(ns kontor.l10n-au.pit-provider
  "AU personal income tax provider — individual income tax + Medicare
   Levy + LITO — built as a `PeriodTaxProvider` (ADR-099) over the
   statute-as-data substrate (ADR-101). Mirrors
   `kontor.l10n-at.pit-provider` structurally (single-
   component fold + `:progressive-bracket` from `parameter-brackets-at`)
   with two compute-fns — Medicare Levy (low-income shade-in) and
   LITO (3-band shade).

   The provider does THREE things and nothing else:

   1. Reads the ITRA 1986 Sch 7 `:effective-from`-keyed bracket scale
      for `:as-of` from `:parameter-bracket` data
      (`AU.PIT.brackets`).
   2. For the single `:au-pit` component sets `:component :au-pit`
      in ctx, calls `kontor.tax.statute/apply-provisions` for each
      relevant concept, folds base-side adjustments + applies the
      bracket schedule + folds tax-side surtax (Medicare Levy) and
      credits (LITO, franking, FITO, TFN-prepaid). Stamps the post-
      adjustment `:base` into the scoped ctx so the late-bound
      compute-fns (Medicare + LITO) can read the right taxable income.
   3. Assembles a 1-component `TaxReturnFacts`.

   ## Inputs the consumer supplies

   `:tax-unit` (filing-unit / household config — all optional with
   defaults):
     {}   no tax-unit flags drive v1 provisions (residency / filing
          status / dependants are deferred3); the
          tax-unit map MAY be empty.

   `:inputs` (period facts — `:gross-income` is the only required key):
     {:gross-income                       <BigDecimal>  required —
                                                        taxable income
                                                        (ITAA 1997 §4-15)
                                                        after consumer-
                                                        side deductions
                                                        for §16
                                                        Werbungskosten /
                                                        §18 etc.
      :au-cgt-pit-base-additions          <BigDecimal>  optional — post-
                                                        Div-115-discount
                                                        post-Subdiv-152
                                                        net capital gain
                                                        from cgt-provider
      :au-investment-pit-base-additions   <BigDecimal>  optional — franking
                                                        gross-up + unfranked
                                                        + foreign + interest
                                                        from investment-income-
                                                        provider
      :au-franking-credit-pit-credit      <BigDecimal>  optional — refundable
                                                        for :individual /
                                                        :super-fund /
                                                        :fixed-trust
      :au-fito-pit-credit                 <BigDecimal>  optional — FITO
                                                        per §770-10 (non-
                                                        refundable)
      :au-tfn-prepaid-pit-credit          <BigDecimal>  optional — TFN-
                                                        withheld prepaid
                                                        (refundable)}

   ## Compute-fns

   - `:au-medicare-levy` — returns a late-bound fn (consumes
     `:base` from `ctx-w-running` at fold time) that computes
     `min(rate × TI, shade-rate × max(0, TI − threshold))`. Reads
     `AU.PIT.medicare-levy-rate`, `AU.PIT.medicare-shade-rate`, and
     `AU.PIT.medicare-low-income-threshold` parameters via
     `parameter-value-at` for `:as-of`.
   - `:au-lito` — returns a late-bound fn that computes the 3-band
     LITO shade reading 6 parameters via `parameter-value-at`.

   Both compute-fns rely on the provider stamping `:base` into the
   scoped ctx BEFORE calling `apply-adjustments` so the late-bound
   fns see the post-base-adjustment taxable income (not the raw
   `:inputs :gross-income`).

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked as a follow-up."
  (:require [kontor.l10n-au.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint for symmetry with FR / DE / AT templates.
(comment pit-statute/install!)

;; ============================================================================
;; Compute-fn registration — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as fallback)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- au-medicare-levy
  "Medicare Levy — `min(rate × TI, shade-rate × max(0, TI − low-income-
   threshold))`. Returns a late-bound `(fn [ctx-w-running] ...)` so the
   levy is computed off the post-base-adjustment `:base` (taxable
   income) the provider stamps into the scoped ctx, not the raw
   `:inputs :gross-income`. Reads rate / shade-rate / threshold from
   `:parameter` via `parameter-value-at` for `:as-of`."
  [ctx]
  (let [db         (:db ctx)
        as-of      (as-of-from-ctx ctx)
        rate       (or (statute/parameter-value-at db "AU.PIT.medicare-levy-rate" as-of) 0M)
        shade-rate (or (statute/parameter-value-at db "AU.PIT.medicare-shade-rate" as-of) 0M)
        threshold  (or (statute/parameter-value-at db "AU.PIT.medicare-low-income-threshold" as-of) 0M)]
    (fn [ctx-w-running]
      (let [ti (or (some-> (:base ctx-w-running) bigdec) 0M)]
        (min (* rate ti)
             (* shade-rate (max 0M (- ti threshold))))))))

(defn- au-lito
  "Low Income Tax Offset — ITAA 1997 §159N 3-band shade:

     TI ≤ $37 500              → $700
     $37 500 < TI ≤ $45 000    → $700 − 0.05 × (TI − $37 500)
     $45 000 < TI ≤ $66 667    → $325 − 0.015 × (TI − $45 000)
     TI > $66 667              → 0

   All six thresholds / rates read from `:parameter` via
   `parameter-value-at`. Returns a late-bound `(fn [ctx-w-running] ...)`
   so LITO is computed off the post-base-adjustment `:base`."
  [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        lito-max    (or (statute/parameter-value-at db "AU.PIT.lito-max" as-of) 0M)
        flat-upper  (or (statute/parameter-value-at db "AU.PIT.lito-flat-upper" as-of) 0M)
        mid-rate    (or (statute/parameter-value-at db "AU.PIT.lito-mid-rate" as-of) 0M)
        mid-upper   (or (statute/parameter-value-at db "AU.PIT.lito-mid-upper" as-of) 0M)
        mid-amount  (or (statute/parameter-value-at db "AU.PIT.lito-mid-amount" as-of) 0M)
        upper-rate  (or (statute/parameter-value-at db "AU.PIT.lito-upper-rate" as-of) 0M)
        upper-cap   (or (statute/parameter-value-at db "AU.PIT.lito-upper-cap" as-of) 0M)]
    (fn [ctx-w-running]
      (let [ti (or (some-> (:base ctx-w-running) bigdec) 0M)]
        (cond
          (<= (compare ti flat-upper) 0)  lito-max
          (<= (compare ti mid-upper) 0)   (- lito-max (* mid-rate (- ti flat-upper)))
          (<= (compare ti upper-cap) 0)   (- mid-amount (* upper-rate (- ti mid-upper)))
          :else                           0M)))))

(defn register!
  "Register the two AU PIT compute-fns with `kontor.tax.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :au-medicare-levy au-medicare-levy)
  (statute/register-compute-fn! :au-lito          au-lito))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds the component
