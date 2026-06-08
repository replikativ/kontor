(ns kontor.l10n-mx.pit-provider
  "MX personal income tax provider — ISR personas físicas — built as
   a `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-at.pit-provider` structurally (single-component fold
   with a `:progressive-bracket` schedule pulled from
   `parameter-brackets-at` for the right `:as-of`).

   The provider does THREE things and nothing else:

   1. Reads the `:effective-from`-keyed bracket scale for `:as-of`
      from `:parameter-bracket` data (MX.PIT.art-152.brackets).
   2. For the single `:isr-pf` component sets `:component :isr-pf`
      in ctx, calls `kontor.tax.statute/apply-provisions` for each
      relevant concept, folds base-side adjustments + applies the
      bracket schedule + folds tax-side credits (subsidio para el
      empleo refundable, ISR retenido refundable, art. 140 factor
      non-refundable, art. 54 bank-WHT refundable, art. 5 FTC
      non-refundable), returns the component map.
   3. Assembles a 1-component `TaxReturnFacts`.

   ## Inputs the consumer supplies

   `:tax-unit` (none required in v1; reserved for filing-status /
   household configuration when future provisions need it).

   `:inputs` (period facts):
     {:gross-income                        <BigDecimal>  required —
                                          LISR art. 152 base
                                          (consumer maintains the
                                          deducciones-personales
                                          net outside the substrate)
      :subsidio-empleo                     <BigDecimal>  optional —
                                          annual subsidio para el
                                          empleo magnitude
                                          (refundable; Q5.5
                                          migration — see
                                          subsidio-empleo-input)
      :isr-retenido                        <BigDecimal>  optional —
                                          ISR withheld throughout
                                          the year (refundable)
      :cgt-pit-base-additions              <BigDecimal>  optional —
                                          cgt-provider real-estate
                                          art. 120 + unlisted-share
                                          art. 22 fold
      :investment-pit-base-additions       <BigDecimal>  optional —
                                          investment-income-provider
                                          PF lanes
      :investment-pit-credits-factor       <BigDecimal>  optional —
                                          art. 140 factor-credit
                                          (non-refundable)
      :investment-pit-credits-bank-wht     <BigDecimal>  optional —
                                          art. 54 bank-interest WHT
                                          (refundable)
      :investment-pit-credits-ftc          <BigDecimal>  optional —
                                          art. 5 foreign tax credit
                                          (non-refundable)}

   ## Subsidio para el empleo

   `(subsidio-empleo-input months as-of db)` is the substrate-aware
   helper: it reads
   `MX.PIT.art-96-bis.subsidio-empleo-uma-month` × `…factor` from
   `:parameter` data and returns `months × uma-month × factor`. New
   consumers should prefer it over the legacy
   `period_tax_provider/subsidio-empleo-credit` (which hard-codes
   the 2025 MX$ 475 figure).

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a small kernel sweep tracked separately."
  (:require [kontor.l10n-mx.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint for symmetry with FR / DE / AT templates.
(comment pit-statute/install!)

;; ============================================================================
;; Helpers — substrate-aware subsidio-empleo magnitude
;; ============================================================================

(defn subsidio-empleo-input
  "Compute the annual subsidio para el empleo magnitude from
   parameters (`MX.PIT.art-96-bis.subsidio-empleo-uma-month` ×
   `…factor`). Replaces the legacy hard-coded
   `period_tax_provider/subsidio-empleo-credit` factory; reads
   bitemporal values from the substrate.

   Returns a BigDecimal magnitude suitable for
   `:inputs :subsidio-empleo`. When the UMA-month / factor
   parameters are not installed (the optional v1 stubs), returns
   `(* months 475M)` as a back-compat fallback matching the legacy
   2025 figure.

   Arguments:
     months — number of months the worker was under the ceiling
              (typically 12 for a full-year low earner).
     as-of  — `java.util.Date` to resolve the parameter values at.
     db     — datahike DB to read parameters from."
  ^java.math.BigDecimal [months as-of db]
  (let [uma    (statute/parameter-value-at db "MX.PIT.art-96-bis.subsidio-empleo-uma-month" as-of)
        factor (statute/parameter-value-at db "MX.PIT.art-96-bis.subsidio-empleo-factor" as-of)]
    (if (and uma factor)
      ;; Strict reading: uma is the UMA elevated to a month; factor is
      ;; the 11.82 % statutory rate per LISR art. 96-bis post-2024.
      ;; Annual magnitude per qualifying month = uma × factor; sum
      ;; across qualifying months.
      (* (bigdec months) uma factor)
      ;; Fallback for tests that don't install the optional UMA-month
      ;; parameter — match the legacy hard-coded 2025 figure (MX$ 475 /
      ;; month). Consumers should install MX.PIT.art-96-bis.subsidio-
      ;; empleo-uma-month + …factor for bitemporal-correct values.
      (* (bigdec months) 475M))))

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds component
;; ============================================================================

(defn- component-items
  "For the `:isr-pf` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them. Returns
   `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :isr-pf :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :mx
                                                   :as-of        as-of}
                                               scoped-ctx))
        adds       (query :base-transform-add)
        deducts    (query :base-transform-deduct)
        surtaxes   (query :surtax)
        refundable-credits     (query :refundable-credit)
        non-refundable-credits (query :non-refundable-credit)]
    {:base-items (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items  (vec (concat (:tax-items refundable-credits)
                              (:tax-items non-refundable-credits)
                              (:tax-items surtaxes)))
     :provisions (concat (:provisions adds)
                         (:provisions deducts)
                         (:provisions refundable-credits)
                         (:provisions non-refundable-credits)
                         (:provisions surtaxes))}))

(defn- isr-pf-component
  "Build the ISR personas físicas component map. Base = gross-income
   + base-adjustments (CGT art. 120 fold, investment-income fold);
   schedule = LISR art. 152 progressive bracket scale from
   `parameter-brackets-at` for `:as-of`; tax-side = subsidio /
   ISR-retenido / art-140-factor / art-54-bank-WHT / art-5-FTC
   credits via `apply-adjustments`."
  [db ctx as-of gross-income functional-commodity]
  (let [brackets (or (statute/parameter-brackets-at db "MX.PIT.art-152.brackets" as-of)
                     (throw (ex-info "MX PIT provider: no LISR art. 152 bracket-set in effect at as-of (install kontor.l10n-mx.pit-statute first)"
                                     {:as-of as-of})))
        schedule {:kontor.schedule/type :progressive-bracket :brackets brackets}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :isr-pf :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   gross-income base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base')
        ;; ISR is not refundable on a loss — floor the gross at 0M.
        ;; Refundable credits (subsidio, ISR retenido, bank-WHT) can
        ;; still drive liability below zero.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :personal-income-tax
     :authority       :mx-sat
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
     :provenance      {:provider-id        :mx-pit
                       :statute            "LISR Título IV art. 152 (Tarifa) + arts. 96-bis / 96 / 120 / 140 / 54 / 5 (provisions)"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord MxPitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          gross-income (or (:gross-income inputs)
                           (throw (ex-info "MX PIT provider needs :inputs :gross-income"
                                           {:inputs inputs})))
          isr-pf-c     (isr-pf-component db ctx as-of gross-income commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :mx :authority :mx-sat}
         :functional-commodity commodity
         :components           [isr-pf-c]})))))

(defn mx-pit-provider
  "Build an MX PIT provider. Statute lives in `:provision` /
   `:parameter` / `:parameter-bracket` data (installed via
   `kontor.l10n-mx.pit-statute/install!`); the provider just folds
   the applicable provisions for the ISR personas físicas component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :mx-pit)
     :commodity — functional commodity (default :MXN)"
  [{:keys [id commodity] :or {id :mx-pit commodity :MXN}}]
  (->MxPitProvider id commodity))
