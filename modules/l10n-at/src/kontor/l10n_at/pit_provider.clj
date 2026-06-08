(ns kontor.l10n-at.pit-provider
  "AT personal income tax provider — Einkommensteuer (ESt) — built as
   a `PeriodTaxProvider` (ADR-099) over the statute-as-data substrate
   (ADR-101). Mirrors
   `kontor.l10n-fr.cit-provider` structurally (single-component fold)
   with a `:progressive-bracket` schedule pulled from
   `parameter-brackets-at` for the right `:as-of` (the FR PME PME
   schedule-override pattern; here applied as the DEFAULT schedule
   rather than an elective override — AT ESt is always the §33 Abs 1
   ladder).

   The provider does THREE things and nothing else:

   1. Reads the `:effective-from`-keyed bracket scale for `:as-of`
      from `:parameter-bracket` data (AT.EStG.§33-Abs-1.brackets).
   2. For the single `:est` component sets `:component :est` in ctx,
      calls `kontor.tax.statute/apply-provisions` for each relevant
      concept, folds base-side adjustments + applies the bracket
      schedule + folds tax-side credits (Familienbonus,
      Alleinverdiener, Verkehrsabsetz, KESt-prepaid, DBA, etc.),
      returns the component map.
   3. Assembles a 1-component `TaxReturnFacts`.

   ## Inputs the consumer supplies

   `:tax-unit` (filing-unit / household config):
     {:children-under-18-count   <long>  optional, default 0 — number
                                         of qualifying children < 18
                                         (drives Familienbonus magnitude)
      :children-over-18-count    <long>  optional, default 0 — number
                                         of qualifying adult children
                                         in education (separate, smaller
                                         Familienbonus rate)
      :children-count            <long>  optional, default 0 — total
                                         children (drives Alleinverdiener
                                         tier selection)
      :alleinverdiener?          <bool>  optional, default false —
                                         single-earner flag for
                                         Alleinverdienerabsetzbetrag
      :employment-relationship?  <bool>  optional, default false —
                                         drives Verkehrsabsetzbetrag
      :kindermehrbetrag-eligible? <bool> optional, default false —
                                         consumer adjudicates the
                                         §33 Abs 7 Z 2 income test
                                         outside the substrate
      :familienbonus-claimed     <BigDec> optional — used by the
                                         Kindermehrbetrag cliff check
                                         (consumer can pre-compute)}

   `:inputs` (period facts):
     {:gross-income                              <BigDecimal>  required —
                                                  §2 EStG Gesamtbetrag
                                                  der Einkünfte (after
                                                  consumer-side deductions
                                                  for Sonderausgaben /
                                                  Werbungskosten / a.B.)
      :cgt-pit-base-additions                    <BigDecimal>  optional
      :cgt-pit-base-deductions-§28               <BigDecimal>  optional
      :investment-pit-base-additions             <BigDecimal>  optional
      :investment-pit-credits-kest-prepaid       <BigDecimal>  optional
      :investment-pit-credits-non-refundable-dba <BigDecimal>  optional}

   ## Compute-fns

   - `:at-familienbonus-plus` — reads `:tax-unit :children-under-18-count`
     × parameter `AT.EStG.§33-Abs-3a.familienbonus-under-18`.
   - `:at-familienbonus-plus-over-18` — same shape for adult children
     in education.
   - `:at-alleinverdiener-amount` — picks the right of the three
     `AT.EStG.§33-Abs-4-Z-1.alleinverdiener-*` parameters based on
     `:tax-unit :children-count`.
   - `:at-kindermehrbetrag-amount` — children-under-18-count ×
     `AT.EStG.§33-Abs-7.kindermehrbetrag`.

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a ~50 LOC kernel sweep tracked separately."
  (:require [kontor.l10n-at.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint for symmetry with FR / DE templates.
(comment pit-statute/install!)

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

(defn- at-familienbonus-plus
  "Familienbonus Plus, children < 18 lane — children-under-18-count ×
   `AT.EStG.§33-Abs-3a.familienbonus-under-18` (parameter)."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        n     (or (get-in ctx [:tax-unit :children-under-18-count]) 0)
        per   (or (statute/parameter-value-at db "AT.EStG.§33-Abs-3a.familienbonus-under-18" as-of) 0M)]
    (* (bigdec n) per)))

(defn- at-familienbonus-plus-over-18
  "Familienbonus Plus, children ≥ 18 in education lane —
   children-over-18-count × the smaller per-child parameter."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        n     (or (get-in ctx [:tax-unit :children-over-18-count]) 0)
        per   (or (statute/parameter-value-at db "AT.EStG.§33-Abs-3a.familienbonus-over-18" as-of) 0M)]
    (* (bigdec n) per)))

