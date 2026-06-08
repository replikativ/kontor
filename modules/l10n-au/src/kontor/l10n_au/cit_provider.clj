(ns kontor.l10n-au.cit-provider
  "AU corporate income tax provider — Australian company tax — built
   as a `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` (the closest single-component CIT
   template); the BRE rate swap rides `:op :schedule-override` (same
   pattern as FR PME).

   The provider does THREE things and nothing else:

   1. Reads the ITRA 1986 §23 standard rate from `:parameter` data
      (`AU.InvIncome.corporate-rate.large`, shipped from
      `kontor.l10n-au.investment-income-statute`) and uses it as the
      default flat schedule.
   2. For the single `:au-cit` component sets `:component :au-cit` in
      ctx, calls `kontor.tax.statute/apply-provisions` to gather any
      `:schedule-override` (the BRE swap), `:base-add` provisions
      (CGT + investment-income lanes), and `:credit` provisions
      (franking + FITO). Folds via the substrate.
   3. Assembles a 1-component `TaxReturnFacts`.

   Everything else — the BRE eligibility test, the per-event franking-
   credit fate, the FITO computation — lives in the consumer or in
   already-shipped sibling providers; this CIT provider just consumes
   their lanes via `:tax-context-fact`.

   ## Inputs the consumer supplies

   `:tax-unit` (company config — all optional with defaults):
     {:base-rate-entity?  #{true false}  default false — the consumer-
                                         adjudicated BRE eligibility flag
                                         (aggregated turnover < $50M +
                                         ≤ 80 % BREPI per ATO LCR 2019/5)}

   `:inputs` (period facts — `:book-profit` is the only required key):
     {:book-profit                       <BigDecimal>  required —
                                                       taxable income
                                                       (ITAA 1997 §4-15;
                                                       the consumer
                                                       maintains the
                                                       tax-vs-book delta
                                                       outside the
                                                       substrate)
      :au-cgt-cit-base-additions         <BigDecimal>  optional — the
                                                       cgt-provider's
                                                       :cit-base-additions
                                                       lane (post-cascade
                                                       net gain; no Div
                                                       115 discount for
                                                       companies)
      :au-investment-cit-base-additions  <BigDecimal>  optional — the
                                                       investment-income-
                                                       provider's
                                                       :cit-base-additions
                                                       lane (franking
                                                       gross-up + unfranked
                                                       + foreign + interest)
      :au-franking-credit-cit-credit     <BigDecimal>  optional — the
                                                       franking credit
                                                       (non-refundable
                                                       for :company)
      :au-fito-cit-credit                <BigDecimal>  optional — FITO
                                                       per §770-10
                                                       (non-refundable)}

   ## Out-of-substrate

   - **Tax Consolidation Regime (ITAA 1997 Pt 3-90)** — deferred to
     `kontor-group-consolidation` companion ().
   - **R&D Tax Incentive (§355)** — consumer pre-computes via
     `:inputs :credits` (v1).
   - **Loss Carry-Back Tax Offset (LCBTO)** — sunset; deferred.
   - **Carry-forward losses (§36-15)** — consumer pre-computes the
     permitted offset (inter-period carry).

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked as a follow-up."
  (:require [kontor.l10n-au.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — the namespace is required for its
;; install-time side effects (parameter / provision defs read elsewhere
;; and to keep the symmetry with the FR / DE / JP / CA / AT templates).
(comment cit-statute/install!)

;; ============================================================================
;; Compute-fn registration — no compute-fns needed for AU CIT
;; ============================================================================
;;
;; The AU CIT substrate has zero compute-fns: every amount comes from
;; `:tax-context-fact` (the consumer-supplied lanes) and the BRE rate
;; comes from `:parameter` via `:rate-from :parameter`. We keep a
;; `(register!)` no-op for symmetry with FR / DE / JP / CA / AT.

(defn register!
  "No-op for AU CIT — kept for symmetry with the other ADR-101 CIT
   providers that DO register compute-fns. Idempotent."
  [])

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds the component
;; ============================================================================

(defn- component-items
  "For the `:au-cit` component, query the statute for all applicable
   base-side + schedule-override + tax-side provisions and resolve
   them. Returns `{:base-items :tax-items :schedule-overrides :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :au-cit :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :au
                                                   :as-of        as-of}
                                               scoped-ctx))
        regime     (query :elective-regime)
        adds       (query :base-transform-add)
        deducts    (query :base-transform-deduct)
        surtaxes   (query :surtax)
        refundable-credits     (query :refundable-credit)
        non-refundable-credits (query :non-refundable-credit)]
    {:base-items         (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items          (vec (concat (:tax-items refundable-credits)
                                      (:tax-items non-refundable-credits)
                                      (:tax-items surtaxes)))
     :schedule-overrides (:schedule-overrides regime)
     :provisions         (concat (:provisions regime)
                                 (:provisions adds)
                                 (:provisions deducts)
                                 (:provisions refundable-credits)
                                 (:provisions non-refundable-credits)
                                 (:provisions surtaxes))}))

(defn- au-cit-component
  "Build the AU company-tax component map. Base = book-profit +
   base-adjustments (CGT + investment-income lanes); schedule = the
   highest-priority `:schedule-override` (BRE 25 %) or the flat 30 %
   standard rate from `:parameter`; tax-side = franking + FITO
   non-refundable credits via `apply-adjustments`."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [base-items tax-items schedule-overrides provisions]}
        (component-items db ctx as-of)

        ;; Schedule: prefer the elective `:schedule-override` (BRE);
        ;; else the flat 30 % standard rate from `:parameter`.
        std-rate (or (statute/parameter-value-at
                      db "AU.InvIncome.corporate-rate.large" as-of)
                     (throw (ex-info "AU CIT provider: parameter AU.InvIncome.corporate-rate.large not found — was kontor.l10n-au.investment-income-statute installed?"
                                     {:as-of as-of})))
        schedule (if (seq schedule-overrides)
                   (:schedule (first schedule-overrides))
                   {:kontor.schedule/type :flat :rate std-rate})

        scoped-ctx (assoc ctx :component :au-cit :db db :as-of as-of)

        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)

        raw-gross (ts/apply-schedule schedule base' scoped-ctx)
        ;; AU company tax is not refundable on a loss — floor gross at 0M.
        gross     (if (neg? raw-gross) 0M raw-gross)

        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)

        credits  (filter #(= :credit (:op %)) tax-resolved)
        surtaxes (filter #(= :surtax (:op %)) tax-resolved)]
    {:kind            :corporate-income-tax
     :authority       :au-ato
     :base            {:amount base' :commodity functional-commodity}
     :base-transform  (when (seq base-resolved)
                        {:transform/type :adjustments
                         :items          base-resolved})
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :credits         (mapv #(select-keys % [:code :label :amount :refundable? :provenance])
                            credits)
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                            surtaxes)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          (when (seq schedule-overrides)
                        (:code (first schedule-overrides)))
     :provenance      {:provider-id        :au-cit
                       :statute            "Income Tax Assessment Act 1997 + Income Tax Rates Act 1986 §23 / §23AA"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord AuCitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of       (or (:as-of ctx) (:to period))
          book-profit (or (:book-profit inputs)
                          (throw (ex-info "AU CIT provider needs :inputs :book-profit"
                                          {:inputs inputs})))
          au-cit-c    (au-cit-component db ctx as-of book-profit commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :au :authority :au-ato}
         :functional-commodity commodity
         :components           [au-cit-c]})))))

(defn au-cit-provider
  "Build an AU CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-au.cit-statute/install!` + the rate
   parameters that ship with `kontor.l10n-au.investment-income-statute/install!`);
   the provider just folds the applicable provisions for the
   `:au-cit` component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :au-cit)
     :commodity — functional commodity (default :AUD)"
  [{:keys [id commodity] :or {id :au-cit commodity :AUD}}]
  (->AuCitProvider id commodity))
