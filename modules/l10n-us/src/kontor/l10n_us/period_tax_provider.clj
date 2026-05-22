(ns kontor.l10n-us.period-tax-provider
  "US federal income tax as a kontor `PeriodTaxProvider` (ADR-099;
   research notes 103 / 104).

   Two providers:

   - `us-corporate-income-tax-provider` — Form 1120: a flat 21 %
     (IRC §11, post-TCJA) on taxable income, book profit adjusted by
     the Schedule M-1 / M-3 book-to-tax reconciliation that rides
     `context :inputs` as a `:base-transform`.

   - `us-personal-income-tax-provider` — Form 1040: the seven-bracket
     ordinary-income ladder of IRC §1, where BOTH the bracket
     thresholds AND the standard deduction depend on the taxpayer's
     filing status (single / married-filing-jointly /
     married-filing-separately / head-of-household). The schedule is a
     `:formula` that reads `(:filing-status (:tax-unit ctx))` and runs
     the matching `progressive` table — the same `:tax-unit`-in-`ctx`
     mechanism FR uses for the quotient familial (ADR-099 addendum
     GAP 3). The standard deduction is applied inside the formula
     unless the consumer supplies their own deduction `:base-transform`
     (the itemize-vs-standard election).

   State corporate / personal income tax (the 50-state patchwork) and
   the 15 % corporate AMT (CAMT) are a later iteration — see the
   note-104 audit finding: state returns are a Phase-4 multi-component
   fan-out (one `PeriodTaxProvider` per state), not a substrate change."
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Form 1120 — federal corporate income tax
;; ============================================================================

(def federal-rate
  "US federal corporate income tax — flat 21 % (IRC §11)."
  0.21M)

(defn us-corporate-income-tax-provider
  "US federal corporate income tax (Form 1120) provider. Config:
     :rate — optional override (default 21 %)"
  [{:keys [rate]}]
  (cit/corporate-income-tax-provider
   {:id        :us-1120
    :rate      (or rate federal-rate)
    :authority :us-irs
    :commodity :USD
    :statute   "IRC §11"}))

;; ============================================================================
;; Form 1040 — federal personal income tax
;; ============================================================================
;;
;; Tax year 2024 figures (IRS Rev. Proc. 2023-34). VERIFY AGAINST
;; CURRENT LAW — the brackets and the standard deduction are inflation-
;; indexed every year, and the TCJA individual-rate schedule is itself
;; scheduled to sunset after 2025.

(def ^:private ordinary-rates
  "The seven ordinary-income marginal rates of IRC §1(j) — common to
   every filing status; only the thresholds differ."
  [0.10M 0.12M 0.22M 0.24M 0.32M 0.35M 0.37M])

(defn- brackets
  "Build a `progressive`-shaped bracket vector by pairing the seven
   `ordinary-rates` with `uppers` (six thresholds; the open top band
   is nil)."
  [uppers]
  (mapv (fn [rate upper] {:rate rate :upper upper})
        ordinary-rates
        (conj (vec uppers) nil)))

(def filing-status-brackets-2024
  "Form 1040 ordinary-income brackets by filing status — tax year
   2024 (IRS Rev. Proc. 2023-34). VERIFY against current law."
  {:single
   (brackets [11600M 47150M 100525M 191950M 243725M 609350M])
   :married-filing-jointly
   (brackets [23200M 94300M 201050M 383900M 487450M 731200M])
   :married-filing-separately
   (brackets [11600M 47150M 100525M 191950M 243725M 365600M])
   :head-of-household
   (brackets [16550M 63100M 100500M 191950M 243700M 609350M])})

(def standard-deduction-2024
  "Form 1040 standard deduction by filing status — tax year 2024 (IRS
   Rev. Proc. 2023-34). VERIFY against current law. The additional
   standard deduction for age 65+/blind is not modelled here — a
   consumer who itemizes or claims it supplies their own deduction
   `:base-transform`."
  {:single                    14600M
   :married-filing-jointly    29200M
   :married-filing-separately 14600M
   :head-of-household         21900M})

(def filing-statuses
  "The closed set of Form 1040 filing statuses. A qualifying
   surviving spouse files on the married-filing-jointly schedule, so
   no separate table is needed."
  (set (keys filing-status-brackets-2024)))

(defn- resolve-filing-status
  "The filing status from `ctx :tax-unit`, defaulting to `:single`
   (the most conservative single-earner schedule). Throws on an
   unknown status rather than silently mis-taxing."
  [ctx]
  (let [fs (get-in ctx [:tax-unit :filing-status] :single)]
    (when-not (contains? filing-statuses fs)
      (throw (ex-info "us-1040: unknown :filing-status"
                      {:filing-status fs :expected filing-statuses})))
    fs))

(defn- form-1040-tax
  "The Form 1040 ordinary-income tax. `taxable` is taxable income
   AFTER the standard deduction has been applied — see `us-1040-tax`
   for where the deduction is taken. Selects the bracket table by the
   `:filing-status` carried in `ctx :tax-unit` and runs it through the
   shared bracket folder."
  ^java.math.BigDecimal [^java.math.BigDecimal taxable ctx]
  (ts/apply-schedule
   (ts/progressive (get filing-status-brackets-2024 (resolve-filing-status ctx)))
   taxable))

(defn- us-1040-tax
  "The full Form 1040 schedule `fn` — `(fn [base ctx])`. `base` is
   gross income (the marginalized aggregate, after any consumer
   `:base-transform`). The standard deduction for the filing status is
   subtracted here UNLESS the `:tax-unit` carries
   `{:itemized? true}` — in which case the consumer is expected to
   have already netted their itemized deductions via the `:inputs`
   `:base-transform`, and only the bracket schedule is applied. The
   taxable base is floored at zero."
  ^java.math.BigDecimal [^java.math.BigDecimal base ctx]
  (let [fs        (resolve-filing-status ctx)
        itemized? (get-in ctx [:tax-unit :itemized?] false)
        deduction (if itemized? 0M (get standard-deduction-2024 fs))
        taxable   (max 0M (- base deduction))]
    (form-1040-tax taxable ctx)))

(defn us-personal-income-tax-provider
  "US federal personal income tax (Form 1040) provider. The seven
   IRC §1 ordinary-income brackets, with the bracket table AND the
   standard deduction selected by the filing status carried in
   `context :inputs :tax-unit` as `{:filing-status <kw>}` — one of
   `filing-statuses`; absent ⇒ `:single`.

   By default the provider subtracts the filing-status standard
   deduction. A taxpayer who itemizes passes
   `:tax-unit {:filing-status … :itemized? true}` and supplies the
   itemized total via the `:inputs` `:base-transform` — the provider
   then runs the brackets on whatever taxable income it is handed.

   Tax credits (Child Tax Credit, EITC, education credits, …) ride
   `context :inputs` as `:credits`; this is federal-only — state
   personal income tax is a separate per-state provider.

   Config: no required keys."
  [_]
  (pit/personal-income-tax-provider
   {:id        :us-1040
    :schedule  {:schedule/type :formula :fn us-1040-tax}
    :authority :us-irs
    :commodity :USD
    :statute   "IRC §1"}))
