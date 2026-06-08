(ns kontor.l10n-in.cgt-provider
  "Indian capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data.

   ## One provider, multi-component

   Post-FA-2024 the rate structure is the SIMPLEST of any major
   jurisdiction kontor covers (12.5 % flat LTCG, 20 % flat equity
   STCG, slab for everything else) but the rollover-exemption
   catalogue is the RICHEST (§54 / §54B / §54D / §54EC / §54F / §54G
   / §54GA / §54GB plus 30+ §47 transfer-not-regarded keywords).

   The provider classifies each disposal into one of five lanes via
   `:kontor.disposal/asset-class`:

     :in-equity-listed     listed STT-paid equity / equity-MF / BT units
                           — §111A 20 % STCG / §112A 12.5 % LTCG above ₹1.25 L floor
     :in-equity-unlisted   unlisted equity shares
                           — slab STCG / §112 12.5 % LTCG
     :in-immovable         residential / commercial / urban-agri immovable
                           — slab STCG / §112 12.5 % LTCG (CII election
                             for resident-individual pre-23-Jul-2024)
     :in-debt-mf           debt MF / debt securities post-1-Apr-2023
                           — always slab (§50AA)
     :in-other-lta         everything else
                           — slab STCG / §112 12.5 % LTCG

   Holding-period cutoffs are TWO-tier post-FA-2024:
   - 12 months for `:in-equity-listed`
   - 24 months for everything else

   ## TaxReturnFacts component layout

   Up to FIVE components per call:

     :lane                       :ltcg-§112A             ; equity-listed LTCG above ₹1.25 L
     :lane                       :stcg-§111A             ; equity-listed STCG 20 %
     :lane                       :ltcg-§112              ; other LTCG 12.5 % (or 20 % with indexation)
     :lane                       :stcg-slab              ; slab STCG → folds into PIT/CIT
     :lane                       :cess                   ; 4 % cess on standalone CGT

   Plus deferred-via-exemption disposals (`:in-§54` family) emit a
   tracking line item but contribute zero gain.

   ## Composition with the PIT / CIT provider

   Like the US CGT provider, slab-rate STCG is NOT computed standalone
   — the lane fans out the net amount via
   `:jurisdiction-specific-codes :pit-base-additions` (when
   `:kind :individual`) or `:cit-base-additions` (when
   `:kind :corporation`). The consumer threads those into
   `kontor.l10n-in.period-tax-provider` (PIT) or the future IN CIT
   provider on the same period.

   ## §54 family deferral

   When a disposal carries `:exemption-claimed` containing any of
   `:in-§54`, `:in-§54B`, `:in-§54D`, `:in-§54EC`, `:in-§54F`,
   `:in-§54G`, `:in-§54GA`, `:in-§54GB`, or the `:in-§47-*`
   transfer-not-regarded subset, the provider folds the
   `:kontor.disposal/rollover-amount` (or, for §47, the entire gain) out of
   the lane's net base — surfaces a `:rollover-§…` line item, but the
   gain does not enter the lane.

   §54EC enforces the ₹50 L per-FY hard cap by aggregating across
   the period's disposals; the consumer-supplied
   `:inputs :in-§54EC-prior-claimed` lets a mid-year run know what's
   already been used.

   ## Capital-loss carryforward

   Five IN buckets via
   `:inputs :capital-loss-carryforward {:in-stcl-equity
                                        :in-ltcl-equity
                                        :in-stcl-other
                                        :in-ltcl-other
                                        :in-ltcl-pre-2026-onetime}`

   The §70 (within-year) / §74 (carry-forward) wall is enforced:
   STCL offsets STCG or LTCG; LTCL only LTCG (except the
   `:in-ltcl-pre-2026-onetime` one-time relief from IT Bill 2025 that
   may offset STCG from AY 2027-28 onward).

   ## §50C deemed proceeds

   When the consumer supplies
   `:inputs :in-stamp-duty-deemed-proceeds <Money>` for an immovable
   disposal AND the SDV exceeds 110 % of the recorded proceeds
   (post-FA-2020 safe-harbour), the provider deems proceeds = SDV.

   ## §194-IA TDS

   Consumer supplies `:inputs :in-tds-§194-IA <BigDecimal>` — folded
   as a `:prepaid` reduction at the final reconciliation."
  (:require [datahike.api :as d]
            [kontor.provider.disposal-provider :as ds]
            [kontor.l10n-in.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Constants — closed sets
;; ============================================================================

(def lanes
  "The closed set of IN CGT lanes a disposal classifies into."
  #{:ltcg-§112A   ; equity-listed LTCG (above ₹1.25 L floor)
    :stcg-§111A   ; equity-listed STCG (20 %)
    :ltcg-§112    ; other LTCG (12.5 % flat, or 20 % w/ indexation election)
    :stcg-slab    ; slab STCG → folds into PIT/CIT
    :cess         ; 4 % H&E cess (a surtax)
    :exempt})     ; §54 family / §47 (no liability)

(def asset-classes
  "The IN-namespaced :kontor.disposal/asset-class vocabulary the provider
   classifies on. Other unknown values are treated conservatively as
   `:in-other-lta`."
  #{:in-equity-listed :in-equity-unlisted :in-immovable
    :in-debt-mf :in-debt-security :in-other-lta})

(def §54-family-exemptions
  "The §54 family rollover-relief keywords this provider honours
   (rollover-amount subtracted from lane base)."
  #{:in-§54 :in-§54B :in-§54D :in-§54EC :in-§54F
    :in-§54G :in-§54GA :in-§54GB})