(defn- at-alleinverdiener-amount
  "Alleinverdienerabsetzbetrag — picks the right of the three indexed
   parameters based on `:tax-unit :children-count`:

     0 / 1 child  → AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child
     2 children   → AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children
     3+ children  → 2-child amount + (n − 2) × per-addl

   v1 follows the §33 Abs 4 Z 1 statutory structure (the 1-child amount
   is the default for childless single-earners too, per the §106 EStG
   reading where the credit ladder starts at 1)."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        n     (or (get-in ctx [:tax-unit :children-count]) 0)]
    (cond
      (<= n 1) (or (statute/parameter-value-at db "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child" as-of) 0M)
      (= n 2)  (or (statute/parameter-value-at db "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children" as-of) 0M)
      :else    (let [base    (or (statute/parameter-value-at db "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children" as-of) 0M)
                     per-add (or (statute/parameter-value-at db "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-each-addl" as-of) 0M)]
                 (+ base (* (bigdec (- n 2)) per-add))))))

(defn- at-kindermehrbetrag-amount
  "Kindermehrbetrag — children-under-18-count × per-child parameter.
   Statutorily refundable; the provision condition gates on the
   consumer-supplied `:tax-unit :kindermehrbetrag-eligible?` flag."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        n     (or (get-in ctx [:tax-unit :children-under-18-count]) 0)
        per   (or (statute/parameter-value-at db "AT.EStG.§33-Abs-7.kindermehrbetrag" as-of) 0M)]
    (* (bigdec n) per)))

(defn register!
  "Register the four AT PIT compute-fns with `kontor.tax.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :at-familienbonus-plus          at-familienbonus-plus)
  (statute/register-compute-fn! :at-familienbonus-plus-over-18  at-familienbonus-plus-over-18)
  (statute/register-compute-fn! :at-alleinverdiener-amount      at-alleinverdiener-amount)
  (statute/register-compute-fn! :at-kindermehrbetrag-amount     at-kindermehrbetrag-amount))

(register!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds the component
;; ============================================================================

(defn- component-items
  "For the `:est` component, query the statute for all applicable
   base-side + tax-side provisions and resolve them. Returns
   `{:base-items :tax-items :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :est :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :at
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

(defn- est-component
  "Build the ESt component map. Base = gross-income + base-adjustments
   (Regelbesteuerung fold add, §30 Abs 7 ImmoESt-loss carry deduct);
   schedule = §33 Abs 1 progressive bracket scale from
   `parameter-brackets-at` for `:as-of`; tax-side =
   Familienbonus / Alleinverdiener / Verkehrsabsetz / KESt-prepaid /
   DBA-Quellensteuer credits via `apply-adjustments`."
  [db ctx as-of gross-income functional-commodity]
  (let [brackets (or (statute/parameter-brackets-at db "AT.EStG.§33-Abs-1.brackets" as-of)
                     (throw (ex-info "AT PIT provider: no §33 Abs 1 bracket-set in effect at as-of (install kontor.l10n-at.pit-statute first)"
                                     {:as-of as-of})))
        schedule {:kontor.schedule/type :progressive-bracket :brackets brackets}
        {:keys [base-items tax-items provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :est :db db :as-of as-of)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   gross-income base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base')
        ;; ESt is not refundable on a loss — floor the gross at 0M.
        ;; Refundable credits (KESt-prepaid, Alleinverdiener as
        ;; Negativsteuer, Kindermehrbetrag) can still drive liability
        ;; below zero.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)]
    {:kind            :personal-income-tax
     :authority       :at-finanz
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
     :provenance      {:provider-id        :at-pit
                       :statute            "EStG 1988 §33 Abs 1 (Tarif) + §33 Abs 3a-7 (Absetzbeträge) + §27a / §30 (Kapitalvermögen / ImmoESt fold)"
                       :provisions-applied (mapv :kontor.provision/code provisions)
                       :as-of              as-of}}))

(defrecord AtPitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          gross-income (or (:gross-income inputs)
                           (throw (ex-info "AT PIT provider needs :inputs :gross-income"
                                           {:inputs inputs})))
          est-c        (est-component db ctx as-of gross-income commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :at :authority :at-finanz}
         :functional-commodity commodity
         :components           [est-c]})))))

(defn at-pit-provider
  "Build an AT PIT provider. Statute lives in `:provision` /
   `:parameter` / `:parameter-bracket` data (installed via
   `kontor.l10n-at.pit-statute/install!`); the provider just folds
   the applicable provisions for the ESt component.

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :at-pit)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity] :or {id :at-pit commodity :EUR}}]
  (->AtPitProvider id commodity))
