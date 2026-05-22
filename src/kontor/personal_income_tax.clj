(ns kontor.personal-income-tax
  "A generic `PeriodTaxProvider` for personal income tax (ADR-099;
   note 102 §10 / note 103). AT Einkommensteuer, AU individual tax,
   DE Einkommensteuer (§32a), FR impôt sur le revenu — all one shape:

     liability = schedule(gross income − deductions)
                 − Σ credits + Σ surtaxes

   - the base-selector marginalizes (σ_E) the entity's income
     postings into gross income;
   - deductions ride `context :inputs` as a `:base-transform`;
   - the schedule is the jurisdiction's bracket ladder / formula —
     including FR's quotient familial, a `:formula` that reads the
     `:tax-unit` from `ctx` (ADR-099 addendum GAP 3);
   - credits + consumer-supplied surtaxes ride `:inputs`; computed
     surtaxes derived from the tax itself (DE Solidaritätszuschlag)
     come from the config's `:surtax-fns`.

   Per-jurisdiction credit and deduction TABLES are l10n content that
   grows on demand — this provider + the schedule are the bounded
   mechanism; the schedule (incl. `:formula`) does not constrain how
   a jurisdiction actually computes the tax."
  (:require [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.tax-schedule :as ts]))

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

(defn- sum-amounts [items]
  (reduce (fn [acc it] (+ acc (bigdec (:amount it)))) 0M items))

(defn- ->money-items [items commodity]
  (mapv (fn [it] (assoc it :amount (money/money (bigdec (:amount it)) commodity)))
        items))

(defrecord PersonalIncomeTaxProvider
           [id schedule authority commodity statute surtax-fns]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as context}]
    (let [income     (gross-income context commodity)
          transform  (:base-transform inputs)
          taxable    (ts/apply-base-transform transform (:amount income))
          tax-ctx    {:tax-unit (:tax-unit inputs)}
          gross      (max 0M (ts/apply-schedule schedule taxable tax-ctx))
          credits    (vec (:credits inputs))
          after-cred (max 0M (- gross (sum-amounts credits)))
          surtaxes   (vec (concat (keep #(% after-cred) (or surtax-fns []))
                                  (:surtaxes inputs)))
          liability  (+ after-cred (sum-amounts surtaxes))]
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
          :prepaid         (money/zero commodity)
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
     :id         — provider-id keyword
     :schedule   — the jurisdiction's `kontor.tax-schedule`
     :authority  — the taxing-authority keyword
     :commodity  — the functional commodity
     :statute    — optional citation string
     :surtax-fns — optional vector of fns `after-credits-bigdecimal →
                   {:code :label :amount}` (or nil) — computed
                   surtaxes derived from the tax (DE Soli)"
  [{:keys [id schedule authority commodity statute surtax-fns]}]
  (->PersonalIncomeTaxProvider id schedule authority commodity statute
                               surtax-fns))