(def §47-transfer-not-regarded
  "The §47 transfer-not-regarded keywords this provider honours
   (entire gain treated as zero — the transferee inherits basis +
   holding period under §49). The closed v1 subset; consumers may
   add jurisdiction-specific sub-clauses via the same convention.

   `:in-§10(37)-compulsory-agri` uses the `keyword`-fn form because
   the symbol-syntax keyword literal can't contain parentheses."
  (into #{:in-§47 :in-§47-amalgamation :in-§47-demerger
          :in-§47-gift-relative :in-§47-parent-subsidiary
          :in-§47-llp-conversion}
        [(keyword "in-§10(37)-compulsory-agri")]))

;; ============================================================================
;; Compute-fn registration — 4 % cess
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- in-cgt-cess
  "4 % Health & Education cess on the standalone CGT running tax. The
   provider supplies the running tax via
   `:inputs :standalone-cgt-running-tax`; the provision's condition
   gates this to > 0."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (statute/parameter-value-at db "IN.CGT.cess.rate" as-of)
        base  (or (get-in ctx [:inputs :standalone-cgt-running-tax]) 0M)]
    (* base rate)))

(defn register!
  "Register IN-specific compute-fns with `kontor.tax.statute`. Called at
   namespace load; idempotent."
  []
  (statute/register-compute-fn! :in-cgt-cess in-cgt-cess))

(register!)

;; ============================================================================
;; Holding-period classifier (post-FA-2024 two-tier)
;; ============================================================================

(defn- months-between
  "Whole months between two `java.util.Date` instants (Long). Naive —
   30.44-day month — adequate for the LTCG cutoff classifier
   (matches CBDT's calendar-month convention closely enough for the
   12 / 24 / 36 cliffs that ALL fall on multi-year boundaries)."
  ^long [^java.util.Date a ^java.util.Date b]
  (long (/ (- (.getTime b) (.getTime a))
           (* 1000 60 60 24 30.44))))

(defn- long-term?
  "Two-tier post-FA-2024 cutoff. `:in-equity-listed` uses 12 months;
   all other asset classes use 24 months. `:in-debt-mf` (post-1-Apr-2023
   acquisition) is ALWAYS short regardless of holding (§50AA)."
  [disposal {:keys [equity-cutoff-mo other-cutoff-mo]}]
  (let [acq         (:kontor.disposal/acquired-on disposal)
        dis         (:kontor.disposal/disposed-on disposal)
        asset-class (:kontor.disposal/asset-class disposal)]
    (cond
      (= asset-class :in-debt-mf)        false  ; §50AA — always STCG
      (= asset-class :in-debt-security)  false
      (not (and acq dis))                false
      (= asset-class :in-equity-listed)  (> (months-between acq dis) equity-cutoff-mo)
      :else                              (> (months-between acq dis) other-cutoff-mo))))

