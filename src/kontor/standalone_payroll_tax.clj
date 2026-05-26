(ns kontor.standalone-payroll-tax
  "A generic `PeriodTaxProvider` for standalone employer payroll taxes
   — a flat or capped levy on the marginalized employer wage sum
   (ADR-099; research notes 102 §10 / 103). MX ISN, AU state payroll
   tax, AT Kommunalsteuer and similar municipal / state wage levies
   are all one mechanism: `schedule × Σ(wage-expense postings)`.

   This is the period-tax substrate's first GL-marginalizing provider.
   The base-selector IS `kontor.report` — the σ_E of ADR-096: the wage
   base is the sum of the entity's wage-expense postings over the
   period, identified by account code. The CA T1 pilot wrapped a
   slip-fed `compute`; this proves `(scope, marginalize, schedule)`
   end to end on a real provider.

   It is NOT for social-insurance contributions — those stay
   engine-authoritative (ADR-075; note 102 §7-stress-4). It is for a
   standalone payroll *levy* a jurisdiction assesses on aggregate
   wages. l10n modules construct it with their rate + chart codes."
  (:require [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.tax-schedule :as ts]))

(defn wage-base
  "The base-selector: marginalize (σ_E) the entity's wage-expense
   postings — those on an account whose `:kontor.account/code` matches a
   `wage-codes` pattern (exact, or `\"prefix%\"`) — over the period
   into one Money sum. `context` carries `:conn`, `:period`, and an
   optional `:entity` (ADR-031)."
  [{:keys [conn entity period]} wage-codes commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))]
    (:value (report/run-engine postings
                               {:engine    :account-codes
                                :codes     wage-codes
                                :sign      :inflow
                                :commodity commodity}
                               {}))))

(defrecord StandalonePayrollTaxProvider
           [id schedule wage-codes authority commodity statute base-label]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as context}]
    (let [base  (wage-base context wage-codes commodity)
          gross (money/money (ts/apply-schedule schedule (:amount base))
                             commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:authority authority}
        :functional-commodity commodity
        :components
        [{:kind            :payroll-tax-employer
          :authority       authority
          :base            base
          :schedule        schedule
          :gross-liability gross
          :liability       gross
          :prepaid         (money/zero commodity)
          :provenance      {:provider-id id :statute statute}
          :line-items      [{:line  :wage-base
                             :label (or base-label "Taxable wage base")
                             :value base}
                            {:line  :levy
                             :label "Employer payroll levy"
                             :value gross}]}]}))))

(defn standalone-payroll-tax-provider
  "Construct a `StandalonePayrollTaxProvider`. Config keys:
     :id          — provider-id keyword
     :schedule    — a `kontor.tax-schedule` (`flat` / `capped` / …)
     :wage-codes  — account-code patterns identifying wage expense
     :authority   — the taxing-authority keyword
     :commodity   — the functional commodity
     :statute     — optional citation string
     :base-label  — optional label for the wage-base line item"
  [{:keys [id schedule wage-codes authority commodity statute base-label]}]
  (->StandalonePayrollTaxProvider id schedule wage-codes authority
                                  commodity statute base-label))
