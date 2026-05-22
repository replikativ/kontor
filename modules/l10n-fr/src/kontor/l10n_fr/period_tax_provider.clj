(ns kontor.l10n-fr.period-tax-provider
  "French personal income tax — impôt sur le revenu — as a kontor
   `PeriodTaxProvider` (ADR-099; research note 103).

   The defining feature is the quotient familial: net taxable income
   is divided by the household's `parts`, the per-part quotient run
   through the barème, and the result multiplied back — so the
   schedule is parameterised by the household. It reads the household
   from the `:tax-unit` in `ctx` (ADR-099 addendum GAP 3 — the
   3-arity `apply-schedule` exists precisely so a schedule can depend
   on the filing unit). The plafonnement caps the tax benefit of the
   child half-parts. Both are expressed in a `:formula` schedule —
   the abstraction carries the real CGI computation, quotient and cap
   included, rather than approximating it."
  (:require [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

(def bareme-2024
  "Barème de l'impôt sur le revenu — revenus 2024 (verify against the
   loi de finances). The first tranche is the 0 % band."
  [{:rate 0M    :upper 11294M}
   {:rate 0.11M :upper 28797M}
   {:rate 0.30M :upper 82341M}
   {:rate 0.41M :upper 177106M}
   {:rate 0.45M :upper nil}])

(def ^:private bareme (ts/progressive bareme-2024))

(def plafond-demi-part
  "Plafonnement du quotient familial — the maximum tax benefit per
   additional half-part, 2024 (verify)."
  1759M)

(defn- div
  "BigDecimal division rounded to the cent — the per-part quotient
   need not divide evenly (a household may have 2.5 parts)."
  ^java.math.BigDecimal [a b]
  (.divide (bigdec a) (bigdec b) 2 java.math.RoundingMode/HALF_EVEN))

(defn- impot-revenu
  "The impôt sur le revenu with the quotient familial. `ctx :tax-unit`
   carries `{:parts P :reference-parts R}` — P the household's parts,
   R the parts disregarding the child half-parts (1 for a single
   filer, 2 for a couple; default 1). The plafonnement caps the
   benefit of the (P − R) extra parts at `plafond-demi-part` per
   half-part: `tax = max(quotient-tax, reference-tax − cap)`."
  ^java.math.BigDecimal [revenu {:keys [tax-unit]}]
  (let [{:keys [parts reference-parts]
         :or   {parts 1M reference-parts 1M}} tax-unit
        quotient-tax  (* parts (ts/apply-schedule bareme (div revenu parts)))
        reference-tax (* reference-parts
                         (ts/apply-schedule bareme (div revenu reference-parts)))
        extra-halves  (* 2M (- parts reference-parts))
        max-benefit   (* extra-halves plafond-demi-part)]
    (max quotient-tax (- reference-tax max-benefit))))

(defn fr-income-tax-provider
  "FR personal income tax — impôt sur le revenu — provider. The
   quotient-familial `:formula` schedule. Réductions / crédits d'impôt
   ride `context :inputs` as credits; the household `:tax-unit` rides
   `:inputs` too."
  [_]
  (pit/personal-income-tax-provider
   {:id        :fr-ir
    :schedule  {:schedule/type :formula :fn impot-revenu}
    :authority :fr-dgfip
    :commodity :EUR
    :statute   "CGI art. 197"}))
