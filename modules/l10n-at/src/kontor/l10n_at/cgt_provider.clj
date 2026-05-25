(ns kontor.l10n-at.cgt-provider
  "AT capital-gains tax providers — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 134.

   ## THREE providers (FOUR overlapping regimes)

   Per note 134 §5, AT CGT splits across THREE provider records:

   - **`at-kest-cgt-provider`** (kind `:individual-kest`) — §27/§27a
     EStG KESt-Endbesteuerung. Flat 27.5 % on financial assets
     (shares / bonds / funds / derivatives); 25 % on bank-deposit
     interest (NB: interest is NOT a disposal event — provider does
     not see it). Verlustverrechnungstopf within the year; NO
     carryforward (Jan 1 reset — note 134 §6.3). Bank-withheld via
     `:inputs :at-kest-prepaid`. Endbesteuerungswirkung makes the
     return informational unless Regelbesteuerung is elected.

   - **`at-immoest-provider`** (kind `:individual-immoest`) —
     §30/§30a EStG. Flat 30 % on Neuvermögen real-estate gains;
     pauschale 4.2 % (unwidmet) / 18 % (gewidmet) of GROSS PROCEEDS
     for Altvermögen. Hauptwohnsitzbefreiung short-circuits to zero;
     Herstellerbefreiung exempts building only (consumer splits via
     `:disposal/basis-amount` representing land only + a notes field).
     §30 Abs 7 loss carry: 60 % of net loss residual × 15 years
     against §28-Vermietung income (note 134 §6.2 — first cross-
     category CGT loss in the kontor substrate; provider writes to
     `:pit-base-deductions {:§28-vermietung [...]}`).
     **Umwidmungszuschlag** (§30 Abs 6a EStG, BBG 2025, effective
     2025-07-01): the consumer flags eligibility via
     `:elective-regime :at-umwidmungszuschlag` on a Neuvermögen-style
     Bauland disposal; the basis supplied on the disposal MUST
     represent the LAND-only portion (consistent with Herstellerbefreiung
     convention) so the gain the surcharge fires on IS the land-slice
     gain. The provider then emits a separate surcharge component at
     30 % of the positive land-slice gain (note 146 §3.1; cap
     `Bemessungsgrundlage = min(1.30 × Gewinn ; Erlös)` enforced).

   - **`at-corporate-cgt-provider`** (kind `:corporation`) — §10 KStG.
     **INVERSION** of the usual exemption-flag direction (note 134
     §6.1): default is TAX-NEUTRAL for qualifying Schachtelbeteiligung
     (>10 % + 1 year + foreign corp); the consumer opts INTO taxable
     via `:elective-regime :at-§10-tax-effective-option`. When the
     option is in force, gains enter the CIT base at 23 % (2024+)
     and losses spread over 7 years via Siebentelregelung
     (`:inputs :at-§10-loss-siebentel`).
     **Foreign-corp guard** (§10 Abs 2 KStG, note 146 §3.2): §10
     applies to foreign corps only. The consumer attests via
     `:tax-unit :held-entity-domestic? <bool>`; when true, the §10
     default-exempt INVERSION does NOT fire — the gain stays in the
     CIT base as ordinary income (consistent with §10 Abs 1 Z 1 KStG
     which exempts dividends but NOT gains on domestic stakes).
     §9 KStG Gruppenbesteuerung is OUT OF SCOPE for v1.

   ## Lane classification (`:disposal/asset-class`)

     :at-kest-aktien            → KESt 27.5 % (shares)
     :at-kest-anleihen          → KESt 27.5 % (bonds)
     :at-kest-fonds             → KESt 27.5 % (funds)
     :at-kest-derivate          → KESt 27.5 % (derivatives)
     :at-immoest-neu            → ImmoESt 30 % (Neuvermögen, post-2002)
     :at-immoest-alt            → 4.2 % / 18 % per `:elective-regime`
                                  (`:at-immoest-alt-unwidmet` /
                                   `:at-immoest-alt-gewidmet`)
     :at-immoest-residence      → 0 % if Hauptwohnsitz flag in
                                  `:exemption-claimed` set
                                  (`:at-hauptwohnsitz-2of2` or
                                   `:at-hauptwohnsitz-5of10`);
                                  otherwise treated as
                                  :at-immoest-neu
     :at-§10-participation      → §10 KStG (default exempt; opt-in via
                                  `:elective-regime
                                   :at-§10-tax-effective-option`)

   Disposals with an unrecognised `:asset-class` for the given holder
   kind are silently dropped (forward-compat with new asset classes).

   ## Loss carryforward shape

   `:inputs :capital-loss-carryforward` is a map with TWO AT-specific
   buckets:
     :at-kest       — DELIBERATELY UNUSED (resets Jan 1; note 134 §6.3)
     :at-immoest    — only the §30 Abs 7 mechanism applies; tracked
                      per loss-year via the Siebentel-shaped
                      `:inputs :at-immoest-loss-carryforward
                      {year-keyword amount}` shape (note 134 §6.2).

   ## DisposalSource

   The provider depends on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`); a consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants
;; ============================================================================

(def kest-asset-classes
  "Closed set of `:disposal/asset-class` keywords the KESt provider
   recognises. All four route to the 27.5 % flat schedule (interest on
   bank deposits is not a disposal event — see note 134 §3 row 2)."
  #{:at-kest-aktien :at-kest-anleihen :at-kest-fonds :at-kest-derivate})

(def immoest-asset-classes
  "Closed set of `:disposal/asset-class` keywords the ImmoESt provider
   recognises."
  #{:at-immoest-neu :at-immoest-alt :at-immoest-residence})

(def corporate-asset-classes
  "Closed set of `:disposal/asset-class` keywords the corporate §10
   provider recognises."
  #{:at-§10-participation})

(def ^:private hauptwohnsitz-flags
  "The two `:exemption-claimed` flags that trigger
   Hauptwohnsitzbefreiung (note 134 §1.4 — alternative tests, either
   one suffices)."
  #{:at-hauptwohnsitz-2of2 :at-hauptwohnsitz-5of10})

(def ^:private hersteller-flag
  ":exemption-claimed flag that triggers Herstellerbefreiung
   (note 134 §1.5 — building only, land remains taxable)."
  :at-herstellerbefreiung)

(def ^:private §10-option-flag
  ":elective-regime flag that opts INTO the §10 KStG tax-effective
   regime (note 134 §6.1 — default-exempt INVERSION)."
  :at-§10-tax-effective-option)

(def ^:private immoest-alt-unwidmet-flag
  ":elective-regime flag selecting the 4.2 % pauschale (Altvermögen,
   unwidmet — pre-2002 acquisition, no land-use rezoning)."
  :at-immoest-alt-unwidmet)

(def ^:private immoest-alt-gewidmet-flag
  ":elective-regime flag selecting the 18 % pauschale (Altvermögen,
   gewidmet — pre-2002 acquisition, land rezoned to building land
   post-1987-12-31)."
  :at-immoest-alt-gewidmet)

(def ^:private umwidmungszuschlag-flag
  ":elective-regime flag opting INTO the §30 Abs 6a Umwidmungszuschlag
   (BBG 2025, effective 2025-07-01). The consumer attests that (a) the
   disposal involves land previously zoned non-building (e.g.
   agricultural/forest) that was rezoned to Bauland after 2024-12-31,
   (b) the disposal occurred after 2025-06-30, and (c) the
   `:disposal/basis-amount` represents the LAND-only portion (building
   share documented in `:notes` per Herstellerbefreiung convention).
   Provider emits a separate 30 %-of-positive-land-gain surcharge
   component (cap: Bemessungsgrundlage ≤ proceeds). Note 146 §3.1."
  :at-umwidmungszuschlag)

;; ============================================================================
;; Helpers — date math, ctx, gain, regime/exemption set reading
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
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- proceeds
  "Proceeds (gross sales price) on one disposal — used by the
   Altvermögen pauschale path (4.2 % / 18 % of GROSS, not of gain)."
  ^java.math.BigDecimal [disposal]
  (or (:disposal/proceeds-amount disposal) 0M))

(defn- regime-set
  "Normalize `:disposal/elective-regime` (cardinality-many) to a set.
   Pull may return a vector OR a single keyword."
  [disposal]
  (let [r (:disposal/elective-regime disposal)]
    (cond
      (nil? r)         #{}
      (coll? r)        (set r)
      (keyword? r)     #{r}
      :else            #{})))

(defn- exemption-set
  "Normalize `:disposal/exemption-claimed` (cardinality-many) to a set."
  [disposal]
  (let [r (:disposal/exemption-claimed disposal)]
    (cond
      (nil? r)         #{}
      (coll? r)        (set r)
      (keyword? r)     #{r}
      :else            #{})))

(defn- hauptwohnsitz-claimed?
  "True iff the disposal carries either Hauptwohnsitz exemption flag
   (note 134 §1.4 — 2-of-2 OR 5-of-10 either suffices)."
  [disposal]
  (boolean (some (exemption-set disposal) hauptwohnsitz-flags)))

(defn- hersteller-claimed?
  "True iff the disposal carries the Herstellerbefreiung flag."
  [disposal]
  (contains? (exemption-set disposal) hersteller-flag))

(defn- §10-option-elected?
  "True iff the disposal opts into the §10 KStG tax-effective regime.
   Note 134 §6.1 — INVERSION: this signals the OPT-OUT of the default
   exemption."
  [disposal]
  (contains? (regime-set disposal) §10-option-flag))

(defn- umwidmungszuschlag-claimed?
  "True iff the disposal flags the §30 Abs 6a EStG Umwidmungszuschlag
   regime (BBG 2025; note 146 §3.1)."
  [disposal]
  (contains? (regime-set disposal) umwidmungszuschlag-flag))

(defn- §10-qualifying?
  "True iff the disposal qualifies as a §10 KStG internationale
   Schachtelbeteiligung: foreign corp (note 146 §3.2) AND ≥ 10 %
   ownership AND ≥ 1-year holding (note 134 §1.7).

   Foreign-corp gate (note 146 §3.2 P0-2): §10 Abs 2 KStG applies to
   FOREIGN corporations only. §10 Abs 1 Z 1 KStG exempts dividends
   from domestic stakes but does NOT exempt gains. The consumer
   attests via `:tax-unit :held-entity-domestic? <bool>`; when true,
   this predicate returns false so the default-exempt INVERSION does
   NOT fire and the gain stays in the CIT base as ordinary income."
  [disposal qualifying-fraction qualifying-days ctx]
  (let [own (or (:disposal/ownership-fraction disposal) 0M)
        acq (:disposal/acquired-on disposal)
        dis (:disposal/disposed-on disposal)
        days (when (and acq dis) (days-between acq dis))
        domestic? (boolean (get-in ctx [:tax-unit :held-entity-domestic?]))]
    (and (not domestic?)
         (>= (compare own qualifying-fraction) 0)
         days
         (>= days (long qualifying-days)))))

(defn- sum-amounts
  ^java.math.BigDecimal [amounts]
  (reduce + 0M amounts))

;; ============================================================================
;; KESt provider — §27/§27a EStG
;; ============================================================================

(defn- kest-component
  "One :capital-gains-tax component for the KESt 27.5 % bucket.
   Verlustverrechnungstopf within the year — gains and losses sum
   freely; NO carryforward (Jan 1 reset, §27 Abs 8 EStG).
   Endbesteuerungswirkung: when the consumer reports the bank-withheld
   amount via `:inputs :at-kest-prepaid`, the `:liability` becomes the
   delta (zero in the common case)."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal kest-net
   ^java.math.BigDecimal rate
   ^java.math.BigDecimal prepaid
   regelbesteuerung?]
  (let [taxable (max 0M kest-net)
        schedule (ts/flat rate)
        gross   (ts/apply-schedule schedule taxable)
        liab    (- gross prepaid)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money liab commodity)
     :prepaid         (money/money prepaid commodity)
     :regime          (if regelbesteuerung? :regelbesteuerung :endbesteuerung)
     :line-items
     (cond-> [{:line :at-kest-net
               :label "§27 EStG — KESt-Vermögen net gain (year, Verlustverrechnungstopf)"
               :value (money/money kest-net commodity)}
              {:line :at-kest-tax
               :label "§27a Abs 1 Z 2 EStG — KESt at 27.5 % (gross)"
               :value (money/money gross commodity)}
              {:line :at-kest-bank-withheld
               :label "§95 EStG — bank-withheld KESt (prepaid via inputs)"
               :value (money/money prepaid commodity)}]
       regelbesteuerung?
       (conj {:line :at-regelbesteuerung
              :label "§27a Abs 5 EStG — Regelbesteuerungsoption elected"
              :value (money/money kest-net commodity)}))
     :jurisdiction-specific-codes
     (cond-> {:lane :at-kest}
       regelbesteuerung? (assoc :pit-base-additions [(max 0M kest-net)]))}))

;; ============================================================================
;; ImmoESt provider — §30/§30a EStG
;; ============================================================================

(defn- residence-component
  "Hauptwohnsitzbefreiung short-circuit — emit a zero-tax component
   for audit (note 134 §2.2). The component records the flag that
   triggered the exemption."
  [{:keys [commodity authority]} disposal]
  (let [claimed (some (exemption-set disposal) hauptwohnsitz-flags)
        gain    (realized-gain disposal)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/zero commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :hauptwohnsitzbefreiung
     :line-items      [{:line :at-immoest-gross-gain
                        :label "§30 EStG — gross gain on private real-estate disposal"
                        :value (money/money gain commodity)}
                       {:line :at-immoest-hauptwohnsitz
                        :label (str "§30 Abs 2 Z 1 EStG — Hauptwohnsitzbefreiung (" claimed ")")
                        :value (money/money gain commodity)}
                       {:line :at-immoest-taxable
                        :label "§30 EStG — taxable amount after Hauptwohnsitzbefreiung"
                        :value (money/zero commodity)}]
     :jurisdiction-specific-codes {:lane :at-immoest-residence
                                   :exemption claimed}}))

(defn- neuvermoegen-component
  "Standard Neuvermögen ImmoESt component — 30 % on (proceeds − basis).
   Used both for `:at-immoest-neu` and for `:at-immoest-residence`
   when the Hauptwohnsitz flag is absent (note 134 §1.4 — exemption
   must be explicitly claimed). When `:at-herstellerbefreiung` is in
   the exemption set, the consumer must have set
   `:disposal/basis-amount` to represent the LAND-only portion (note
   134 §4.2 — building share exempt; the deviation is documented in
   `:notes`)."
  [{:keys [commodity authority]} ctx disposal rate prepaid]
  (let [gain    (realized-gain disposal)
        taxable (max 0M gain)
        schedule (ts/flat rate)
        gross   (ts/apply-schedule schedule taxable)
        liab    (- gross prepaid)
        hersteller? (hersteller-claimed? disposal)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money liab commodity)
     :prepaid         (money/money prepaid commodity)
     :regime          (cond
                        hersteller? :herstellerbefreiung
                        :else       :neuvermoegen)
     :line-items
     (cond-> [{:line :at-immoest-gross-gain
               :label "§30 EStG — gross gain (proceeds − basis)"
               :value (money/money gain commodity)}
              {:line :at-immoest-tax
               :label "§30a Abs 1 EStG — ImmoESt at 30 % (Neuvermögen)"
               :value (money/money gross commodity)}]
       hersteller?
       (conj {:line :at-immoest-hersteller
              :label "§30 Abs 2 Z 2 EStG — Herstellerbefreiung (building exempt; basis is land only)"
              :value (money/money taxable commodity)})
       (pos? prepaid)
       (conj {:line :at-immoest-prepaid
              :label "§30b EStG — Selbstberechnung (notary-withheld ImmoESt)"
              :value (money/money prepaid commodity)}))
     :jurisdiction-specific-codes {:lane :at-immoest-neu}}))

(defn- altvermoegen-component
  "Altvermögen pauschale component — 4.2 % (unwidmet) or 18 %
   (gewidmet) of GROSS PROCEEDS (note 134 §1.3). The deemed gain is
   14 % / 60 % of proceeds, taxed at 30 %; the parameter table stores
   the EFFECTIVE rate (4.2 % / 18 %) directly so the math is one
   multiply.

   The regime is selected by an `:elective-regime` flag on the
   disposal: `:at-immoest-alt-unwidmet` (default if neither flag set)
   vs `:at-immoest-alt-gewidmet`."
  [{:keys [commodity authority]} ctx disposal unwidmet-rate gewidmet-rate prepaid]
  (let [regime    (regime-set disposal)
        gewidmet? (contains? regime immoest-alt-gewidmet-flag)
        effective-rate (if gewidmet? gewidmet-rate unwidmet-rate)
        gross-proceeds (proceeds disposal)
        gross-tax (* gross-proceeds effective-rate)
        liab    (- gross-tax prepaid)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money gross-proceeds commodity)
     :schedule        (ts/flat effective-rate)
     :gross-liability (money/money gross-tax commodity)
     :liability       (money/money liab commodity)
     :prepaid         (money/money prepaid commodity)
     :regime          (if gewidmet?
                        :altvermoegen-gewidmet
                        :altvermoegen-unwidmet)
     :line-items      [{:line :at-immoest-gross-proceeds
                        :label "§30 Abs 4 EStG — gross proceeds (Altvermögen pauschale base)"
                        :value (money/money gross-proceeds commodity)}
                       {:line :at-immoest-effective-rate
                        :label (if gewidmet?
                                 "§30 Abs 4 Z 1 EStG — 18 % effective rate (umgewidmet, post-1987)"
                                 "§30 Abs 4 Z 2 EStG — 4.2 % effective rate (unwidmet)")
                        :value (money/money gross-tax commodity)}]
     :jurisdiction-specific-codes {:lane :at-immoest-alt}}))

(defn- umwidmungszuschlag-component
  "§30 Abs 6a EStG Umwidmungszuschlag (BBG 2025) — 30 % surcharge on
   the LAND-slice positive gain when previously non-Bauland was rezoned
   to Bauland after 2024-12-31 and disposed after 2025-06-30.

   Bemessungsgrundlage = min(1.30 × Gewinn ; Erlös) per statute — the
   *enhanced* base cannot exceed gross proceeds. The provider expresses
   this as a separate component on the DELTA between the enhanced base
   and the plain gain, taxed at the ImmoESt rate. Equivalent math:
   surcharge = (capped-enhanced-base − gain) × ImmoESt-rate.

   When the simpler form (no cap binding) applies:
     surcharge = gain × 0.30 × ImmoESt-rate
   When the cap binds (1.30 × gain > proceeds):
     surcharge = (proceeds − gain) × ImmoESt-rate.

   Consumer attests via `:elective-regime :at-umwidmungszuschlag` and
   supplies the LAND-slice basis as `:disposal/basis-amount` so the
   gain IS the land slice (per Herstellerbefreiung convention)."
  [{:keys [commodity authority]} disposal
   ^java.math.BigDecimal surcharge-rate
   ^java.math.BigDecimal immoest-rate]
  (let [gain          (realized-gain disposal)
        gross-proceeds (proceeds disposal)
        positive-gain (max 0M gain)
        ;; surcharge add-on to the base: min(0.30 × Gewinn ; Erlös − Gewinn).
        ;; The total enhanced base is min(1.30 × Gewinn ; Erlös);
        ;; the DELTA over the plain gain is the surcharge base.
        plain-delta   (* surcharge-rate positive-gain)
        cap-delta     (- gross-proceeds positive-gain)
        surcharge-base (max 0M (min plain-delta cap-delta))
        surcharge-tax (* surcharge-base immoest-rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money surcharge-base commodity)
     :schedule        (ts/flat immoest-rate)
     :gross-liability (money/money surcharge-tax commodity)
     :liability       (money/money surcharge-tax commodity)
     :prepaid         (money/zero commodity)
     :regime          :umwidmungszuschlag
     :line-items      [{:line :at-umwidmungszuschlag-land-gain
                        :label "§30 Abs 6a EStG — LAND-slice positive gain (consumer-supplied as basis split)"
                        :value (money/money positive-gain commodity)}
                       {:line :at-umwidmungszuschlag-surcharge-base
                        :label "§30 Abs 6a EStG — Umwidmungszuschlag base = min(0.30 × Gewinn ; Erlös − Gewinn)"
                        :value (money/money surcharge-base commodity)}
                       {:line :at-umwidmungszuschlag-tax
                        :label "§30 Abs 6a EStG — Umwidmungszuschlag at 30 % ImmoESt on surcharge base"
                        :value (money/money surcharge-tax commodity)}]
     :jurisdiction-specific-codes {:lane :at-umwidmungszuschlag}}))

(defn- §30-loss-carry-component
  "§30 Abs 7 EStG loss-distribution component (note 134 §6.2 — the
   first cross-category CGT loss in the kontor substrate).

   When the period's ImmoESt disposals net to a LOSS, the residual is
   reduced to 60 % and emitted as a PIT base deduction against §28
   Vermietung income. The carry is to be distributed over 15 years by
   the consumer; this component records the first year's slice (1/15
   of the 60 %-reduced loss) for the current period. Subsequent years
   ride through `:inputs :at-immoest-loss-carryforward {year amount}`.

   Substrate convention (note 134 §6.2): the destination is
   income-category-specific —
   `:pit-base-deductions {:§28-vermietung [<amount>]}` rather than the
   generic `:pit-base-deductions [<amount>]`."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal raw-loss-amount
   ^java.math.BigDecimal carry-factor
   ^java.math.BigDecimal carry-years]
  (let [reduced (* raw-loss-amount carry-factor)
        yearly  (.divide reduced carry-years 6 java.math.RoundingMode/HALF_EVEN)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/zero commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :§30-abs-7-loss-carry
     :line-items      [{:line :at-immoest-loss-raw
                        :label "§30 Abs 7 EStG — raw ImmoESt loss this year"
                        :value (money/money raw-loss-amount commodity)}
                       {:line :at-immoest-loss-reduced
                        :label "§30 Abs 7 EStG — 60 % residual after factor"
                        :value (money/money reduced commodity)}
                       {:line :at-immoest-loss-yearly
                        :label "§30 Abs 7 EStG — 15-year yearly slice (against §28 Vermietung)"
                        :value (money/money yearly commodity)}]
     :jurisdiction-specific-codes
     {:lane :at-immoest-loss-carry
      :pit-base-deductions {:§28-vermietung [yearly]}}}))

;; ============================================================================
;; Corporate §10 KStG provider
;; ============================================================================

(defn- §10-exempt-component
  "§10 KStG Schachtelbeteiligung exemption — default-exempt branch
   (note 134 §6.1 — INVERSION). The gain that landed in the GL as
   ordinary income is REMOVED from the CIT base via
   `:cit-base-deductions`. The CIT provider composes downstream."
  [{:keys [commodity authority]} disposal ^java.math.BigDecimal gain]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money gain commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :regime          :§10-default-exempt
   :line-items      [{:line :at-§10-gross-gain
                      :label "§10 Abs 2 KStG — Schachtelbeteiligung gross gain"
                      :value (money/money gain commodity)}
                     {:line :at-§10-exempt
                      :label "§10 Abs 3 KStG — DEFAULT tax-neutral (no Option exercised)"
                      :value (money/money gain commodity)}
                     {:line :at-§10-cit-base-deduct
                      :label "§10 Abs 3 KStG — gain removed from CIT base (via :cit-base-deductions)"
                      :value (money/money gain commodity)}]
   :jurisdiction-specific-codes {:cit-base-deductions [gain]
                                 :lane :at-§10-exempt}})

(defn- §10-option-taxable-component
  "§10 KStG with Option zur Steuerwirksamkeit elected — gain enters
   CIT base at the standard rate (note 134 §2.4). No exemption; the
   gain is added to `:cit-base-additions` so the downstream CIT
   provider applies the CIT rate (23 % from 2024)."
  [{:keys [commodity authority]} disposal ^java.math.BigDecimal gain cit-rate]
  ;; Gain is positive — loss path goes through §10-option-loss-component.
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money gain commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :regime          :§10-tax-effective-option
   :line-items      [{:line :at-§10-gross-gain
                      :label "§10 Abs 2 KStG — Schachtelbeteiligung gross gain"
                      :value (money/money gain commodity)}
                     {:line :at-§10-option-elected
                      :label "§10 Abs 3 KStG — Option zur Steuerwirksamkeit ELECTED — gain taxable"
                      :value (money/money gain commodity)}
                     {:line :at-§10-cit-base-add
                      :label (str "§10 KStG — gain into CIT base (CIT rate "
                                  (* cit-rate 100M) " %)")
                      :value (money/money gain commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [gain]
                                 :lane :at-§10-option-taxable}})

(defn- §10-option-loss-component
  "§10 KStG with Option AND a LOSS — §12 Abs 3 Z 2 KStG
   Siebentelregelung: the loss is spread over 7 years (note 134 §2.4).
   Provider emits 1/7 of the loss as `:cit-base-deductions` for the
   current year; the remaining 6/7 ride through
   `:inputs :at-§10-loss-siebentel` for subsequent years."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal loss-amount siebentel-years]
  (let [;; loss-amount is positive (we negated the negative gain)
        yearly (.divide loss-amount siebentel-years
                        6 java.math.RoundingMode/HALF_EVEN)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money loss-amount commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :§10-siebentel
     :line-items      [{:line :at-§10-loss-raw
                        :label "§10 KStG — Schachtel loss (Option elected)"
                        :value (money/money loss-amount commodity)}
                       {:line :at-§10-siebentel-yearly
                        :label "§12 Abs 3 Z 2 KStG — 1/7 Siebentelregelung yearly slice"
                        :value (money/money yearly commodity)}]
     :jurisdiction-specific-codes {:cit-base-deductions [yearly]
                                   :lane :at-§10-siebentel-loss}}))

;; ============================================================================
;; The providers
;; ============================================================================

(defrecord ATKestCgtProvider
           [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AT KESt CGT provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          disposals (ds/disposals-in source entity period)
          ;; Filter to KESt-eligible asset classes; sum gains+losses
          ;; within the year (Verlustverrechnungstopf; NO carry-in —
          ;; note 134 §6.3).
          relevant  (filter #(contains? kest-asset-classes
                                        (:disposal/asset-class %))
                            disposals)
          kest-net  (sum-amounts (map realized-gain relevant))
          rate      (statute/parameter-value-at
                     db "AT.EStG.§27a.kest-financial-rate" as-of)
          prepaid   (or (:at-kest-prepaid inputs) 0M)
          regel?    (boolean (get-in ctx [:tax-unit :regelbesteuerung-elected?]))
          components (if (seq relevant)
                       [(kest-component {:authority authority :commodity commodity}
                                        ctx kest-net rate prepaid regel?)]
                       [])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :at :authority authority}
        :functional-commodity commodity
        :components           components}))))

(defrecord ATImmoEstProvider
           [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AT ImmoESt provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          disposals (ds/disposals-in source entity period)
          relevant  (filter #(contains? immoest-asset-classes
                                        (:disposal/asset-class %))
                            disposals)
          rate-30   (statute/parameter-value-at
                     db "AT.EStG.§30a.immoest-rate" as-of)
          rate-unwidmet (statute/parameter-value-at
                         db "AT.EStG.§30.altvermoegen-unwidmet-effective-rate" as-of)
          rate-gewidmet (statute/parameter-value-at
                         db "AT.EStG.§30.altvermoegen-gewidmet-effective-rate" as-of)
          carry-factor  (statute/parameter-value-at
                         db "AT.EStG.§30-Abs-7.loss-carry-factor" as-of)
          carry-years   (statute/parameter-value-at
                         db "AT.EStG.§30-Abs-7.loss-carry-years" as-of)
          umwidmung-rate (statute/parameter-value-at
                          db "AT.EStG.§30-Abs-6a.umwidmungszuschlag-rate" as-of)
          prepaid       (or (:at-immoest-prepaid inputs) 0M)
          opts          {:authority authority :commodity commodity}
          per-disposal-components
          (->> relevant
               (mapcat
                (fn [d]
                  (let [ac (:disposal/asset-class d)
                        base-cmp
                        (cond
                           ;; Hauptwohnsitzbefreiung short-circuit
                           ;; (note 134 §1.4)
                          (and (or (= ac :at-immoest-residence)
                                   (= ac :at-immoest-neu))
                               (hauptwohnsitz-claimed? d))
                          (residence-component opts d)

                           ;; Altvermögen pauschale (4.2 % / 18 %)
                          (= ac :at-immoest-alt)
                          (altvermoegen-component
                           opts ctx d rate-unwidmet rate-gewidmet prepaid)

                           ;; Neuvermögen 30 % (default for
                           ;; :at-immoest-neu and :at-immoest-residence
                           ;; when Hauptwohnsitz NOT claimed)
                          (or (= ac :at-immoest-neu)
                              (= ac :at-immoest-residence))
                          (neuvermoegen-component opts ctx d rate-30 prepaid)

                          :else nil)
                        ;; Umwidmungszuschlag rides on Neuvermögen-style
                        ;; Bauland disposals (note 146 §3.1). If the
                        ;; consumer flagged it but the surcharge
                        ;; parameter isn't effective at :as-of (pre-
                        ;; 2025-07-01), raise an informative ex-info
                        ;; rather than silently undercharging.
                        umwidmung-cmp
                        (when (umwidmungszuschlag-claimed? d)
                          (cond
                            (nil? umwidmung-rate)
                            (throw (ex-info
                                    "AT §30 Abs 6a Umwidmungszuschlag flagged but no rate effective at :as-of (BBG 2025 effective-from 2025-07-01)"
                                    {:disposal/external-id (:disposal/external-id d)
                                     :as-of as-of}))

                            (not (or (= ac :at-immoest-neu)
                                     (= ac :at-immoest-residence)))
                            (throw (ex-info
                                    "AT §30 Abs 6a Umwidmungszuschlag only applies to Neuvermögen-style Bauland disposals (:at-immoest-neu / :at-immoest-residence); reclassify the disposal or remove :at-umwidmungszuschlag from :elective-regime"
                                    {:disposal/external-id (:disposal/external-id d)
                                     :asset-class ac}))

                            (and (or (= ac :at-immoest-residence)
                                     (= ac :at-immoest-neu))
                                 (hauptwohnsitz-claimed? d))
                            ;; Hauptwohnsitzbefreiung overrides — gain
                            ;; was already exempted; surcharge has
                            ;; nothing to ride on. Silent skip.
                            nil

                            :else
                            (umwidmungszuschlag-component
                             opts d umwidmung-rate rate-30)))]
                    (cond-> []
                      base-cmp     (conj base-cmp)
                      umwidmung-cmp (conj umwidmung-cmp)))))
               (remove nil?)
               vec)
          ;; §30 Abs 7 loss carry: when net (Neuvermögen-only) realised
          ;; gain across the year is negative, emit a loss-carry
          ;; component. Altvermögen pauschale has no losses (the base
          ;; is always positive proceeds); residence is exempted.
          neuvermoegen-net
          (sum-amounts (map realized-gain
                            (filter #(and (or (= (:disposal/asset-class %) :at-immoest-neu)
                                              (= (:disposal/asset-class %) :at-immoest-residence))
                                          (not (hauptwohnsitz-claimed? %)))
                                    relevant)))
          loss-carry-component
          (when (neg? neuvermoegen-net)
            (§30-loss-carry-component opts ctx
                                      (- 0M neuvermoegen-net)
                                      carry-factor
                                      carry-years))
          components (cond-> per-disposal-components
                       loss-carry-component (conj loss-carry-component))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :at :authority authority}
        :functional-commodity commodity
        :components           (vec components)}))))

(defrecord ATCorporateCgtProvider
           [id source authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AT corporate CGT provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          disposals (ds/disposals-in source entity period)
          qualifying-fraction (statute/parameter-value-at
                               db "AT.KStG.§10.qualifying-ownership-fraction" as-of)
          qualifying-days     (statute/parameter-value-at
                               db "AT.KStG.§10.qualifying-holding-days" as-of)
          siebentel-years     (statute/parameter-value-at
                               db "AT.KStG.§12-Abs-3-Z-2.siebentel-years" as-of)
          cit-rate            (statute/parameter-value-at
                               db "AT.KStG.cit-rate" as-of)
          relevant  (filter #(contains? corporate-asset-classes
                                        (:disposal/asset-class %))
                            disposals)
          opts {:authority authority :commodity commodity}
          per-disposal-components
          (->> relevant
               (mapv (fn [d]
                       (let [g (realized-gain d)
                             option? (§10-option-elected? d)
                             qualifying? (§10-qualifying? d qualifying-fraction qualifying-days ctx)]
                         (cond
                           ;; Option elected + LOSS → Siebentelregelung
                           ;; (note 134 §2.4)
                           (and option? (neg? g))
                           (§10-option-loss-component opts ctx (- 0M g) siebentel-years)

                           ;; Option elected + gain → fully taxable
                           ;; into CIT base
                           (and option? (pos? g))
                           (§10-option-taxable-component opts d g cit-rate)

                           ;; Option NOT elected + qualifying
                           ;; Schachtelbeteiligung + gain → DEFAULT
                           ;; EXEMPT (note 134 §6.1 — INVERSION).
                           ;; Gain on the books is removed from CIT
                           ;; base via :cit-base-deductions.
                           (and (not option?) qualifying? (pos? g))
                           (§10-exempt-component opts d g)

                           ;; Option NOT elected + qualifying + LOSS
                           ;; → also tax-neutral by default (symmetric).
                           ;; The GL booked the loss as ordinary
                           ;; expense, pulling CIT base DOWN by |g|.
                           ;; To neutralize, ADD the positive magnitude
                           ;; back to the CIT base. Note 146 §5 P0-3:
                           ;; emitting `[g]` (signed-negative) would
                           ;; DOUBLE the loss effect, not neutralize
                           ;; it. The correct value is `(- 0M g)`
                           ;; (positive).
                           (and (not option?) qualifying? (neg? g))
                           (let [pos-mag (- 0M g)]
                             {:kind            :capital-gains-tax
                              :authority       authority
                              :base            (money/money pos-mag commodity)
                              :schedule        nil
                              :gross-liability (money/zero commodity)
                              :liability       (money/zero commodity)
                              :prepaid         (money/zero commodity)
                              :regime          :§10-default-exempt-loss
                              :line-items      [{:line :at-§10-loss-raw
                                                 :label "§10 Abs 3 KStG — Schachtel loss (DEFAULT tax-neutral, no Option)"
                                                 :value (money/money g commodity)}
                                                {:line :at-§10-cit-base-add
                                                 :label "§10 Abs 3 KStG — loss neutralized by adding |loss| back to CIT base"
                                                 :value (money/money pos-mag commodity)}]
                              :jurisdiction-specific-codes
                              {:cit-base-additions [pos-mag]
                               :lane :at-§10-exempt-loss}})

                           ;; Otherwise (non-qualifying participation
                           ;; OR zero gain): no component. The gain
                           ;; lands in CIT base via ordinary GL posting;
                           ;; the CGT provider has no view to add.
                           :else nil))))
               (remove nil?)
               vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :at :authority authority}
        :functional-commodity commodity
        :components           per-disposal-components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn at-kest-cgt-provider
  "Build an AT KESt CGT provider — §27/§27a EStG KESt-Endbesteuerung
   on financial-asset disposals for individuals.

   Required: `:source` — a `DisposalSource` (kernel protocol).
   Optional:
     :id        — provider id (default :at-cgt-kest)
     :commodity — functional commodity (default :EUR)

   Note 134 §6.3: KESt losses do NOT carry forward —
   Verlustverrechnungstopf resets Jan 1; provider does not read
   `:inputs :capital-loss-carryforward :at-kest`."
  [{:keys [source id commodity]
    :or   {id :at-cgt-kest commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->ATKestCgtProvider id source :at-finanzamt commodity
                       "EStG §27 + §27a + §97 (KESt-Endbesteuerung)"))

(defn at-immoest-provider
  "Build an AT ImmoESt provider — §30/§30a EStG on real-estate
   disposals for individuals.

   Required: `:source` — a `DisposalSource`.
   Optional:
     :id        — provider id (default :at-cgt-immoest)
     :commodity — functional commodity (default :EUR)

   Note 134 §6.2: §30 Abs 7 loss carry writes to
   `:pit-base-deductions {:§28-vermietung [...]}` — the first
   cross-category CGT loss in the kontor substrate. The destination
   convention is AT-specific."
  [{:keys [source id commodity]
    :or   {id :at-cgt-immoest commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->ATImmoEstProvider id source :at-finanzamt commodity
                       "EStG §30 + §30a + §30b (ImmoESt)"))

(defn at-corporate-cgt-provider
  "Build an AT corporate CGT provider — §10 KStG Schachtelbeteiligung.

   Required: `:source` — a `DisposalSource`.
   Optional:
     :id        — provider id (default :at-cgt-corporate)
     :commodity — functional commodity (default :EUR)

   Note 134 §6.1 INVERSION: the DEFAULT for qualifying
   Schachtelbeteiligungen is TAX-NEUTRAL; the consumer opts INTO
   taxable via `:elective-regime :at-§10-tax-effective-option`. §9
   KStG Gruppenbesteuerung is out of scope for v1."
  [{:keys [source id commodity]
    :or   {id :at-cgt-corporate commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->ATCorporateCgtProvider id source :at-finanzamt commodity
                            "KStG §10 + §12 Abs 3 Z 2 (Schachtelbeteiligung + Siebentelregelung)"))