;; ============================================================================
;; CII indexation lookup
;; ============================================================================

(defn- fiscal-year-of
  "Indian fiscal year for an instant — FY runs 1-Apr to 31-Mar.
   Returns the keyword `:in-cgt-cii/fy-YYYY-YY` matching the
   parameter-code naming."
  [^java.util.Date d]
  (let [cal     (doto (java.util.GregorianCalendar.) (.setTime d))
        year    (.get cal java.util.Calendar/YEAR)
        month0  (.get cal java.util.Calendar/MONTH) ; 0 = January
        start   (if (>= month0 3) year (dec year))  ; April = month 3
        end-yy  (mod (inc start) 100)]
    (format "IN.CGT.cii.fy-%d-%02d" start end-yy)))

(defn- cii-for
  "CII (BigDecimal) for the FY containing `d`, or nil if not in the
   table. Reads from the parameter table at `as-of` so future CII
   amendments respect bitemporal queries."
  [db ^java.util.Date as-of ^java.util.Date d]
  (statute/parameter-value-at db (fiscal-year-of d) as-of))

(defn- indexed-basis
  "Compute the indexed basis: `basis × (CII-of-disposal-FY ÷
   CII-of-acquisition-FY)`. Rounded HALF-EVEN to whole rupees.
   Returns nil when CII for either FY is missing (provider falls back
   to no-indexation). The intermediate ratio is computed with an
   explicit `MathContext` to avoid the non-terminating-decimal
   exception that `(/ a b)` raises for ratios like 376/167."
  ^java.math.BigDecimal [db ^java.util.Date as-of disposal]
  (let [acq          (:kontor.disposal/acquired-on disposal)
        dis          (:kontor.disposal/disposed-on disposal)
        basis        (or (:kontor.disposal/basis-amount disposal) 0M)
        cii-acq      (cii-for db as-of acq)
        cii-dis      (cii-for db as-of dis)]
    (when (and cii-acq cii-dis (pos? cii-acq))
      (let [ratio (.divide ^java.math.BigDecimal cii-dis
                           ^java.math.BigDecimal cii-acq
                           java.math.MathContext/DECIMAL64)]
        (.setScale ^java.math.BigDecimal (.multiply ^java.math.BigDecimal basis ratio)
                   0
                   java.math.RoundingMode/HALF_EVEN)))))

;; ============================================================================
;; Classification — disposal → lane + provisional net amount
;; ============================================================================

(defn- effective-proceeds
  "Resolve `:kontor.disposal/proceeds-amount`, applying §50C deemed-proceeds
   for immovable property when the consumer supplied
   `:inputs :in-stamp-duty-deemed-proceeds` and the SDV exceeds the
   safe-harbour ratio (default 110 % post-FA-2020) of recorded
   proceeds."
  ^java.math.BigDecimal [disposal {:keys [in-stamp-duty-deemed-proceeds
                                          §50C-safe-harbour-ratio]}]
  (let [recorded (or (:kontor.disposal/proceeds-amount disposal) 0M)
        sdv      (some-> in-stamp-duty-deemed-proceeds bigdec)
        ratio    (or §50C-safe-harbour-ratio 1.10M)]
    (cond
      (not= (:kontor.disposal/asset-class disposal) :in-immovable) recorded
      (nil? sdv) recorded
      ;; §50C bites when SDV > ratio × recorded (the safe-harbour);
      ;; effective proceeds become the SDV.
      (> sdv (* ratio recorded)) sdv
      :else recorded)))

(defn- realized-gain
  "Provisional gain on a disposal, BEFORE rollover-exemption deduction.
   Uses indexed basis when the consumer elected the §112 proviso
   (CII indexation) for a pre-23-Jul-2024 immovable disposal."
  ^java.math.BigDecimal [db ^java.util.Date as-of disposal cii-elected? §50C-opts]
  (let [proceeds (effective-proceeds disposal §50C-opts)
        basis    (if cii-elected?
                   (or (indexed-basis db as-of disposal)
                       (or (:kontor.disposal/basis-amount disposal) 0M))
                   (or (:kontor.disposal/basis-amount disposal) 0M))]
    (- proceeds basis)))

(defn- §47-exempt?
  "True iff the disposal carries any §47 keyword — gain is zero
   (transferee inherits basis under §49)."
  [disposal]
  (boolean (some §47-transfer-not-regarded
                 (:kontor.disposal/exemption-claimed disposal))))

(defn- §54-family-rollover-amount
  "If the disposal claims any §54 family exemption AND the consumer
   recorded `:kontor.disposal/rollover-amount`, return a map
   `{:total <BigDecimal> :§54EC-used <BigDecimal>}` where `:total` is
   the total amount deferred (capped by §54EC ₹50 L if §54EC was the
   sole claim) and `:§54EC-used` is the §54EC-attributable slice that
   counts against the FY cap.

   Allocation rule when a disposal claims BOTH `:in-§54EC` and another
   `:in-§54x`: the §54EC cap is consumed FIRST (up to the head of the
   FY cap), with any remainder allocated to the sibling exemption.
   This matches the natural intent — the consumer's rollover total
   spans both bond purchases (§54EC) and the qualifying replacement
   asset (sibling §54x), and the bond portion is what counts against
   the ₹50 L cap.

   When the cap is exhausted (head ≤ 0) and the disposal also claims a
   non-§54EC sibling, the ENTIRE rollover routes through the sibling
   and `:§54EC-used` is 0M — preserving cap headroom for later
   disposals in the same FY.

   Returns `{:total 0M :§54EC-used 0M}` when no §54-family exemption
   is claimed.

   (Fixes the P0 in research.1 — the old shape returned
   only the total, and the caller had to guess which portion was
   §54EC-attributable, which it did wrong by adding the FULL rollover
   to the FY accumulator on every mixed claim.)"
  [disposal {:keys [§54EC-cap §54EC-prior-claimed §54EC-running-claimed]}]
  (let [claimed   (set (:kontor.disposal/exemption-claimed disposal))
        rollover  (or (:kontor.disposal/rollover-amount disposal) 0M)
        §54EC?    (contains? claimed :in-§54EC)
        non-§54EC (some §54-family-exemptions (disj claimed :in-§54EC))]
    (cond
      ;; Hard ₹50 L cap on §54EC — sum prior+running across FY.
      §54EC?
      (let [already   (+ (or §54EC-prior-claimed 0M) (or §54EC-running-claimed 0M))
            head      (max 0M (- §54EC-cap already))
            allowed   (min rollover head)
            remainder (max 0M (- rollover allowed))]
        {:total      (+ allowed
                        ;; If the disposal ALSO claims a non-§54EC
                        ;; exemption, the remainder still rolls under
                        ;; that exemption's own rules (consumer-sized).
                        (if non-§54EC remainder 0M))
         :§54EC-used allowed})

      non-§54EC {:total rollover :§54EC-used 0M}
      :else     {:total 0M       :§54EC-used 0M})))

(defn- classify
  "Classify one disposal into IN CGT lanes, returning a map
   `{:lane <kw> :gain <bigdec> :rollover <bigdec> :election <kw|nil>
    :disposal <map>}`.

   `:gain` is the net taxable amount AFTER rollover-exemption
   subtraction; `:rollover` is the amount deferred (surfaces as a
   line item)."
  [db ^java.util.Date as-of disposal opts]
  (let [{:keys [equity-cutoff-mo other-cutoff-mo §54EC-cap
                §54EC-prior-claimed §54EC-running-claimed
                §50C-opts]} opts
        asset-class   (:kontor.disposal/asset-class disposal)
        elective      (set (:kontor.disposal/elective-regime disposal))
        cii-elected?  (contains? elective :in-cii-indexation)
        gain-pre      (realized-gain db as-of disposal cii-elected? §50C-opts)
        long?         (long-term? disposal {:equity-cutoff-mo equity-cutoff-mo
                                             :other-cutoff-mo other-cutoff-mo})
        §47?          (§47-exempt? disposal)
        {rollover    :total
         §54EC-used  :§54EC-used} (§54-family-rollover-amount
                                   disposal {:§54EC-cap            §54EC-cap
                                             :§54EC-prior-claimed  §54EC-prior-claimed
                                             :§54EC-running-claimed §54EC-running-claimed})
        gain-net      (max 0M (- (max 0M gain-pre) rollover))
        ;; Lane assignment by (asset-class, long?).
        lane          (cond
                        §47?
                        :exempt

                        (and long? (= asset-class :in-equity-listed))
                        :ltcg-§112A

                        (and (not long?) (= asset-class :in-equity-listed))
                        :stcg-§111A

                        ;; debt-mf / debt-security: ALWAYS slab (§50AA)
                        (or (= asset-class :in-debt-mf)
                            (= asset-class :in-debt-security))
                        :stcg-slab

                        long?
                        :ltcg-§112

                        :else
                        :stcg-slab)]
    {:lane       lane
     :gain       (if §47? 0M gain-net)
     :rollover   rollover
     :§54EC-used §54EC-used
     :election   (when cii-elected? :in-cii-indexation)
     :gain-pre   gain-pre
     :disposal   disposal}))

;; ============================================================================
;; Within-year set-off + carry-in (§70 / §74)
;; ============================================================================

(defn- sum-gain
  ^java.math.BigDecimal [classified lane]
  (reduce + 0M (map :gain (filter #(= lane (:lane %)) classified))))

(defn- sum-rollover
  ^java.math.BigDecimal [classified lane]
  (reduce + 0M (map :rollover (filter #(= lane (:lane %)) classified))))

(defn- net-against
  "Subtract a carry-in BigDecimal from a lane gain (floor at zero).
   Returns `{:net :consumed :remaining-carry}`."
  [^java.math.BigDecimal lane-gain ^java.math.BigDecimal carry-in]
  (let [carry    (or carry-in 0M)
        consumed (min carry lane-gain)]
    {:net             (max 0M (- lane-gain carry))
     :consumed        consumed
     :remaining-carry (max 0M (- carry lane-gain))}))

;; ============================================================================
;; Components
;; ============================================================================

(defn- ltcg-§112A-component
  "Listed-equity LTCG above ₹1.25 L floor at 12.5 %."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-gain]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (statute/parameter-value-at db "IN.CGT.§112A.rate" as-of)
        floor     (statute/parameter-value-at db "IN.CGT.§112A.floor" as-of)
        above-floor (max 0M (- net-gain floor))
        gross     (* above-floor rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :ltcg-§112A-gross
                        :label "Listed-equity LTCG (gross)"
                        :value (money/money net-gain commodity)}
                       {:line :ltcg-§112A-floor
                        :label (str "§112A floor (₹" floor " p.a.)")
                        :value (money/money (min floor net-gain) commodity)}
                       {:line :ltcg-§112A-taxable
                        :label "Taxable above floor"
                        :value (money/money above-floor commodity)}
                       {:line :ltcg-§112A-tax
                        :label (str "Tax at " (* rate 100M) "%")
                        :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :ltcg-§112A}}))

(defn- stcg-§111A-component
  "Listed-equity STCG at 20 % (post-FA-2024)."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-gain]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (statute/parameter-value-at db "IN.CGT.§111A.rate" as-of)
        gross (* net-gain rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :stcg-§111A-base
                        :label "Listed-equity STCG (net)"
                        :value (money/money net-gain commodity)}
                       {:line :stcg-§111A-tax
                        :label (str "Tax at " (* rate 100M) "%")
                        :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :stcg-§111A}}))

(defn- ltcg-§112-component
  "Other-asset-class LTCG at 12.5 % (post-FA-2024 flat) — OR 20 %
   with-indexation when the per-disposal CII election was made. The
   provider applies whichever ELECTION the disposal carries on
   `:elective-regime`; lower-tax pick is the consumer's responsibility
   (the substrate doesn't auto-elect: the consumer either records
   no election → 12.5 % flat, OR records `:in-cii-indexation` → 20 %
   on the indexed gain)."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-gain
   election]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        indexed? (= election :in-cii-indexation)
        rate     (statute/parameter-value-at
                  db (if indexed?
                       "IN.CGT.§112.rate-with-indexation"
                       "IN.CGT.§112.rate")
                  as-of)
        gross    (* net-gain rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :regime          election
     :line-items      [{:line :ltcg-§112-base
                        :label (str "LTCG net" (when indexed? " (indexed basis)"))
                        :value (money/money net-gain commodity)}
                       {:line :ltcg-§112-tax
                        :label (str "Tax at " (* rate 100M) "%")
                        :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :ltcg-§112
                                   :election election}}))

(defn- stcg-slab-component
  "Slab-rate STCG / debt-MF base — folds into PIT (individual) or
   CIT (corporation) via `:pit-base-additions` / `:cit-base-additions`."
  [{:keys [commodity authority]} ^java.math.BigDecimal net-gain kind]
  (let [code (if (= kind :individual) :pit-base-additions :cit-base-additions)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :stcg-slab-base
                        :label "Slab-rate STCG (folds into PIT/CIT)"
                        :value (money/money net-gain commodity)}]
     :jurisdiction-specific-codes {code [net-gain]
                                   :lane :stcg-slab}}))

(defn- cess-component
  "4 % H&E cess on standalone CGT (LTCG + equity STCG). Driven through
   `kontor.tax.statute/apply-provisions` so the cess line carries
   provision provenance."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal cgt-running-tax]
  (let [db         (:db ctx)
        as-of      (as-of-from-ctx ctx)
        ctx-w-base (-> ctx
                       (assoc :db db)
                       (assoc-in [:inputs :standalone-cgt-running-tax]
                                 cgt-running-tax))
        {:keys [tax-items]} (statute/apply-provisions
                             db
                             {:concept :surtax :jurisdiction :in :as-of as-of}
                             ctx-w-base)
        {liability :liability resolved :resolved}
        (ts/apply-adjustments cgt-running-tax tax-items ctx-w-base)
        cess-amount (- liability cgt-running-tax)]
    (when (pos? cess-amount)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/zero commodity)
       :schedule        nil
       :gross-liability (money/money cess-amount commodity)
       :liability       (money/money cess-amount commodity)
       :prepaid         (money/zero commodity)
       :line-items      (mapv (fn [r] {:line  (:code r)
                                       :label (:label r)
                                       :value (money/money (:amount r) commodity)})
                              resolved)
       :composed-of     [:ltcg-§112A :stcg-§111A :ltcg-§112]
       :jurisdiction-specific-codes {:lane :cess}})))

(defn- exempt-component
  "Track the §54-family / §47 exemption rollover totals as a zero-
   liability component — surfaces the deferred gain for audit."
  [{:keys [commodity authority]}
   {:keys [§47-count §54-family-rollover §54EC-cap-used]}]
  (when (or (pos? §47-count) (pos? §54-family-rollover))
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money §54-family-rollover commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (cond-> []
                        (pos? §47-count)
                        (conj {:line  :§47-transfers
                               :label (str "§47 transfers-not-regarded (" §47-count " disposal(s))")
                               :value (money/zero commodity)})
                        (pos? §54-family-rollover)
                        (conj {:line  :§54-family-rollover
                               :label "§54 / §54B / §54D / §54EC / §54F / §54G family rollover (deferred)"
                               :value (money/money §54-family-rollover commodity)})
                        (pos? §54EC-cap-used)
                        (conj {:line  :§54EC-cap-used
                               :label "§54EC ₹50 L cap used (FY-aggregate)"
                               :value (money/money §54EC-cap-used commodity)}))
     :jurisdiction-specific-codes {:lane :exempt}}))

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord INCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for IN CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          equity-cut  (long (statute/parameter-value-at
                             db "IN.CGT.holding-period-cutoff-listed-equity-months"
                             as-of))
          other-cut   (long (statute/parameter-value-at
                             db "IN.CGT.holding-period-cutoff-other-months"
                             as-of))
          §54EC-cap   (statute/parameter-value-at db "IN.CGT.§54EC.cap-per-fy" as-of)
          §50C-ratio  (statute/parameter-value-at db "IN.CGT.§50C.safe-harbour-ratio" as-of)
          §50C-opts   {:in-stamp-duty-deemed-proceeds
                       (get-in inputs [:in-stamp-duty-deemed-proceeds])
                       :§50C-safe-harbour-ratio §50C-ratio}
          §54EC-prior (or (:in-§54EC-prior-claimed inputs) 0M)
          ;; Classify each disposal, threading the running §54EC FY
          ;; total so successive disposals share the cap.
          disposals   (ds/disposals-facts source {:entity entity :period period})
          {classified :classified
           §54EC-used :§54EC-used}
          (reduce
           (fn [{:keys [classified §54EC-used]} d]
             (let [c (classify db as-of d
                               {:equity-cutoff-mo equity-cut
                                :other-cutoff-mo  other-cut
                                :§54EC-cap        §54EC-cap
                                :§54EC-prior-claimed §54EC-prior
                                :§54EC-running-claimed §54EC-used
                                :§50C-opts §50C-opts})
                   ;; Only the §54EC-attributable slice consumes the
                   ;; ₹50 L FY cap — NOT the entire rollover when a
                   ;; sibling §54x exemption also claims part of it.
                   ;; (Fixes the P0 in research.1.)
                   §54EC-add (or (:§54EC-used c) 0M)]
               {:classified (conj classified c)
                :§54EC-used (+ §54EC-used §54EC-add)}))
           {:classified [] :§54EC-used 0M}
           disposals)
          carry-in    (or (:capital-loss-carryforward inputs) {})
          §47-count   (count (filter #(= :exempt (:lane %)) classified))
          §54-roll    (reduce + 0M (map :rollover classified))
          opts        {:authority authority :commodity commodity}

          ;; --- Net each lane against its compartment's carry-in ---
          ;;
          ;; IN compartment walls (§70 / §74):
          ;;   STCL-equity → STCG-equity, then LTCG-equity (§70(2))
          ;;   STCL-other  → STCG-other,  then LTCG-other  (§70(2))
          ;;   LTCL-equity → LTCG-equity ONLY (§70(3))
          ;;   LTCL-other  → LTCG-other  ONLY (§70(3))
          ;;
          ;; IT Bill 2025 one-time relief: `:in-ltcl-pre-2026-onetime`
          ;; may set off against ANY STCG bucket from AY 2027-28+ (gated
          ;; by `:as-of ≥ 2026-04-01`).
          stcg-eq     (sum-gain classified :stcg-§111A)
          ltcg-eq     (sum-gain classified :ltcg-§112A)
          ltcg-other  (sum-gain classified :ltcg-§112)
          stcg-slab   (sum-gain classified :stcg-slab)

          ;; Step 1: LTCL → LTCG (same compartment)
          {ltcg-eq-net :net}
          (net-against ltcg-eq (or (:in-ltcl-equity carry-in) 0M))
          {ltcg-other-net :net}
          (net-against ltcg-other (or (:in-ltcl-other carry-in) 0M))

          ;; Step 2: STCL → STCG, then spill to LTCG (same compartment, §70(2))
          {stcg-eq-net :net stcg-eq-rem :remaining-carry}
          (net-against stcg-eq (or (:in-stcl-equity carry-in) 0M))
          {ltcg-eq-net-2 :net}
          (net-against ltcg-eq-net stcg-eq-rem)

          {stcg-slab-net :net slab-rem :remaining-carry}
          (net-against stcg-slab (or (:in-stcl-other carry-in) 0M))
          {ltcg-other-net-2 :net}
          (net-against ltcg-other-net slab-rem)

          ;; Step 3: IT Bill 2025 one-time pre-2026 LTCL → any STCG
          ay-2027-on? (>= (compare as-of #inst "2026-04-01") 0)
          onetime     (when ay-2027-on?
                        (or (:in-ltcl-pre-2026-onetime carry-in) 0M))

          {stcg-eq-net-2 :net onetime-rem-1 :remaining-carry}
          (if onetime
            (net-against stcg-eq-net onetime)
            {:net stcg-eq-net :remaining-carry 0M})

          {stcg-slab-net-2 :net}
          (if onetime
            (net-against stcg-slab-net onetime-rem-1)
            {:net stcg-slab-net :remaining-carry 0M})

          ;; --- Build components ---
          ltcg-§112A (when (pos? ltcg-eq-net-2)
                       (ltcg-§112A-component opts ctx ltcg-eq-net-2))
          stcg-§111A (when (pos? stcg-eq-net-2)
                       (stcg-§111A-component opts ctx stcg-eq-net-2))

          ;; §112 lane — group by election: lower-tax-elect is the
          ;; consumer's responsibility per disposal. Aggregate flat-rate
          ;; (non-indexed) into one component; aggregate indexed
          ;; into a separate component (different :regime + rate). The
          ;; lane shares the post-carry total `ltcg-other-net-2` —
          ;; flat-rate gets the carry-reduced amount, indexed bucket
          ;; passes through (consumer-elected indexation runs on the
          ;; per-disposal indexed gain that classify already computed).
          §112-flat-pre   (->> classified
                               (filter #(and (= :ltcg-§112 (:lane %))
                                             (nil? (:election %))))
                               (map :gain) (reduce + 0M))
          §112-indexed (->> classified
                            (filter #(and (= :ltcg-§112 (:lane %))
                                          (= :in-cii-indexation (:election %))))
                            (map :gain) (reduce + 0M))
          ;; Distribute the post-carry net-of-§112 reduction across the
          ;; two buckets proportionally: the consumed carry only ever
          ;; offsets the flat-rate bucket in v1 (the indexed lane is
          ;; rarer and the consumer can rebalance via the §54 family).
          §112-flat (if (pos? §112-flat-pre)
                      (min §112-flat-pre ltcg-other-net-2)
                      0M)
          §112-flat-cmp    (when (pos? §112-flat)
                             (ltcg-§112-component opts ctx §112-flat nil))
          §112-indexed-cmp (when (pos? §112-indexed)
                             (ltcg-§112-component opts ctx §112-indexed :in-cii-indexation))

          stcg-slab-cmp (when (pos? stcg-slab-net-2)
                          (stcg-slab-component opts stcg-slab-net-2 kind))

          ;; --- Cess on standalone tax (LTCG + equity STCG) ---
          standalone-tax (+ (or (some-> ltcg-§112A :gross-liability :amount) 0M)
                            (or (some-> stcg-§111A :gross-liability :amount) 0M)
                            (or (some-> §112-flat-cmp :gross-liability :amount) 0M)
                            (or (some-> §112-indexed-cmp :gross-liability :amount) 0M))
          cess-cmp       (when (pos? standalone-tax)
                           (cess-component opts ctx standalone-tax))

          ;; --- Exempt tracking component ---
          exempt-cmp     (exempt-component
                          opts {:§47-count §47-count
                                :§54-family-rollover §54-roll
                                :§54EC-cap-used §54EC-used})

          ;; --- §194-IA TDS prepayment ---
          tds-§194-IA    (or (get-in inputs [:in-tds-§194-IA]) 0M)
          components-raw [ltcg-§112A stcg-§111A §112-flat-cmp §112-indexed-cmp
                          stcg-slab-cmp cess-cmp exempt-cmp]
          ;; Apply TDS as prepaid on the §112 (immovable) lane if present, else cess
          components
          (->> components-raw
               (remove nil?)
               ((fn [cs]
                  (if (and (pos? tds-§194-IA) (seq cs))
                    (let [target-idx (or (some (fn [[i c]]
                                                 (when (= :ltcg-§112
                                                          (get-in c [:jurisdiction-specific-codes :lane]))
                                                   i))
                                               (map-indexed vector cs))
                                         0)]
                      (update (vec cs) target-idx
                              update :prepaid
                              (fn [_] (money/money tds-§194-IA commodity))))
                    cs)))
               vec)]

      (when-not (#{:individual :corporation} kind)
        (throw (ex-info "IN CGT provider :kind must be :individual or :corporation"
                        {:kind kind})))
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :in :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn in-cgt-provider
  "Build an IN CGT provider.
   Required: `:source` — a `DisposalProvider` (kernel protocol).
             `:kind`   — `:individual` | `:corporation`."
  [{:keys [source id kind]}]
  (when-not source (throw (ex-info ":source DisposalProvider required" {})))
  (when-not (#{:individual :corporation} kind)
    (throw (ex-info ":kind must be :individual or :corporation" {:kind kind})))
  (->INCapitalGainsTaxProvider
   (or id (keyword (str "in-cgt-" (name kind))))
   source :in-cbdt :INR
   "Income-tax Act 1961 §111A / §112 / §112A / §50AA / §54 family"
   kind))