;; ============================================================================

(defn- component-items
  "For the `:au-pit` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them. Returns
   `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :au-pit :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :au
                                                   :as-of        as-of}
                                               scoped-ctx))
        adds       (query :base-transform-add)
        deducts    (query :base-transform-deduct)
        surtaxes   (query :surtax)
        refundable-credits     (query :refundable-credit)
        non-refundable-credits (query :non-refundable-credit)]
    {:base-items (vec (concat (:base-items adds) (:base-items deducts)))
     ;; Tax-side fold order matters: surtaxes (Medicare) BEFORE credits
     ;; (LITO + franking + FITO + TFN-prepaid) so the levy is added to
     ;; the gross before any credit reduces it. Within credits the
     ;; non-refundables fire BEFORE refundables so the refundables can
     ;; still drive the liability negative without being lost to a
     ;; clamped non-refundable.
     :tax-items  (vec (concat (:tax-items surtaxes)
                              (:tax-items non-refundable-credits)
                              (:tax-items refundable-credits)))
     :provisions (concat (:provisions adds)
                         (:provisions deducts)
                         (:provisions surtaxes)
                         (:provisions non-refundable-credits)
                         (:provisions refundable-credits))}))

(defn- au-pit-component
  "Build the AU PIT component map. Base = gross-income + base-
   adjustments (CGT + investment-income lanes); schedule = ITRA 1986
   Sch 7 progressive bracket scale from `parameter-brackets-at` for
   `:as-of`; tax-side = Medicare Levy surtax + LITO non-refundable
   credit + franking refundable + FITO non-refundable + TFN-prepaid
   refundable via `apply-adjustments`. The provider stamps `:base`
   into the scoped ctx BEFORE the tax-side fold so the late-bound
   compute-fns (Medicare + LITO) see post-adjustment taxable income."
  [db ctx as-of gross-income functional-commodity]
  (let [brackets (or (statute/parameter-brackets-at db "AU.PIT.brackets" as-of)
                     (throw (ex-info "AU PIT provider: no AU.PIT.brackets bracket-set in effect at as-of (install kontor.l10n-au.pit-statute first)"
                                     {:as-of as-of})))
        schedule {:kontor.schedule/type :progressive-bracket :brackets brackets}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :au-pit :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   gross-income base-items scoped-ctx)
        ;; Stamp the post-base-adjustment taxable income into ctx so the
        ;; late-bound Medicare + LITO compute-fns (returned by their
        ;; resolvers earlier) read the right TI at fold time.
        scoped-ctx (assoc scoped-ctx :base base')
        raw-gross  (ts/apply-schedule schedule base' scoped-ctx)
        ;; PIT is not refundable on a loss — floor gross at 0M.
        ;; Refundable credits (franking, TFN-prepaid) can still drive
        ;; liability below zero.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :personal-income-tax
     :authority       :au-ato
     :base            {:amount base' :commodity functional-commodity}
     :base-transform  (when (seq base-resolved)
                        {:transform/type :adjustments
                         :items          base-resolved})
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            (filter #(= :credit (:op %)) tax-resolved))
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                            (filter #(= :surtax (:op %)) tax-resolved))
     :liability       {:amount liability :commodity functional-commodity}
     :regime          nil
     :provenance      {:provider-id        :au-pit
                       :statute            "Income Tax Assessment Act 1997 + Income Tax Rates Act 1986 Sch 7 + Medicare Levy Act 1986"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord AuPitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          gross-income (or (:gross-income inputs)
                           (throw (ex-info "AU PIT provider needs :inputs :gross-income"
                                           {:inputs inputs})))
          au-pit-c     (au-pit-component db ctx as-of gross-income commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :au :authority :au-ato}
         :functional-commodity commodity
         :components           [au-pit-c]})))))

(defn au-pit-provider
  "Build an AU PIT provider. Statute lives in `:provision` /
   `:parameter` / `:parameter-bracket` data (installed via
   `kontor.l10n-au.pit-statute/install!`); the provider just folds
   the applicable provisions for the `:au-pit` component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :au-pit)
     :commodity — functional commodity (default :AUD)"
  [{:keys [id commodity] :or {id :au-pit commodity :AUD}}]
  (->AuPitProvider id commodity))
