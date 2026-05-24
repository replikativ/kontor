(ns kontor.l10n-mx.cgt-provider
  "MX capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 132.

   ## ONE provider, two callable shapes

   Per note 132 §5 MX has a single CGT provider with a `:kind`
   discriminator (`:individual` | `:corporation`) — both kinds share
   the asset-class dispatch + the same statute, but the resulting
   component shape differs:

   - **`:individual`** (personas físicas) — up to FOUR components
     depending on which asset classes are present:
       - `:mx-pf-real-estate-art-120` — real-estate gain after the
         art. 93-XIX-a casa-habitación cap + art. 120 averaging split
         (acumulable folds into PIT via `:pit-base-additions`;
         no-acumulable taxed at the resulting effective rate computed
         from a coupled PIT pass — see TODO below). Surtaxed by the
         art. 127 state 5 % via `kontor.statute`.
       - `:mx-pf-bolsa-art-129` — listed-share gains netted within
         the bolsa lane, 10 % definitive flat.
       - `:mx-pf-unlisted-art-22` — unlisted-share gains after the
         CUFIN/CUCA adjustment, fold into PIT base.
       - `:mx-pf-clawback-cooling-off` — surfaced when a prior
         casa-habitación exemption is invalidated (rare).
     Notary withholdings (art. 126 federal + art. 127 state, both
     consumer-supplied via `:inputs`) reduce the `:prepaid` slot.

   - **`:corporation`** (personas morales) — ONE component:
       - `:mx-pm-cgt-fold` — net cap gain across all asset classes
         folds into the CIT base via `:cit-base-additions`. The 30 %
         art. 9 rate fires at the CIT provider, not here.

   - **`:individual` with `:tax-unit :mx-residence-status
     :non-resident`** — switches the provider to the Title V lane:
       - real-estate: 25 % on gross OR 35 % on net (elective per
         `:disposal/elective-regime` containing
         `:mx-art-161-dictamen-on-net`).
       - shares: same gross/net election under art. 161.

   ## Composition with the existing MX providers

   The consumer composes this provider with `mx-isr-corporate-provider`
   (for personas morales) or `mx-isr-personal-provider` (for personas
   físicas) by reading `:jurisdiction-specific-codes :pit-base-additions`
   / `:cit-base-additions` and threading them downstream — mirroring
   the US/CA/DE template + `kontor.sole-proprietor` (ADR-100).

   ## Art. 120 averaging — TODO

   The art. 120 averaging mechanism (gain / years-held → acumulable +
   no-acumulable, the latter taxed at an effective rate computed from
   a coupled PIT pass) is the headline MX-specific feature. v1
   implements the linear split — `acumulable = gain / years-held`,
   `no-acumulable = gain × (years-held - 1) / years-held` — and surfaces
   both via `:line-items` with the acumulable folded into
   `:pit-base-additions` for downstream PIT inclusion. The
   `no-acumulable` portion is exposed as a `:line-item` annotated with
   the effective-rate computation TODO; the full cross-provider
   two-pass coupling against `mx-isr-personal-provider` is deferred
   (note 132 §5 — \"cleanest cross-provider seam example\").

   ## INPC

   Out of scope (note 132 §4 Gap A). `:disposal/basis-amount` is
   consumer-supplied already-indexed for inflation between acquisition
   and disposition months. Storing a 30-year monthly INPC series in
   `:parameter`s is a Phase 3 decision per note 132 §6.

   ## DisposalSource

   Depends on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`); a consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Closed asset-class sets per kind
;; ============================================================================

(def individual-asset-classes
  "Asset classes the individual (persona física) MX CGT provider
   recognises. Disposals with another asset class are silently dropped
   (forward-compat with later substrate additions)."
  #{:mx-inmueble-residencia
    :mx-inmueble
    :mx-bmv-shares
    :mx-unlisted-shares
    :mx-non-resident-prop})

(def corporate-asset-classes
  "Asset classes the corporate (persona moral) MX CGT provider
   recognises."
  #{:mx-inmueble
    :mx-inmueble-comercial
    :mx-bmv-shares
    :mx-unlisted-shares
    :mx-fixed-asset
    :mx-land
    :mx-intangible})

(def non-resident-asset-classes
  "Asset classes routed to the Title V non-resident lane (art. 160 / 161)."
  #{:mx-non-resident-prop
    :mx-non-resident-shares})

;; ============================================================================
;; Small helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- years-between
  "Whole calendar years between two `java.util.Date` instants — a
   day-count divide (≈365.25). MX uses calendar years for the art. 120
   divisor; the consumer can pass `:inputs :mx-years-held-override`
   to force a value if a holding straddles a leap-year boundary."
  ^java.math.BigDecimal [^java.util.Date a ^java.util.Date b]
  (let [ms (- (.getTime b) (.getTime a))
        days (/ (double ms) (* 1000 60 60 24))]
    (bigdec (Math/floor (max 1.0 (/ days 365.25))))))

(defn- gain-of
  "Realised gain (positive) or loss (negative) on one disposal:
   `proceeds - basis - rollover`. The basis is treated as already
   INPC-adjusted (note 132 §4 Gap A)."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- proceeds-of
  ^java.math.BigDecimal [disposal]
  (or (:disposal/proceeds-amount disposal) 0M))

(defn- years-held
  "Compute years-held (capped, with override support). The cap is the
   art. 120 / 122 parameter (20 for gains, 10 for losses)."
  ^java.math.BigDecimal [disposal cap-years input-override]
  (let [raw (or input-override
                (years-between (:disposal/acquired-on disposal)
                               (:disposal/disposed-on disposal)))]
    (cond
      (nil? raw) 1M
      (zero? raw) 1M
      :else (min raw cap-years))))

;; ============================================================================
;; Casa habitación 700k UDIS cap — applied at proceeds level
;; ============================================================================

(defn- casa-habitacion-split
  "Apply the art. 93-XIX-a 700 000 UDIS cap to a casa-habitación
   disposal. Returns `{:taxable-fraction <bigdec> :exempt-fraction
   <bigdec> :cap-mxn <bigdec> :proceeds <bigdec>}`.

   The cap is a PROCEEDS-SIDE limit:
     - If proceeds ≤ 700k UDIS × UDI rate (in MXN), the disposal is
       FULLY exempt (taxable-fraction = 0).
     - Otherwise the EXEMPT slice is the cap, the TAXABLE slice is
       the excess; basis and gain split proportionally.

   `udi-rate` is the MXN value of one UDI on the disposition date,
   consumer-supplied via `:inputs :mx-udis-rate` (the daily UDI series
   is too large for a `:parameter` snapshot — note 132 §6).

   `residence-cap-used` is the running total of casa-habitación
   exemptions already claimed in the cooling-off window (`:inputs
   :mx-residence-cap-used`). When ≥ 1, the cap has already been used
   in the past 3 years — the provider does NOT block (the consumer
   enforces the cooling-off; see note 132 §4 Gap E) but the consumer
   that supplied a non-zero value has acknowledged the prior use.

   Per LISR art. 93-XIX-a the cooling-off rule is binary (a single
   exemption per 3-year window); v1 treats `residence-cap-used > 0`
   as \"cap already consumed\" and produces a fully-taxable disposal
   (taxable-fraction = 1)."
  [disposal cap-udis udi-rate residence-cap-used]
  (let [proceeds (proceeds-of disposal)
        cap-mxn  (* (or cap-udis 700000M)
                    (or udi-rate 1M))]
    (cond
      ;; Cap already used in the cooling-off window → fully taxable.
      (pos? (or residence-cap-used 0M))
      {:taxable-fraction 1M :exempt-fraction 0M
       :cap-mxn cap-mxn :proceeds proceeds
       :cap-already-used? true}
      ;; Below the cap → fully exempt.
      (<= proceeds cap-mxn)
      {:taxable-fraction 0M :exempt-fraction 1M
       :cap-mxn cap-mxn :proceeds proceeds}
      :else
      (let [taxable (- proceeds cap-mxn)
            tf      (with-precision 12 (/ taxable proceeds))]
        {:taxable-fraction tf
         :exempt-fraction  (- 1M tf)
         :cap-mxn          cap-mxn
         :proceeds         proceeds}))))

;; ============================================================================
;; Art. 120 averaging — linear annual-portion split
;; ============================================================================

(defn- art-120-split
  "Apply art. 120 averaging to a gain. Returns `{:acumulable
   :no-acumulable :years-held}` — the acumulable folds into PIT, the
   no-acumulable is taxed at the resulting effective rate (TODO: full
   PIT coupling per note 132 §5).

   For v1 we compute the linear split:
     - acumulable     = gain / years-held
     - no-acumulable  = gain × (years-held - 1) / years-held

   `:years-held` is capped at 20 per art. 120 (parameter
   `MX.CGT.art-120.gain-years-cap`)."
  [gain years-held]
  (let [yh (max 1M years-held)
        acumulable    (with-precision 12 (/ gain yh))
        no-acumulable (- gain acumulable)]
    {:acumulable    acumulable
     :no-acumulable no-acumulable
     :years-held    yh}))

;; ============================================================================
;; Art. 22 costo promedio — CUFIN / CUCA fold
;; ============================================================================

(defn- art-22-adjusted-gain
  "Compute the art. 22 gain on an unlisted-share disposal: starts from
   the consumer-supplied INPC-adjusted basis, then adds the holder's
   proportional share of the CUFIN increase (prevents double-tax of
   already-taxed retained earnings), subtracts CUCA capital
   reductions (consumer-supplied via `:inputs :mx-share-adjustments`,
   keyed by the disposal's `:disposal/external-id` — see note 132 §4
   Gap B).

   Returns a map `{:gain :adjusted-basis :cufin-add :cuca-deduct}`."
  [disposal share-adjustments]
  (let [adj          (get share-adjustments (:disposal/external-id disposal) {})
        proceeds     (proceeds-of disposal)
        basis        (or (:disposal/basis-amount disposal) 0M)
        ownership    (or (:disposal/ownership-fraction disposal) 1M)
        cufin-delta  (or (:cufin-delta adj) 0M)
        cufin-add    (* cufin-delta ownership)
        cuca-deduct  (or (:cuca-reduction adj) 0M)
        pending-loss (or (:pending-losses adj) 0M)
        adj-basis    (- (+ basis cufin-add) cuca-deduct pending-loss)
        gain         (- proceeds adj-basis)]
    {:gain           gain
     :adjusted-basis adj-basis
     :cufin-add      cufin-add
     :cuca-deduct    cuca-deduct
     :pending-loss   pending-loss}))

;; ============================================================================
;; Components — individual
;; ============================================================================

(defn- real-estate-component
  "Real-estate component (art. 120 averaging path). Handles the
   casa-habitación cap when the asset class is
   `:mx-inmueble-residencia`. The acumulable portion folds into PIT
   base; the no-acumulable portion is currently surfaced as a
   `:line-item` annotated with the TODO for the cross-provider
   effective-rate coupling.

   Notary withholdings (art. 126 federal + art. 127 state, both
   consumer-supplied) ride `:prepaid` and the state-5 % surtax is
   computed from the statute provision.

   Returns nil when there is no taxable real-estate gain (full
   exemption / pure loss in v1)."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal cap-udis
   ^java.math.BigDecimal gain-years-cap disposal]
  (let [asset-class    (:disposal/asset-class disposal)
        inputs         (:inputs ctx)
        udi-rate       (get inputs :mx-udis-rate)
        cap-used       (get inputs :mx-residence-cap-used 0M)
        yh-override    (get inputs :mx-years-held-override)
        casa?          (= asset-class :mx-inmueble-residencia)
        gross-gain     (gain-of disposal)
        ;; Apply casa-habitación cap when relevant.
        cap-split      (when casa? (casa-habitacion-split disposal cap-udis udi-rate cap-used))
        taxable-frac   (if casa? (:taxable-fraction cap-split) 1M)
        taxable-gain   (* gross-gain taxable-frac)]
    (when (pos? taxable-gain)
      (let [yh            (years-held disposal gain-years-cap yh-override)
            split         (art-120-split taxable-gain yh)
            acumulable    (:acumulable split)
            no-acumulable (:no-acumulable split)
            ;; Art. 127 state 5 % surtax on the gain (provider-side
            ;; computation — the statute provision documents the rate;
            ;; the provider reads it directly from the parameter).
            state-rate    (statute/parameter-value-at
                           (:db ctx) "MX.CGT.art-127.state-notary-rate" (as-of-from-ctx ctx))
            state-surtax  (* taxable-gain state-rate)
            ;; Notary federal + state prepayments (consumer-supplied).
            fed-prepaid   (or (get inputs :mx-isr-retencion-federal) 0M)
            state-prepaid (or (get inputs :mx-isr-retencion-estatal) 0M)
            total-prepaid (+ fed-prepaid state-prepaid)
            ;; Liability v1: surfaces the state surtax as part of the
            ;; in-period assessment; the federal portion is computed
            ;; at PIT (acumulable into base + no-acumulable as a fold
            ;; the consumer wires per the TODO).
            line-items
            (cond-> [{:line :gross-gain
                      :label "Ganancia bruta (proceeds − basis − rollover)"
                      :value (money/money gross-gain commodity)}
                     {:line :years-held
                      :label "Years held (capped at art. 120 limit)"
                      :value yh}
                     {:line :acumulable
                      :label "Ganancia acumulable (gain / years)"
                      :value (money/money acumulable commodity)}
                     {:line :no-acumulable
                      :label "Ganancia no acumulable (gain × (years-1) / years) — TODO: cross-provider effective-rate coupling against mx-isr-personal-provider"
                      :value (money/money no-acumulable commodity)}
                     {:line :state-surtax
                      :label "Art. 127 state 5 % notary surtax"
                      :value (money/money state-surtax commodity)}]
              casa?
              (conj {:line :casa-habitacion-cap
                     :label "Art. 93-XIX-a casa-habitación cap (700 000 UDIS)"
                     :value (money/money (:cap-mxn cap-split) commodity)}
                    {:line :taxable-fraction
                     :label "Casa-habitación taxable fraction"
                     :value taxable-frac})
              (pos? fed-prepaid)
              (conj {:line :notary-federal
                     :label "Art. 126 federal notary withholding (prepaid)"
                     :value (money/money fed-prepaid commodity)})
              (pos? state-prepaid)
              (conj {:line :notary-state
                     :label "Art. 127 state 5 % notary withholding (prepaid)"
                     :value (money/money state-prepaid commodity)}))]
        {:kind            :capital-gains-tax
         :authority       authority
         :base            (money/money taxable-gain commodity)
         :schedule        nil
         :gross-liability (money/money state-surtax commodity)
         :liability       (money/money (max 0M (- state-surtax total-prepaid)) commodity)
         :prepaid         (money/money total-prepaid commodity)
         :line-items      line-items
         :jurisdiction-specific-codes
         {:pit-base-additions [acumulable]
          :lane               :mx-pf-real-estate-art-120
          :no-acumulable      no-acumulable
          :years-held         yh
          :asset-class        asset-class
          :casa-habitacion?   casa?
          :art-120-todo       "no-acumulable taxed at effective-rate from PIT coupling — note 132 §5"}}))))

(defn- bolsa-component
  "BMV / BIVA listed-share component — art. 129 10 % definitive flat
   on the net annual bolsa gain. Loss-carry within lane (10 years).
   The broker withholding (consumer-supplied via `:inputs
   :mx-bmv-broker-withheld`) sits in `:prepaid`."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-bolsa]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (statute/parameter-value-at db "MX.CGT.art-129.bolsa-rate" as-of)
        gross     (* net-bolsa rate)
        broker-wh (or (get-in ctx [:inputs :mx-bmv-broker-withheld]) 0M)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-bolsa commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money (max 0M (- gross broker-wh)) commodity)
     :prepaid         (money/money broker-wh commodity)
     :line-items      [{:line :bolsa-net
                        :label "Net BMV/BIVA gain"
                        :value (money/money net-bolsa commodity)}
                       {:line :bolsa-tax
                        :label "Art. 129 — 10 % definitive flat"
                        :value (money/money gross commodity)}
                       {:line :broker-withholding
                        :label "Intermediary broker withholding (prepaid)"
                        :value (money/money broker-wh commodity)}]
     :jurisdiction-specific-codes {:lane :mx-pf-bolsa-art-129}}))

(defn- unlisted-component
  "Unlisted-share component (persona física) — art. 22 costo promedio
   with CUFIN/CUCA adjustment. The resulting net gain folds into PIT
   base via `:pit-base-additions` (PF flat treatment is not separate
   from PIT — the gain rides on top of the cumulative PIT base)."
  [{:keys [commodity]} ctx authority disposals]
  (let [share-adjustments (or (get-in ctx [:inputs :mx-share-adjustments]) {})
        details (mapv #(merge {:disposal %} (art-22-adjusted-gain % share-adjustments)) disposals)
        net (reduce + 0M (map :gain details))]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (into [{:line :unlisted-net
                              :label "Art. 22 net (Σ proceeds − Σ adjusted basis)"
                              :value (money/money net commodity)}]
                            (map (fn [{:keys [disposal gain adjusted-basis cufin-add cuca-deduct]}]
                                   {:line :unlisted-detail
                                    :label (str "Unlisted gain " (:disposal/external-id disposal))
                                    :value {:gain           (money/money gain commodity)
                                            :adjusted-basis (money/money adjusted-basis commodity)
                                            :cufin-add      (money/money cufin-add commodity)
                                            :cuca-deduct    (money/money cuca-deduct commodity)}})
                                 details))
     :jurisdiction-specific-codes {:pit-base-additions [net]
                                   :lane               :mx-pf-unlisted-art-22}}))

;; ============================================================================
;; Components — non-resident (Title V)
;; ============================================================================

(defn- non-resident-component
  "Title V non-resident component — art. 160 (real estate) or art. 161
   (shares). Two rates per asset:
     - 25 % on gross consideration (default), OR
     - 35 % on net gain with `:elective-regime
       :mx-art-161-dictamen-on-net` (the dictamen + Mexican
       representative election)."
  [{:keys [commodity authority]} ctx disposal]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        asset-class (:disposal/asset-class disposal)
        elections   (set (or (:disposal/elective-regime disposal) #{}))
        net-elect?  (contains? elections :mx-art-161-dictamen-on-net)
        rate-code   (case [asset-class net-elect?]
                      [:mx-non-resident-prop true]    "MX.CGT.art-160.nr-real-estate-net-rate"
                      [:mx-non-resident-prop false]   "MX.CGT.art-160.nr-real-estate-gross-rate"
                      [:mx-non-resident-shares true]  "MX.CGT.art-161.nr-shares-net-rate"
                      [:mx-non-resident-shares false] "MX.CGT.art-161.nr-shares-gross-rate"
                      "MX.CGT.art-161.nr-shares-gross-rate")
        rate        (statute/parameter-value-at db rate-code as-of)
        base        (if net-elect? (gain-of disposal) (proceeds-of disposal))
        base        (max 0M base)
        liability   (* base rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money base commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money liability commodity)
     :liability       (money/money liability commodity)
     :prepaid         (money/zero commodity)
     :regime          (if net-elect? :mx-art-161-dictamen-on-net :mx-nr-default-gross)
     :line-items      [{:line :nr-rate :label (str "NR rate (" rate-code ")") :value rate}
                       {:line :nr-base :label (if net-elect? "Net gain" "Gross consideration")
                        :value (money/money base commodity)}
                       {:line :nr-tax  :label "NR tax" :value (money/money liability commodity)}]
     :jurisdiction-specific-codes {:lane         :mx-nr
                                   :nr-mode      (if net-elect? :nr-net :nr-gross)
                                   :asset-class  asset-class
                                   :rate-code    rate-code}}))

;; ============================================================================
;; Components — corporation
;; ============================================================================

(defn- corp-net-component
  "Personas-morales fold — net cap gain across all asset classes folds
   into the CIT base via `:cit-base-additions`. The 30 % art. 9 rate
   fires at the CIT provider (mx-isr-corporate-provider), NOT here."
  [{:keys [commodity authority]} ^java.math.BigDecimal net-capital
   share-adjustments-detail]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money net-capital commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      (into [{:line :net-capital :label "Net capital gain (folds into CIT)"
                            :value (money/money net-capital commodity)}]
                          (map (fn [{:keys [disposal gain cufin-add cuca-deduct]}]
                                 {:line :detail
                                  :label (str "Detail " (:disposal/external-id disposal))
                                  :value {:gain        (money/money gain commodity)
                                          :cufin-add   (money/money cufin-add commodity)
                                          :cuca-deduct (money/money cuca-deduct commodity)}})
                               share-adjustments-detail))
   :jurisdiction-specific-codes {:cit-base-additions [net-capital]
                                 :lane :mx-pm-cgt-fold}})

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord MXCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for MX CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          cap-udis    (statute/parameter-value-at db "MX.CGT.casa-habitacion-cap-udis" as-of)
          gain-cap    (statute/parameter-value-at db "MX.CGT.art-120.gain-years-cap" as-of)
          disposals   (ds/disposals-in source entity period)
          opts        {:authority authority :commodity commodity}
          residence-status (or (get-in ctx [:tax-unit :mx-residence-status]) :resident)
          ;; Group disposals by asset class for dispatch.
          by-class    (group-by :disposal/asset-class disposals)
          components
          (case kind
            :individual
            (cond
              ;; Non-resident path — Title V.
              (= residence-status :non-resident)
              (->> disposals
                   (filter #(contains? non-resident-asset-classes (:disposal/asset-class %)))
                   (mapv #(non-resident-component opts ctx %))
                   (remove nil?)
                   vec)

              :else
              (let [real-estate    (concat (get by-class :mx-inmueble-residencia [])
                                           (get by-class :mx-inmueble []))
                    bolsa          (get by-class :mx-bmv-shares [])
                    unlisted       (get by-class :mx-unlisted-shares [])
                    ;; Real-estate components — one per disposal because
                    ;; art. 120 averaging is per-asset (years-held differs).
                    re-cmps        (->> real-estate
                                        (mapv #(real-estate-component opts ctx cap-udis gain-cap %))
                                        (remove nil?)
                                        vec)
                    ;; Bolsa net + lane carry-in.
                    bolsa-gross    (reduce + 0M (map gain-of bolsa))
                    bolsa-carry    (or (get-in inputs [:capital-loss-carryforward :mx-bolsa]) 0M)
                    bolsa-net      (max 0M (- bolsa-gross bolsa-carry))
                    bolsa-cmp      (when (pos? bolsa-net)
                                     (bolsa-component opts ctx bolsa-net))
                    ;; Unlisted (art. 22) — folds into PIT.
                    unlisted-cmp   (when (seq unlisted)
                                     (unlisted-component opts ctx authority unlisted))]
                (->> (concat re-cmps [bolsa-cmp unlisted-cmp])
                     (remove nil?)
                     vec)))

            :corporation
            (let [share-adj (or (get-in inputs [:mx-share-adjustments]) {})
                  details
                  (->> disposals
                       (filter #(contains? corporate-asset-classes (:disposal/asset-class %)))
                       (mapv (fn [d]
                               (if (#{:mx-unlisted-shares} (:disposal/asset-class d))
                                 (assoc (art-22-adjusted-gain d share-adj) :disposal d)
                                 {:disposal      d
                                  :gain          (gain-of d)
                                  :cufin-add     0M
                                  :cuca-deduct   0M}))))
                  gross    (reduce + 0M (map :gain details))
                  carry    (or (get-in inputs [:capital-loss-carryforward :mx-capital]) 0M)
                  net-cap  (max 0M (- gross carry))]
              (if (pos? net-cap)
                [(corp-net-component opts net-cap details)]
                []))

            (throw (ex-info "MX CGT provider :kind must be :individual or :corporation"
                            {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :mx :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn mx-individual-cgt-provider
  "Build an MX persona-física CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol)."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->MXCapitalGainsTaxProvider (or id :mx-cgt-individual) source :mx-sat :MXN
                               "LISR Título IV Cap IV (arts 93-XIX-a, 119-127, 129) + Título V (arts 160-161)"
                               :individual))

(defn mx-corporate-cgt-provider
  "Build an MX persona-moral CGT provider. Required: `:source`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->MXCapitalGainsTaxProvider (or id :mx-cgt-corporate) source :mx-sat :MXN
                               "LISR Título II Cap I (arts 9, 18-IV, 19, 22, 22-A)"
                               :corporation))

(defn install-statute!
  "Install the MX CGT statute (parameters + provisions) into `conn`."
  [conn]
  (cgt-statute/install! conn))
