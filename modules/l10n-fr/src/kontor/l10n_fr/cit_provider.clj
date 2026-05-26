(ns kontor.l10n-fr.cit-provider
  "FR corporate income tax provider — Impôt sur les sociétés (IS) +
   Contribution sociale 3.3 % (CGE) + Crédit d'Impôt Recherche (CIR) +
   régime mère-fille — built as a `PeriodTaxProvider` (ADR-099) over
   the statute-as-data substrate (ADR-101; research notes 108/109).
   Mirrors `kontor.l10n-de.cit-provider` (ADR-104) — the reference
   template for per-jurisdiction CIT authoring.

   The provider does THREE things and nothing else:

   1. Reads the IS standard rate + PME bracket scale from `:parameter`s.
   2. For the single IS component sets `:component :is` in ctx, calls
      `kontor.statute/apply-provisions` for each relevant concept
      (`:elective-regime`, `:base-transform-add` / `:deduct`,
      `:surtax`, `:refundable-credit` / `:non-refundable-credit`),
      picks the highest-priority `:schedule-override` if any (PME ⇒
      progressive 15 %/25 %; absent ⇒ flat 25 %), folds base + tax
      adjustments, returns the component map.
   3. Assembles a 1-component `TaxReturnFacts`.

   Everything else — what counts as PME, how CGE is computed, the
   mère-fille / CIR menu — lives in `kontor.l10n-fr.cit-statute` as
   `:provision` / `:parameter` data, NOT in this provider's code.

   ## Inputs the consumer supplies

   `:tax-unit` (company config):
     {:pme?            <bool>   PME eligibility (CA ≤ €10 M + capital
                                libéré + ≥75 % individual ownership;
                                consumer adjudicates per CGI Art. 219 I-b)
      :cge-exempt?     <bool>   Contribution sociale exemption (CA HT
                                < €7.63 M + PME-eligible per CGI Art.
                                235 ter ZC)
      :cir-refundable? <bool>   CIR refundability (true for PMEs in the
                                EU sense; non-PMEs carry the credit
                                forward 3 years, out-of-substrate)}

   `:inputs` (period facts):
     {:book-profit             <BigDecimal>  required — bénéfice fiscal
                                              (after company-side
                                              réintégrations / déductions
                                              the consumer maintains
                                              outside the substrate)
      :participation-dividends <BigDecimal>  optional, default 0 —
                                              gross dividends from
                                              qualifying subsidiaries
                                              (régime mère-fille; the
                                              substrate adds back the
                                              5 % quote-part)
      :cir-qualifying-expenses <BigDecimal>  optional, default 0 —
                                              qualifying R&D expenses
                                              for CIR; 30 % rate on the
                                              first €100 M + 5 % above}

   ## Out-of-substrate

   `:tax-context-fact` keyed by `:inputs` keys works for facts the
   consumer supplies as-is. Computed shares (mère-fille 5 %, CIR
   piecewise, CGE 3.3 % on excess over €763 000) are expressed via the
   `:kontor.provision/compute-fn` escape hatch — see `register!` below.

   ## Limitations (note 109 §3-§4)

   - Contribution exceptionnelle (CEBGE, LF 2025 / 2026; 20.6 % /
     41.2 % for turnover ≥ €1 B with two-year averaging) — out of
     scope for v1; the two-year carry shape is not on the substrate.
   - Déficit reportable (CGI Art. 209 I al. 3; €1 M + 50 % cap) — this
     is note 105 frontier 2 (inter-period carry); FR IS is the
     demand-trigger but the substrate work is out of scope here.
   - CIR carry-forward for non-PMEs (3-year carry then refund) —
     same inter-period substrate gap; v1 floors non-refundable CIR at
     zero per `apply-adjustments` non-refundable semantics."
  (:require [kontor.l10n-fr.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration — the ADR-101 §D2 escape hatch
;; ============================================================================

(defn- as-of-from-ctx
  "Read the as-of instant from ctx (`:as-of` preferred; `:period :to`
   as fallback for callers who only thread the period)."
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- inputs-fact
  "Read a fact from `:inputs` of ctx, with a default if missing."
  [ctx fact default]
  (or (get-in ctx [:inputs fact]) default))

(defn- fr-mere-fille-addback
  "Régime mère-fille — 5 % quote-part de frais et charges add-back on
   qualifying dividends. Mirrors DE §8b Abs. 5: the consumer's
   `:book-profit` ALREADY excludes the full dividend (French
   réintégration practice posts qualifying dividends to a non-taxable
   account), and we add back the 5 % quote-part the statute keeps
   taxable. `:participation-dividends` × 5 %."
  ^java.math.BigDecimal [ctx]
  (let [quote-part (statute/parameter-value-at
                    (:db ctx) "FR.MereFille.quote-part"
                    (as-of-from-ctx ctx))]
    (* (inputs-fact ctx :participation-dividends 0M) quote-part)))

(defn- fr-cge-on-is
  "Contribution sociale 3.3 % — surtax on IS exceeding €763 000.
   `rate × max(0, running − abattement)`. Reads both rate and
   abattement from `:parameter` (late-bound — `apply-adjustments`
   threads `:running` into the inner fn at fold time)."
  [ctx]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (statute/parameter-value-at db "FR.CGE.rate" as-of)
        abattement (statute/parameter-value-at db "FR.CGE.abattement" as-of)]
    (fn [ctx-w-running]
      (let [running (bigdec (:running ctx-w-running))]
        (* rate (max 0M (- running abattement)))))))

