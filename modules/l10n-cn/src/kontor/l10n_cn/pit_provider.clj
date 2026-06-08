(ns kontor.l10n-cn.pit-provider
  "CN personal income tax provider — Individual Income Tax (个人所得税,
   IIT) — built as a `PeriodTaxProvider` (ADR-099) over the
   statute-as-data substrate (ADR-101). Mirrors
   `kontor.l10n-at.pit-provider` structurally
   (single-component + bracket-from-parameter schedule) with a
   business-income schedule-override and the cumulative withholding
   reconciliation (累计预扣 → 年度汇算清缴) via `:inputs :prepaid`.

   The provider does THREE things and nothing else:

   1. Reads the `:effective-from`-keyed bracket scale for `:as-of`
      from `:parameter-bracket` data
      (`CN.IIT.comprehensive-income.brackets`).
   2. For the single `:iit` component sets `:component :iit` in ctx,
      calls `kontor.tax.statute/apply-provisions` for each relevant
      concept, folds base-side adjustments + applies the schedule
      (the comprehensive-income bracket scale by default; the
      business-income 5-band scale via the §3 ¶2 schedule-override
      when `:tax-unit :business-income? true`). Threads
      `:inputs :prepaid` to the component's `:prepaid` field for
      annual-reconciliation balance.
   3. Assembles a 1-component `TaxReturnFacts`.

   ## CN-specific: NO aggregation of category 7 / 9 income

   Unlike AT / DE / FR / JP, CN PIT does NOT consume CGT or
   investment-income lanes. Category 7 (interest / dividends /
   royalties) and category 9 (capital gains) are SEPARATE final taxes
   at flat 20 % computed by `cn-iit-investment-income-provider` and
   `cn-iit-cgt-provider`. The audit-trail consumer
   query 'what made up this taxpayer's total tax' composes THREE
   provider runs.

   ## Inputs the consumer supplies

   `:tax-unit` (filing-unit / household config):
     {:business-income?    <bool>  optional — true swaps the schedule
                                   to the 5-band business-income scale
                                   for 个体工商户 / sole-trader /
                                   partnership-allocated cases.}

   `:inputs` (period facts — `:gross-comprehensive-income` is the
   required key for comprehensive-income filers, `:business-taxable-
   income` is the required key for business-income filers):
     {:gross-comprehensive-income       <BigDecimal>  required for
                                                       wage filers —
                                                       sum of category
                                                       1/2/3/4 income
                                                       (wages + labour-
                                                       service + author
                                                       + royalties)
      :business-taxable-income          <BigDecimal>  required when
                                                       :business-income?
                                                       — already net of
                                                       business expenses
                                                       + basic deduction
                                                       per §3 ¶2
      :pit-base-deductions-statutory    <BigDecimal>  optional — 五险一金
                                                       employee
                                                       contributions
      :pit-base-deductions-children-education
                                        <BigDecimal>  optional — children
      :pit-base-deductions-continuing-education
                                        <BigDecimal>  optional
      :pit-base-deductions-major-medical
                                        <BigDecimal>  optional
      :pit-base-deductions-mortgage-interest
                                        <BigDecimal>  optional
      :pit-base-deductions-housing-rent <BigDecimal>  optional
      :pit-base-deductions-elderly-support
                                        <BigDecimal>  optional
      :pit-base-deductions-infant-care  <BigDecimal>  optional (post-2022)
      :prepaid                          <BigDecimal>  optional —
                                                       cumulative monthly
                                                       withholding (累计预扣)
                                                       for the annual
                                                       reconciliation;
                                                       feeds the
                                                       component's
                                                       :prepaid field}

   ## TODO — audit-doc seam

   The eventual posting builder (`tax-return-posting-builder`) does
   not yet stamp `:transaction/audit-doc` referencing the responsible
   `:kontor.provision`. The citation already lives on the provision
   row; the wire-up is a small kernel sweep tracked separately."
  (:require [kontor.l10n-cn.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; suppress 'unused require' lint — symmetric with AT / FR / JP templates.
(comment pit-statute/install!)

;; ============================================================================
;; Provider — pulls items from kontor.tax.statute, folds, builds the component
;; ============================================================================

(defn- component-items
  "For the `:iit` component, query the statute for all applicable
   base-side + tax-side + schedule-override provisions and resolve
   them. Returns `{:base-items :tax-items :schedule-overrides
   :provisions}`."
  [db ctx as-of]
  (let [scoped-ctx (assoc ctx :component :iit :db db :as-of as-of)
        query      (fn [concept]
                     (statute/apply-provisions db {:concept      concept
                                                   :jurisdiction :cn
                                                   :as-of        as-of}
                                               scoped-ctx))
        adds       (query :base-transform-add)
        deducts    (query :base-transform-deduct)
        overrides  (query :elective-regime)
        surtaxes   (query :surtax)
        refundable-credits     (query :refundable-credit)
        non-refundable-credits (query :non-refundable-credit)]
    {:base-items         (vec (concat (:base-items adds) (:base-items deducts)))
     :tax-items          (vec (concat (:tax-items refundable-credits)
                                      (:tax-items non-refundable-credits)
                                      (:tax-items surtaxes)))
     :schedule-overrides (:schedule-overrides overrides)
     :provisions         (concat (:provisions adds)
                                 (:provisions deducts)
                                 (:provisions overrides)
                                 (:provisions refundable-credits)
                                 (:provisions non-refundable-credits)
                                 (:provisions surtaxes))}))

(defn- pick-schedule
  "Pick the effective schedule for the IIT component — the first
   (priority-ordered, ambiguity-trapped) `:schedule-override` if any,
   otherwise the provided default (comprehensive-income brackets)."
  [default-schedule overrides]
  (if-let [override (first overrides)]
    (:schedule override)
    default-schedule))

(defn- iit-component
  "Build the IIT component map. Base = gross-income − basic deduction
   − statutory contributions − special-additional deductions (all via
   the substrate's base-side provision fold); schedule = §3 ¶1
   comprehensive-income progressive brackets from
   `parameter-brackets-at` for `:as-of`, OR (when
   `:tax-unit :business-income? true`) the §3 ¶2 business-income
   5-band schedule via `:schedule-override`. Threads `:inputs :prepaid`
   into the component's `:prepaid` field for annual-reconciliation
   balance (ADR-099 Addendum 3)."
  [db ctx as-of gross-income functional-commodity]
  (let [comprehensive-brackets (or (statute/parameter-brackets-at db "CN.IIT.comprehensive-income.brackets" as-of)
                                   (throw (ex-info "CN PIT provider: no comprehensive-income brackets in effect at as-of (install kontor.l10n-cn.pit-statute first)"
                                                   {:as-of as-of})))
        default-schedule {:kontor.schedule/type :progressive-bracket
                          :brackets comprehensive-brackets}
        {:keys [base-items tax-items schedule-overrides provisions]} (component-items db ctx as-of)
        scoped-ctx (assoc ctx :component :iit :db db :as-of as-of)
        schedule   (pick-schedule default-schedule schedule-overrides)
        {base'         :base
         base-resolved :resolved} (ts/apply-base-adjustments
                                   gross-income base-items scoped-ctx)
        raw-gross  (ts/apply-schedule schedule base' scoped-ctx)
        ;; IIT is not refundable on a loss — floor the gross at 0M.
        gross      (if (neg? raw-gross) 0M raw-gross)
        {liability   :liability
         tax-resolved :resolved} (ts/apply-adjustments
                                  gross tax-items scoped-ctx)
        prepaid (or (get-in ctx [:inputs :prepaid]) 0M)]
    (cond->
     {:kind            :personal-income-tax
      :authority       :cn-tax
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
      :provenance      {:provider-id        :cn-pit
                        :statute            "中华人民共和国个人所得税法 (IIT Law) §3 / §6 + STA Ann. 2018 No. 60 + STA Ann. 2022 No. 7"
                        :provisions-applied (mapv :kontor.provision/code provisions)
                        :as-of              as-of}}
      (pos? prepaid) (assoc :prepaid {:amount prepaid :commodity functional-commodity}))))

(defrecord CnPitProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs tax-unit] :as ctx}]
    (let [as-of        (or (:as-of ctx) (:to period))
          gross-income (cond
                         (:business-income? tax-unit)
                         (or (:business-taxable-income inputs)
                             (throw (ex-info "CN PIT provider (business-income mode) needs :inputs :business-taxable-income"
                                             {:inputs inputs})))
                         :else
                         (or (:gross-comprehensive-income inputs)
                             (throw (ex-info "CN PIT provider needs :inputs :gross-comprehensive-income"
                                             {:inputs inputs}))))
          iit-c        (iit-component db ctx as-of gross-income commodity)]
      (ptp/assert-monocommodity-facts!
       (ptp/tax-return-facts
        {:entity               entity
         :period               period
         :jurisdiction         {:country :cn :authority :cn-tax}
         :functional-commodity commodity
         :components           [iit-c]})))))

(defn cn-pit-provider
  "Build a CN PIT provider. Statute lives in `:provision` /
   `:parameter` / `:parameter-bracket` data (installed via
   `kontor.l10n-cn.pit-statute/install!`); the provider just folds
   the applicable provisions for the IIT component.

   The annual reconciliation (年度汇算清缴) reads
   `:inputs :prepaid` (the cumulative monthly withholding 累计预扣
   computed by the payroll layer) and populates the component's
   `:prepaid` field; `kontor.tax.period-tax-provider/balance` yields
   the residual (refund-or-top-up).

   Config (all optional with sensible defaults):
     :id        — provider id keyword (default :cn-pit)
     :commodity — functional commodity (default :CNY)"
  [{:keys [id commodity] :or {id :cn-pit commodity :CNY}}]
  (->CnPitProvider id commodity))
