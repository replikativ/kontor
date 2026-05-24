(ns kontor.l10n-fr.cgt-provider
  "FR capital-gains tax providers — TWO `PeriodTaxProvider`s
   (ADR-099) over the ADR-102 disposal substrate + ADR-101 statute-as-
   data. Research note 128 is the FR-CGT fit assessment.

   FR is the MOST COMPLEX CGT regime in the cross-jurisdiction set —
   five statutory shapes (note 128 §1), per-asset-class PS-rate
   carve-outs (§5.4), and a per-foyer barème election (§1.1). The
   substrate fit is otherwise clean: only enumerant additions on the
   shipped `:disposal/asset-class` / `:elective-regime` /
   `:exemption-claimed` / `:loss-bucket` slots (note 128 §4) — no
   schema change.

   ## Two providers, one statute

   - `fr-corporate-cgt-provider` — IS-side. Two components:
       1. Titres de participation (CGI Art. 219) — exonération + 12 %
          QPFC reintegration. Routes the QPFC via `:cit-base-additions`
          so the FR CIT provider picks it up at the next period-tax
          pass (mirrors DE §8b at note 113 §5.1).
       2. Brevets (CGI Art. 238) — IP-box at 10 % preferential rate,
          nexus-ratio-weighted (the ratio is a consumer-supplied
          `:inputs :ip-box {:nexus-ratio …}` per disposal).
       Loss on titres de participation is sealed (non-deductible) —
       v1 silently drops it (the `:fr-mv-titres-participation` bucket
       carries no carryforward).

   - `fr-personal-cgt-provider` — PIT-side. Four lanes:
       1. **Mobilière** (CGI Art. 150-0 A) — default PFU 31.4 %
          (12.8 % IR + 18.6 % PS post-LFSS-2026) OR barème election
          (folds into PIT base via `:pit-base-additions`). Abattement-
          durée applies ONLY under barème AND for pre-2018 acquisitions
          (1 ter général 50/65 % @ 2y/8y; 1 quater renforcé 50/65/85 %
          @ 1y/4y/8y for `:fr-titres-pme`). PS layer at 18.6 % on
          gross gain (NOT abattement-reduced — Art. 150-0 D 4°).
       2. **Immobilière** (CGI Art. 150 U) — 19 % IR + 17.2 % PS
          (CARVE-OUT from LFSS 2026 raise) + progressive surtaxe
          (Art. 1609 nonies G, 2-6 % > €50 000). TWO separate
          abattement ladders — IR fully exempt at 22y; PS fully
          exempt at 30y. Residence principale → zero everything.
       3. **Pro court-terme** (CGI Art. 39 duodecies) — ordinary
          income, folds into PIT base via `:pit-base-additions`.
          §151 septies revenue test + §238 quindecies transmission
          cliff fully exempt or degressive.
       4. **Pro long-terme** (CGI Art. 39 quindecies) — 12.8 % IR +
          17.2 % PS preferential rate. Same exemption regimes.

       PEA exonération (CGI Art. 157 5°) at any asset-class
       `:fr-pea` / `:fr-pea-pme` zeroes the IR component but still
       emits the PS surtax.

   ## Composition with the FR CIT/PIT provider

   The consumer threads the CGT provider's `TaxReturnFacts` to the
   CIT/PIT provider via `:inputs :base-transform`:

     {:pit-base-additions  [<bigdec> …]   ; ST mobilière under barème,
                                           ;  pro CT, ordinary recapture
      :cit-base-additions  [<bigdec> …]}  ; titres de participation QPFC

   The composition is consumer-side (mirrors `kontor.sole-proprietor`
   ADR-100 between business-net and PIT).

   ## DisposalSource

   The provider depends on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`); a consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components.

   ## Loss buckets (note 128 §1.8)

   v1 honours `:inputs :capital-loss-carryforward` as a per-bucket
   BigDecimal:

     {:fr-mv-mobilière   <bigdec>   ; 10-year carry, offset before abattement
      :fr-mv-pro-long    <bigdec>}  ; 10-year carry, sealed bucket

   The mobilière convention is to apply the carry to the NET gain
   BEFORE the abattement (Art. 150-0 D, 11° + BOI-RPPM-PVBMI-20-10-40
   note 128 §1.8). Per-vintage carry tracking (which 2014 vintage
   expires when) is deferred — v1 takes a single pool BigDecimal."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-fr.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

;; ============================================================================
;; Constants — the closed lane keywords
;; ============================================================================

(def asset-classes
  "Closed v1 set of FR-namespaced `:disposal/asset-class` enumerants
   the provider recognizes. Note 128 §4.1. A disposal with an
   unrecognized `:asset-class` is silently dropped — the provider
   only fires on the FR vocabulary."
  #{:fr-titres-listed
    :fr-titres-pme
    :fr-titres-participation
    :fr-immobilier-residence
    :fr-immobilier-autre
    :fr-immobilier-terrain-batir
    :fr-immobilier-spi
    :fr-pro-court-terme
    :fr-pro-long-terme
    :fr-brevet
    :fr-pea
    :fr-pea-pme})

(def loss-buckets
  "Closed v1 set of FR-namespaced `:disposal/loss-bucket` enumerants.
   Note 128 §1.8 / §4.4."
  #{:fr-mv-mobilière
    :fr-mv-immobilière
    :fr-mv-pro-court
    :fr-mv-pro-long
    :fr-mv-titres-participation})

(def elective-regimes
  "Closed v1 set of FR-namespaced `:disposal/elective-regime`
   enumerants. Note 128 §4.2."
  #{:fr-pfu
    :fr-barème
    :fr-150-0-B-ter-apport-cession
    :fr-sursis-imposition
    :fr-167-bis-exit-tax
    :fr-ip-box-238
    :fr-étalement-3-ans})

(def exemptions
  "Closed v1 set of FR-namespaced `:disposal/exemption-claimed`
   enumerants. Note 128 §4.3."
  #{:fr-pea-exoneration
    :fr-residence-principale
    :fr-151-septies-pme
    :fr-238-quindecies-transmission
    :fr-abattement-durée
    :fr-15000-vente
    :fr-réinvestissement-rp})

(def ^:private pfu-asset-classes
  "Asset classes that route through the mobilière (Art. 150-0 A) lane."
  #{:fr-titres-listed :fr-titres-pme :fr-pea :fr-pea-pme})

(def ^:private immo-asset-classes
  "Asset classes that route through the immobilière (Art. 150 U) lane."
  #{:fr-immobilier-residence :fr-immobilier-autre
    :fr-immobilier-terrain-batir :fr-immobilier-spi})

;; ============================================================================
;; Utilities — gain math, holding period, exemption claim
;; ============================================================================

(defn- realized-gain
  "Positive gain or negative loss in the proceeds commodity:
   `proceeds − basis − rollover-amount`."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- whole-years-between
  "Whole calendar years between two `java.util.Date` instants, rounded
   DOWN (FR's standard convention — the day-by-day count divided by
   365.25 floored)."
  ^long [^java.util.Date a ^java.util.Date b]
  (long (Math/floor (/ (double (- (.getTime b) (.getTime a)))
                       (* 1000.0 60.0 60.0 24.0 365.25)))))

(defn- holding-period-years
  ^long [disposal]
  (let [acq (:disposal/acquired-on disposal)
        dis (:disposal/disposed-on disposal)]
    (if (and acq dis)
      (whole-years-between acq dis)
      0)))

(defn- exemption-claimed?
  "True when `disposal` carries the named `:exemption-claimed`."
  [disposal exemption-kw]
  (let [claimed (:disposal/exemption-claimed disposal)]
    (boolean (and claimed (contains? (set claimed) exemption-kw)))))

(defn- elective?
  [disposal regime-kw]
  (let [eless (:disposal/elective-regime disposal)]
    (boolean (and eless (contains? (set eless) regime-kw)))))

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code as-of]
  (statute/parameter-value-at db code as-of))

;; ============================================================================
;; Abattement-durée — mobilière (CGI Art. 150-0 D 1 ter + 1 quater)
;; ============================================================================

(defn- abat-mobiliere-pct
  "Return the abattement percentage (0-1) to apply to a mobilière net
   gain. Only fires for shares acquired before 2018-01-01 AND under
   the barème election (the consumer's responsibility to elect — the
   provider applies the math only when barème? is true).

   The `:fr-titres-pme` asset-class with `:exemption-claimed`
   `:fr-abattement-durée` AND the PME eligibility flags (8-point
   checklist per note 128 §1.2) gets the *renforcé* ladder
   (1 quater 50/65/85 % @ 1y/4y/8y); otherwise *général*
   (1 ter 50/65 % @ 2y/8y).

   v1 reads `:fr-titres-pme` AS the renforcé signal (the provider
   does NOT re-check the 8-point eligibility — that's a consumer-
   side concern; the consumer either tags the asset-class
   appropriately or doesn't claim the renforcé ladder)."
  ^java.math.BigDecimal [disposal]
  (let [years (holding-period-years disposal)
        acq   (:disposal/acquired-on disposal)
        pre-2018? (and acq (< (.getTime acq) (.getTime #inst "2018-01-01")))
        claimed?  (exemption-claimed? disposal :fr-abattement-durée)
        pme?      (= :fr-titres-pme (:disposal/asset-class disposal))]
    (cond
      (not (and pre-2018? claimed?)) 0M
      pme?
      (cond (< years 1) 0M
            (< years 4) 0.50M
            (< years 8) 0.65M
            :else       0.85M)
      :else
      (cond (< years 2) 0M
            (< years 8) 0.50M
            :else       0.65M))))

;; ============================================================================
;; Abattement-durée — immobilière (TWO ladders: IR + PS)
;; ============================================================================

(defn- immo-IR-abat-pct
  "Cumulative IR abattement for a real-estate disposal at `years`
   holding period. Years 1-5: 0 %; years 6-21: 6 %/year; year 22:
   final 4 % → 100 % at ≥22y."
  ^java.math.BigDecimal [^long years]
  (cond
    (< years 6)  0M
    (< years 22) (* (- years 5) 0.06M)
    :else        1M))

(defn- immo-PS-abat-pct
  "Cumulative PS abattement for a real-estate disposal at `years`.
   Years 1-5: 0 %; years 6-21: 1.65 %/year; year 22: 1.6 %; years
   23-30: 9 %/year → 100 % at ≥30y."
  ^java.math.BigDecimal [^long years]
  (cond
    (< years 6)  0M
    (< years 22) (* (- years 5) 0.0165M)
    (= years 22) (+ (* 16 0.0165M) 0.016M)
    (< years 30) (+ (* 16 0.0165M) 0.016M (* (- years 22) 0.09M))
    :else        1M))

;; ============================================================================
;; Surtaxe immobilière (Art. 1609 nonies G) — 2-6 % progressive on net IR gain
;; ============================================================================

(defn- surtaxe-1609-nonies-G
  "Progressive surtaxe on net IR-taxable immobilière gain above
   €50 000. Bracket table (Notaires de Paris):

     ≤ 50 000        0 %
     50 001-100 000  2 %
     100 001-150 000 3 %
     150 001-200 000 4 %
     200 001-250 000 5 %
     > 250 000       6 %

   Transition formulas in the per-€10 k bands are not modelled in v1
   (note 128 §1.4 — the cliff approximation under-states surtaxe
   by ≤€2 000 at the bracket edges, an acceptable v1 deviation)."
  ^java.math.BigDecimal [^java.math.BigDecimal net-ir-gain]
  (cond
    (<= (compare net-ir-gain 50000M) 0)  0M
    (<= (compare net-ir-gain 100000M) 0) (* 0.02M (- net-ir-gain 50000M))
    (<= (compare net-ir-gain 150000M) 0) (+ (* 0.02M 50000M)
                                            (* 0.03M (- net-ir-gain 100000M)))
    (<= (compare net-ir-gain 200000M) 0) (+ (* 0.02M 50000M) (* 0.03M 50000M)
                                            (* 0.04M (- net-ir-gain 150000M)))
    (<= (compare net-ir-gain 250000M) 0) (+ (* 0.02M 50000M) (* 0.03M 50000M)
                                            (* 0.04M 50000M)
                                            (* 0.05M (- net-ir-gain 200000M)))
    :else                                (+ (* 0.02M 50000M) (* 0.03M 50000M)
                                            (* 0.04M 50000M) (* 0.05M 50000M)
                                            (* 0.06M (- net-ir-gain 250000M)))))

;; ============================================================================
;; §151 septies revenue-tested exemption — proportional reduction
;; ============================================================================

(defn- §151-septies-fraction
  "Return the fraction (0-1) of a pro plus-value that REMAINS TAXABLE
   under §151 septies for the given turnover + activity type. Below
   the full-exemption ceiling → 0 (fully exempt). In the degressive
   band → linearly interpolates from 0 to 1. Above the degressive
   ceiling → 1 (no exemption).

   `activity` is `:services` (BIC services / BNC; threshold €90 k /
   €126 k) or `:goods` (sales of merchandise / lodging; €250 k /
   €350 k); default `:services` (the more common case for pro CT/LT).

   The provider reads the activity + turnover from
   `:inputs :151-septies {:activity :revenue}`. When the consumer
   does not supply `:inputs :151-septies`, the §151 septies
   exemption is treated as not-claimed (fraction = 1)."
  ^java.math.BigDecimal [db ^java.util.Date as-of {:keys [activity revenue]}]
  (let [[full-key degressive-key] (case activity
                                    :goods    ["FR.CGT.§151-septies.threshold-goods-full"
                                               "FR.CGT.§151-septies.threshold-goods-degressive"]
                                    ["FR.CGT.§151-septies.threshold-services-full"
                                     "FR.CGT.§151-septies.threshold-services-degressive"])
        full       (param db full-key as-of)
        degressive (param db degressive-key as-of)
        r          (or revenue 0M)]
    (cond
      (<= (compare r full) 0)       0M           ; fully exempt
      (>= (compare r degressive) 0) 1M           ; no exemption
      :else
      (let [band (- degressive full)
            into (- r full)]
        (/ (bigdec into) (bigdec band))))))

;; ============================================================================
;; §238 quindecies transmission exemption — proportional reduction
;; ============================================================================

(defn- §238-quindecies-fraction
  "Return the fraction (0-1) of a pro plus-value that REMAINS TAXABLE
   under §238 quindecies for the given `transmission-value`. The
   cliffs are date-keyed (€500 k / €1 M pre-2025; €700 k / €1.2 M
   from FY-2025 per LFI 2024).

   The provider reads the transmission value from `:inputs
   :238-quindecies {:transmission-value <bigdec>}`. When the consumer
   does not supply it, defaults to the disposal's proceeds amount.

   §151 septies + §238 quindecies CANNOT cumulate on the same disposal
   (note 128 §1.5); the consumer's `:disposal/exemption-claimed`
   should pick one."
  ^java.math.BigDecimal [db ^java.util.Date as-of {:keys [transmission-value]}]
  (let [full       (param db "FR.CGT.§238-quindecies.threshold-full" as-of)
        degressive (param db "FR.CGT.§238-quindecies.threshold-degressive" as-of)
        v          (or transmission-value 0M)]
    (cond
      (<= (compare v full) 0)       0M
      (>= (compare v degressive) 0) 1M
      :else
      (let [band (- degressive full)
            into (- v full)]
        (/ (bigdec into) (bigdec band))))))

(defn- exemption-fraction
  "Combined exemption fraction for a single pro disposal — picks the
   relevant exemption and returns the TAXABLE fraction (0-1). If
   neither claimed, returns 1 (fully taxable)."
  ^java.math.BigDecimal [db as-of ctx disposal]
  (let [inputs (:inputs ctx)
        §151? (exemption-claimed? disposal :fr-151-septies-pme)
        §238? (exemption-claimed? disposal :fr-238-quindecies-transmission)]
    (cond
      §238? (§238-quindecies-fraction
             db as-of (merge {:transmission-value (:disposal/proceeds-amount disposal)}
                             (:238-quindecies inputs)))
      §151? (§151-septies-fraction db as-of (:151-septies inputs))
      :else 1M)))

;; ============================================================================
;; Mobilière component — CGI Art. 150-0 A
;; ============================================================================

(defn- mobiliere-component
  "Build the mobilière component (PFU or barème).

   - Aggregate net gain across mobilière disposals (offset losses
     against gains in the bucket).
   - Apply `:fr-mv-mobilière` carry-in BEFORE abattement (Art. 150-0
     D 11°).
   - When barème elected, fold each disposal's abattement-durée
     individually (NET positive gain only — note 128 §1.2 secob
     asymmetry).
   - IR: PFU 12.8 % flat on (post-carry, pre-abat) gain OR fold into
     PIT base via :pit-base-additions (barème).
   - PS: 18.6 % on GROSS gain (NOT abattement-reduced, Art. 150-0 D 4°).
   - PEA exonération zeroes IR for the per-disposal slice tagged."
  [opts ctx mob-disposals]
  (let [{:keys [commodity authority db]} opts
        as-of   (as-of-from-ctx ctx)
        barème? (= :barème (get-in ctx [:tax-unit :pfu-or-bareme]))
        ir-rate (param db "FR.CGT.PFU.IR-rate" as-of)
        ;; Split PEA disposals — they take IR exoneration
        pea? (fn [d] (boolean (or (#{:fr-pea :fr-pea-pme} (:disposal/asset-class d))
                                  (exemption-claimed? d :fr-pea-exoneration))))
        pea-disposals     (filter pea? mob-disposals)
        non-pea-disposals (remove pea? mob-disposals)
        carry-in (or (get-in ctx [:inputs :capital-loss-carryforward :fr-mv-mobilière]) 0M)
        ;; Net non-PEA gains (loss can offset gain within bucket)
        gross-net-non-pea (reduce + 0M (map realized-gain non-pea-disposals))
        post-carry-non-pea (max 0M (- gross-net-non-pea carry-in))
        ;; PEA net (after own loss netting; PEA losses generally not deductible
        ;; outside the envelope's own balance, but for v1 we treat as zero-loss
        ;; pool — only positive gains drive PS).
        pea-net (max 0M (reduce + 0M (map realized-gain pea-disposals)))
        ;; Per-disposal abattement on net positive gains, barème only
        abat-amount (if (and barème? (pos? post-carry-non-pea))
                      ;; weight per disposal by its share of the positive sum
                      (let [pos-sum (reduce + 0M (keep #(let [g (realized-gain %)]
                                                          (when (pos? g) g))
                                                       non-pea-disposals))]
                        (if (pos? pos-sum)
                          (reduce + 0M
                                  (map (fn [d]
                                         (let [g (realized-gain d)]
                                           (when (pos? g)
                                             (let [share-of-net (* post-carry-non-pea (/ g pos-sum))
                                                   pct (abat-mobiliere-pct d)]
                                               (* share-of-net pct)))))
                                       non-pea-disposals))
                          0M))
                      0M)
        ir-base (- post-carry-non-pea abat-amount)
        ir-tax  (if barème?
                  0M                                  ; folds into PIT base
                  (* ir-base ir-rate))
        ;; PS on gross gain (excluding PEA — covered separately at the same rate)
        ps-rate-securities (param db "FR.CGT.PS.default-rate" as-of)
        ps-tax-non-pea     (* (max 0M gross-net-non-pea) ps-rate-securities)
        ;; PEA: zero IR, PS on its gain at the same securities PS rate
        ps-tax-pea         (* pea-net ps-rate-securities)
        total-liability    (+ ir-tax ps-tax-non-pea ps-tax-pea)
        any-gain?          (or (pos? post-carry-non-pea)
                               (pos? pea-net))]
    (when any-gain?
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/money ir-base commodity)
       :schedule        (if barème? nil {:schedule/type :flat :rate ir-rate})
       :gross-liability (money/money (+ ir-tax ps-tax-non-pea ps-tax-pea) commodity)
       :liability       (money/money total-liability commodity)
       :prepaid         (money/zero commodity)
       :regime          (if barème? :fr-barème :fr-pfu)
       :surtaxes        (filterv :amount
                                 [(when (pos? ps-tax-non-pea)
                                    {:code :ps :label "Prélèvements sociaux (mobilière)"
                                     :amount (money/money ps-tax-non-pea commodity)})
                                  (when (pos? ps-tax-pea)
                                    {:code :ps-pea :label "Prélèvements sociaux (PEA, IR exonéré)"
                                     :amount (money/money ps-tax-pea commodity)})])
       :line-items      [{:line :mob-gross-non-pea :label "Plus-values mobilières — net (hors PEA)"
                          :value (money/money gross-net-non-pea commodity)}
                         {:line :mob-pea-net :label "Plus-values mobilières — PEA"
                          :value (money/money pea-net commodity)}
                         {:line :mob-carry-applied :label "Moins-values mobilières en report appliquées"
                          :value (money/money (min carry-in (max 0M gross-net-non-pea)) commodity)}
                         {:line :mob-abat-amount :label "Abattement durée appliqué"
                          :value (money/money abat-amount commodity)}
                         {:line :mob-ir-base :label "Base IR taxable (post-abattement)"
                          :value (money/money ir-base commodity)}
                         {:line :mob-ir-tax :label "IR mobilière"
                          :value (money/money ir-tax commodity)}]
       :jurisdiction-specific-codes
       (cond-> {:lane :fr-mobilière
                :asset-classes (vec (distinct (map :disposal/asset-class mob-disposals)))}
         barème? (assoc :pit-base-additions [ir-base]))})))

;; ============================================================================
;; Immobilière component — CGI Art. 150 U
;; ============================================================================

(defn- immo-disposal-bases
  "For one immobilière disposal, compute (IR-base, PS-base) — gross
   gain × (1 − abat-pct), per separate ladder. Residence principale
   → both zero."
  [disposal]
  (let [g (realized-gain disposal)]
    (cond
      (or (= :fr-immobilier-residence (:disposal/asset-class disposal))
          (exemption-claimed? disposal :fr-residence-principale)
          (true? (:disposal/residence? disposal))) [0M 0M]
      (not (pos? g)) [0M 0M]                  ; losses in the bucket vanish (note 128 §1.8)
      :else
      (let [years (holding-period-years disposal)]
        [(* g (- 1M (immo-IR-abat-pct years)))
         (* g (- 1M (immo-PS-abat-pct years)))]))))

(defn- immo-component
  "Immobilière component: 19 % IR on summed IR-base + 17.2 % PS on
   summed PS-base + progressive surtaxe (Art. 1609 nonies G) on
   IR-base. Two separate abattement ladders per disposal, then
   summed."
  [opts ctx disposals]
  (let [{:keys [commodity authority db]} opts
        as-of    (as-of-from-ctx ctx)
        bases    (map immo-disposal-bases disposals)
        ir-base  (reduce + 0M (map first bases))
        ps-base  (reduce + 0M (map second bases))
        ir-rate  (param db "FR.CGT.Immo.IR-rate" as-of)
        ps-rate  (param db "FR.CGT.PS.real-estate-rate" as-of)
        ir-tax   (* ir-base ir-rate)
        ps-tax   (* ps-base ps-rate)
        surtaxe  (surtaxe-1609-nonies-G ir-base)
        total    (+ ir-tax ps-tax surtaxe)]
    (when (pos? total)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/money ir-base commodity)
       :schedule        {:schedule/type :flat :rate ir-rate}
       :gross-liability (money/money (+ ir-tax surtaxe) commodity)
       :liability       (money/money total commodity)
       :prepaid         (money/zero commodity)
       :surtaxes        [{:code :surtaxe-1609 :label "Surtaxe plus-values immobilières (Art. 1609 nonies G)"
                          :amount (money/money surtaxe commodity)}
                         {:code :ps :label "Prélèvements sociaux (immobilière — 17.2 %)"
                          :amount (money/money ps-tax commodity)}]
       :line-items      [{:line :immo-ir-base :label "Base IR (post-abattement)"
                          :value (money/money ir-base commodity)}
                         {:line :immo-ps-base :label "Base PS (post-abattement)"
                          :value (money/money ps-base commodity)}
                         {:line :immo-ir-tax :label "IR plus-value immobilière"
                          :value (money/money ir-tax commodity)}
                         {:line :immo-ps-tax :label "PS plus-value immobilière"
                          :value (money/money ps-tax commodity)}
                         {:line :immo-surtaxe :label "Surtaxe 1609 nonies G"
                          :value (money/money surtaxe commodity)}]
       :jurisdiction-specific-codes {:lane :fr-immobilière}})))

;; ============================================================================
;; Pro court-terme component — ordinary BIC/BNC (folds into PIT base)
;; ============================================================================

(defn- pro-ct-component
  "Plus-values pro court-terme — fold into PIT base via
   `:pit-base-additions`. §151 septies + §238 quindecies exemptions
   apply per-disposal (proportional reduction)."
  [opts ctx disposals]
  (let [{:keys [commodity authority db]} opts
        as-of  (as-of-from-ctx ctx)
        net    (reduce + 0M
                       (map (fn [d]
                              (let [g (realized-gain d)
                                    f (exemption-fraction db as-of ctx d)]
                                (* g f)))
                            disposals))]
    (when (not (zero? net))
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/money net commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :line-items      [{:line :pro-ct-net :label "Plus-values pro court-terme (post-exemptions)"
                          :value (money/money net commodity)}]
       :jurisdiction-specific-codes {:pit-base-additions [net]
                                     :lane :fr-pro-ct}})))

;; ============================================================================
;; Pro long-terme component — preferential 12.8 % IR + 17.2 % PS
;; ============================================================================

(defn- pro-lt-component
  "Plus-values pro long-terme — 12.8 % IR + 17.2 % PS preferential
   rate. §151 septies + §238 quindecies exemptions apply per-disposal.
   Loss carry from `:fr-mv-pro-long` bucket nets against the positive
   total before tax (Art. 39 quindecies + BOI-BIC-PVMV)."
  [opts ctx disposals]
  (let [{:keys [commodity authority db]} opts
        as-of    (as-of-from-ctx ctx)
        net      (reduce + 0M
                         (map (fn [d]
                                (let [g (realized-gain d)
                                      f (exemption-fraction db as-of ctx d)]
                                  (* g f)))
                              disposals))
        carry-in (or (get-in ctx [:inputs :capital-loss-carryforward :fr-mv-pro-long]) 0M)
        ir-base  (max 0M (- net carry-in))
        ir-rate  (param db "FR.CGT.ProLT.IR-rate" as-of)
        ps-rate  (param db "FR.CGT.PS.real-estate-rate" as-of)
        ir-tax   (* ir-base ir-rate)
        ps-tax   (* ir-base ps-rate)
        total    (+ ir-tax ps-tax)]
    (when (pos? total)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/money ir-base commodity)
       :schedule        {:schedule/type :flat :rate ir-rate}
       :gross-liability (money/money ir-tax commodity)
       :liability       (money/money total commodity)
       :prepaid         (money/zero commodity)
       :surtaxes        [{:code :ps :label "Prélèvements sociaux (pro long-terme — 17.2 %)"
                          :amount (money/money ps-tax commodity)}]
       :line-items      [{:line :pro-lt-net :label "Plus-values pro long-terme (post-exemptions)"
                          :value (money/money net commodity)}
                         {:line :pro-lt-carry :label "Moins-values long-terme en report appliquées"
                          :value (money/money (min carry-in net) commodity)}
                         {:line :pro-lt-base :label "Base IR + PS"
                          :value (money/money ir-base commodity)}]
       :jurisdiction-specific-codes {:lane :fr-pro-lt}})))

;; ============================================================================
;; Titres de participation component — IS-side QPFC 12 %
;; ============================================================================

(defn- titres-participation-component
  "Build the titres-de-participation component. Eligibility test:
   `:asset-class :fr-titres-participation` AND holding period ≥ 2y.
   The gross gain is exonéré at IS; 12 % QPFC reintegrates as a
   `:cit-base-additions` for the FR CIT provider's adjustment layer.
   Losses on titres de participation are sealed (non-deductible —
   note 128 §1.6); v1 silently drops them."
  [opts ctx disposals]
  (let [{:keys [commodity authority db]} opts
        as-of      (as-of-from-ctx ctx)
        min-years  (long (param db "FR.CGT.§219.holding-period-years" as-of))
        qpfc-rate  (param db "FR.CGT.§219.QPFC-rate" as-of)
        qualifying (filter #(and (>= (holding-period-years %) min-years)
                                 (pos? (realized-gain %)))
                           disposals)
        gross-gain (reduce + 0M (map realized-gain qualifying))
        qpfc       (* gross-gain qpfc-rate)]
    (when (pos? gross-gain)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/zero commodity)     ; exonéré au IS
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :line-items      [{:line :tp-gross :label "Plus-value titres de participation (brut — exonérée)"
                          :value (money/money gross-gain commodity)}
                         {:line :tp-qpfc :label "Quote-part de frais et charges (12 %) — réintégrée à l'IS"
                          :value (money/money qpfc commodity)}]
       :jurisdiction-specific-codes {:cit-base-additions [qpfc]
                                     :lane :fr-titres-participation
                                     :gross-exempted-gain gross-gain}})))

;; ============================================================================
;; Brevets / IP box component — CGI Art. 238 — 10 % preferential
;; ============================================================================

(defn- brevets-component
  "Build the brevets / IP-box component (CGI Art. 238 — 10 % rate).
   Requires `:elective-regime` `:fr-ip-box-238` on each qualifying
   disposal. Nexus-ratio weighting: per-disposal ratio comes from
   `:inputs :ip-box {<external-id> {:nexus-ratio <bigdec>}}` —
   default 1 (no nexus reduction)."
  [opts ctx disposals]
  (let [{:keys [commodity authority db]} opts
        as-of     (as-of-from-ctx ctx)
        rate      (param db "FR.CGT.§238.IP-box-rate" as-of)
        ip-inputs (get-in ctx [:inputs :ip-box])
        qualifying (filter #(elective? % :fr-ip-box-238) disposals)
        net-weighted (reduce + 0M
                             (map (fn [d]
                                    (let [g (realized-gain d)
                                          eid (:disposal/external-id d)
                                          nx  (or (get-in ip-inputs [eid :nexus-ratio]) 1M)]
                                      (* g nx)))
                                  qualifying))
        net-pos  (max 0M net-weighted)
        tax      (* net-pos rate)]
    (when (pos? net-pos)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/money net-pos commodity)
       :schedule        {:schedule/type :flat :rate rate}
       :gross-liability (money/money tax commodity)
       :liability       (money/money tax commodity)
       :prepaid         (money/zero commodity)
       :regime          :fr-ip-box-238
       :line-items      [{:line :ip-box-net :label "IP box net (nexus-pondéré)"
                          :value (money/money net-pos commodity)}
                         {:line :ip-box-tax :label "IS taux IP box 10 %"
                          :value (money/money tax commodity)}]
       :jurisdiction-specific-codes {:lane :fr-ip-box}})))

;; ============================================================================
;; The CORPORATE provider — titres de participation + brevets
;; ============================================================================

(defrecord FRCorporateCapitalGainsTaxProvider
           [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for FR corporate CGT provider"
                                        {:ctx-keys (keys ctx)})))
          disposals (ds/disposals-in source entity period)
          ;; Sort disposals into corporate lanes
          tp-disposals (filter #(= :fr-titres-participation (:disposal/asset-class %))
                               disposals)
          brevet-disposals (filter #(= :fr-brevet (:disposal/asset-class %))
                                   disposals)
          opts      {:authority authority :commodity commodity :db db}
          tp-cmp     (titres-participation-component opts ctx tp-disposals)
          brevet-cmp (brevets-component opts ctx brevet-disposals)
          components (->> [tp-cmp brevet-cmp] (remove nil?) vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :fr :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; The PERSONAL provider — mobilière + immobilière + pro CT + pro LT (+ PEA)
;; ============================================================================

(defrecord FRPersonalCapitalGainsTaxProvider
           [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for FR personal CGT provider"
                                        {:ctx-keys (keys ctx)})))
          disposals (ds/disposals-in source entity period)
          ;; Drop unrecognized asset classes
          recognized (filter #(contains? asset-classes (:disposal/asset-class %)) disposals)
          by-lane (group-by
                   (fn [d]
                     (let [ac (:disposal/asset-class d)]
                       (cond
                         (pfu-asset-classes ac)                 :mobilière
                         (immo-asset-classes ac)                :immobilière
                         (= :fr-pro-court-terme ac)             :pro-ct
                         (= :fr-pro-long-terme ac)              :pro-lt
                         :else                                  :other)))
                   recognized)
          opts      {:authority authority :commodity commodity :db db}
          mob-cmp   (mobiliere-component opts ctx (:mobilière by-lane))
          immo-cmp  (immo-component      opts ctx (:immobilière by-lane))
          pct-cmp   (pro-ct-component    opts ctx (:pro-ct by-lane))
          plt-cmp   (pro-lt-component    opts ctx (:pro-lt by-lane))
          components (->> [mob-cmp immo-cmp pct-cmp plt-cmp]
                          (remove nil?) vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :fr :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn fr-corporate-cgt-provider
  "Build the FR corporate CGT provider. Required `:source`.

   Two components in the resulting `TaxReturnFacts`:
   - Titres de participation (Art. 219) → QPFC into `:cit-base-additions`
   - Brevets IP box (Art. 238) → standalone 10 % component"
  [{:keys [source id commodity] :or {id :fr-corp-cgt commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->FRCorporateCapitalGainsTaxProvider
   id source :fr-dgfip commodity
   "CGI Art. 219 (titres de participation) + Art. 238 (IP box)"))

(defn fr-personal-cgt-provider
  "Build the FR personal CGT provider. Required `:source`.

   Up to FOUR components in the resulting `TaxReturnFacts`:
   - Mobilière (Art. 150-0 A) — PFU or barème
   - Immobilière (Art. 150 U) — IR + PS + surtaxe
   - Pro court-terme (Art. 39 duodecies) — folds into PIT
   - Pro long-terme (Art. 39 quindecies) — 12.8 % IR + 17.2 % PS

   Inputs the consumer may supply via ctx:
     :tax-unit {:pfu-or-bareme :pfu | :barème}
     :inputs   {:capital-loss-carryforward {:fr-mv-mobilière <bd>
                                             :fr-mv-pro-long <bd>}
                :151-septies {:activity :services|:goods :revenue <bd>}
                :238-quindecies {:transmission-value <bd>}
                :ip-box {<external-id> {:nexus-ratio <bd>}}}"
  [{:keys [source id commodity] :or {id :fr-pers-cgt commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->FRPersonalCapitalGainsTaxProvider
   id source :fr-dgfip commodity
   "CGI Art. 150-0 A + 150 U + 39 duodecies + 39 quindecies + 200 A"))

(defn install-statute!
  "Install the FR CGT statute (parameters) into `conn`."
  [conn]
  (cgt-statute/install! conn))