(defn- fr-cir
  "Crédit d'Impôt Recherche — 30 % of qualifying R&D expenses on the
   first €100 M + 5 % above. Reads both rates and the kink threshold
   from `:parameter`. Returns a BigDecimal (not a late-bound fn — the
   credit amount is independent of the running tax)."
  ^java.math.BigDecimal [ctx]
  (let [db         (:db ctx)
        as-of      (as-of-from-ctx ctx)
        expenses   (inputs-fact ctx :cir-qualifying-expenses 0M)
        rate-base  (statute/parameter-value-at db "FR.CIR.rate-base" as-of)
        rate-above (statute/parameter-value-at db "FR.CIR.rate-above" as-of)
        threshold  (statute/parameter-value-at db "FR.CIR.threshold" as-of)
        in-band    (min expenses threshold)
        above      (max 0M (- expenses threshold))]
    (+ (* in-band rate-base)
       (* above   rate-above))))

(defn register!
  "Register the three FR CIT compute-fns with `kontor.statute`. Called
   automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :fr-mere-fille-addback fr-mere-fille-addback)
  (statute/register-compute-fn! :fr-cge-on-is          fr-cge-on-is)
  (statute/register-compute-fn! :fr-cir                fr-cir))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.statute, folds, builds the component
;; ============================================================================

(defn- component-items
  "For the IS component, query the statute for all applicable items
   across the FR concepts and return them pre-grouped:

     {:base-items         [<:base-add / :base-deduct>]
      :tax-items          [<:credit / :surtax>]
      :schedule-overrides [<:schedule-override>]
      :provisions         [<source :provision pull-map>]}

   `apply-provisions` (post-polish) returns items pre-grouped by `:op`
   category per concept, so the provider concatenates the per-concept
   queries by key rather than splitting them itself."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :is :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :fr
                                                   :as-of        as-of}
                                               scoped-ctx))
        regime    (query :elective-regime)
        adds      (query :base-transform-add)
        deducts   (query :base-transform-deduct)
        surtaxes  (query :surtax)
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

(defn- is-component
  "Build the IS component map. Base = book-profit + base-adjustments
   (mère-fille 5 % addback the canonical case); schedule = the
   highest-priority `:schedule-override` (PME ⇒ progressive 15 %/25 %)
   or the flat IS standard rate (25 %) from `:parameter`;
   surtaxes / credits via `apply-adjustments`."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [base-items tax-items schedule-overrides provisions]}
        (component-items db ctx as-of)

        ;; Schedule: prefer the elective `:schedule-override` (PME); else
        ;; the flat 25 % standard rate from `:parameter`.
        schedule (if (seq schedule-overrides)
                   (:schedule (first schedule-overrides))
                   {:kontor.schedule/type :flat
                    :rate          (statute/parameter-value-at
                                    db "FR.IS.standard-rate" as-of)})

        scoped-ctx (assoc ctx :component :is :db db :as-of as-of)

        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   book-profit base-items scoped-ctx)

        gross (ts/apply-schedule schedule base')

        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)

        credits  (filter #(= :credit (:op %)) tax-resolved)
        surtaxes (filter #(= :surtax (:op %)) tax-resolved)]
    {:kind            :corporate-income-tax
     :authority       :fr-dgfip
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
     :provenance      {:provider-id        :fr-cit
                       :statute            "CGI Art. 219 + 216 + 235 ter ZC + 244 quater B"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord FRCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of       (or (:as-of ctx) (:to period))
          book-profit (or (:book-profit inputs)
                          (throw (ex-info "FR CIT provider needs :inputs :book-profit"
                                          {:inputs inputs})))
          is-c        (is-component db ctx as-of book-profit commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :fr :authority :fr-dgfip}
        :functional-commodity commodity
        :components           [is-c]}))))

(defn fr-cit-provider
  "Build a FR CIT provider. Statute lives in `:provision` / `:parameter`
   data (installed via `kontor.l10n-fr.cit-statute/install!`); the
   provider just folds the applicable provisions for the IS component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :fr-cit)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity] :or {id :fr-cit commodity :EUR}}]
  (->FRCITProvider id commodity))

