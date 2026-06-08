(ns kontor.tax.personal-income-tax
  "A generic `PeriodTaxProvider` for personal income tax (ADR-099;
   note 102 §10 / notes 103 / 105). AT Einkommensteuer, AU individual
   tax, DE Einkommensteuer (§32a), FR impôt sur le revenu — all one
   shape:

     liability = adjustment-fold(schedule(gross income − deductions))

   - the base-selector marginalizes (σ_E) the entity's income
     postings into gross income;
   - deductions ride `context :inputs` as a `:base-transform`;
   - the schedule is the jurisdiction's bracket ladder / formula —
     including FR's quotient familial, a `:formula` that reads the
     `:tax-unit` from `ctx` (ADR-099 addendum GAP 3);
   - the adjustment layer — credits and surtaxes — is an ordered,
     signed, base-aware fold (`kontor.tax.tax-schedule/apply-adjustments`,
     research note 105 frontier 1). The config carries computed
     adjustments via `:adjustments`; the consumer supplies more via
     `:inputs :credits` / `:surtaxes` / `:adjustments`. A credit or
     surtax `:amount` may be a fn of the base + running tax (income-
     dependent credits — CA BPA phase-out, US CTC, IN §87A); a
     refundable credit can drive the liability negative — a refund;
   - tax already withheld in-period (PAYE, monthly cumulative
     withholding) rides `:inputs :prepaid` → the component's
     `:prepaid`; `balance` then yields the refund-or-top-up. It does
     NOT reduce `:liability` — the liability is the full-period tax.

   Per-jurisdiction credit and deduction TABLES are l10n content that
   grows on demand — this provider + the schedule are the bounded
   mechanism; the schedule (incl. `:formula`) does not constrain how
   a jurisdiction actually computes the tax."
  (:require [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.reporting.report :as report]
            [kontor.tax.tax-schedule :as ts]))

(defn gross-income
  "The base-selector: marginalize (σ_E) the entity's income postings
   into gross income over the period."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-type  (report/marginalize postings :account-type
                                     {:sign :inflow :commodity commodity})]
    (get-in by-type [:income :value] (money/zero commodity))))

(defn- ->money-items [items commodity]
  (mapv (fn [it] (assoc it :amount (money/money (bigdec (:amount it)) commodity)))
        items))

(defn- adjustment-phase
  "Canonical order of the adjustment fold: non-refundable credits,
   then surtaxes, then refundable credits."
  [item]
  (case (:op item)
    :surtax 1
    :credit (if (:refundable? item) 2 0)
    0))

(defrecord PersonalIncomeTaxProvider
           [id schedule authority commodity statute adjustments]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as context}]
    (let [income    (gross-income context commodity)
          transform (:base-transform inputs)
          taxable   (ts/apply-base-transform transform (:amount income))
          tax-ctx   {:tax-unit (:tax-unit inputs)}
          gross     (max 0M (ts/apply-schedule schedule taxable tax-ctx))
          ;; assemble the adjustment layer — config-computed adjustments
          ;; + the consumer's credits / surtaxes / rich items — in the
          ;; canonical order (non-refundable credits, surtaxes,
          ;; refundable credits).
          items     (->> (concat adjustments
                                 (map #(assoc % :op :credit) (:credits inputs))
                                 (map #(assoc % :op :surtax) (:surtaxes inputs))
                                 (:adjustments inputs))
                         (sort-by adjustment-phase))
          adj-ctx   {:base taxable :gross gross :tax-unit (:tax-unit inputs)}
          {:keys [liability resolved]} (ts/apply-adjustments gross items adj-ctx)
          credits   (filterv #(= :credit (:op %)) resolved)
          surtaxes  (filterv #(= :surtax (:op %)) resolved)
          prepaid   (money/money (bigdec (or (:prepaid inputs) 0M)) commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:authority authority}
        :functional-commodity commodity
        :components
        [{:kind            :personal-income-tax
          :authority       authority
          :base            (money/money taxable commodity)
          :schedule        schedule
          :gross-liability (money/money gross commodity)
          :credits         (->money-items credits commodity)
          :surtaxes        (->money-items surtaxes commodity)
          :liability       (money/money liability commodity)
          :prepaid         prepaid
          :provenance      {:provider-id id :statute statute}
          :line-items
          (into [{:line :gross-income   :label "Gross income"
                  :value income}
                 {:line :taxable-income :label "Taxable income"
                  :value (money/money taxable commodity)}
                 {:line :tax-before-credits :label "Tax before credits"
                  :value (money/money gross commodity)}]
                (concat
                 (map (fn [c] {:line  (:code c)
                               :label (str "Credit — " (:label c))
                               :value (money/money (- (bigdec (:amount c)))
                                                   commodity)})
                      credits)
                 (map (fn [s] {:line  (:code s)
                               :label (str "Surtax — " (:label s))
                               :value (money/money (bigdec (:amount s))
                                                   commodity)})
                      surtaxes)))}]}))))

(defn personal-income-tax-provider
  "Construct a `PersonalIncomeTaxProvider`. Config keys:
     :id          — provider-id keyword
     :schedule    — the jurisdiction's `kontor.tax.tax-schedule`
     :authority   — the taxing-authority keyword
     :commodity   — the functional commodity
     :statute     — optional citation string
     :adjustments — optional ordered vector of config-computed
                    adjustment items (note 105 / `apply-adjustments`):
                    `{:code :label :op :credit|:surtax :refundable?
                    :amount <bigdec|fn>}` — a fn `:amount` sees the
                    base + running tax (DE Soli, JP reconstruction
                    surtax, income-dependent credits)"
  [{:keys [id schedule authority commodity statute adjustments]}]
  (->PersonalIncomeTaxProvider id schedule authority commodity statute
                               (vec adjustments)))
