(ns kontor.l10n-cn.period-tax-provider
  "Chinese period taxes as kontor `PeriodTaxProvider`s (ADR-099;
   research notes 103 / 104). Two taxes:

   - Enterprise Income Tax (企业所得税, EIT) — a flat 25 % standard
     rate; 15 % for a qualified High / New-Technology Enterprise
     (HNTE). Taxable income is accounting profit adjusted by tax
     rules — the adjustment rides `context :inputs` as a
     `:base-transform`. The small-low-profit-enterprise reduced rates
     select the rate from a taxpayer attribute too; pass an explicit
     `:rate` for those.

   - Individual Income Tax (个人所得税, IIT) on comprehensive income
     (综合所得) — a 7-bracket annual progressive schedule. This
     provider computes the ANNUAL RECONCILIATION (年度汇算清缴): the
     standard ¥60,000 basic deduction and the six special additional
     deductions ride `context :inputs` as a `:base-transform`; the
     cumulative monthly withholding the payroll layer already
     computed rides `:inputs :prepaid` as a credit (see the
     abstraction note on `cn-iit-provider`)."
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

(def eit-standard-rate
  "EIT standard rate — flat 25 % (企业所得税法 §4)."
  0.25M)

(def eit-hnte-rate
  "EIT reduced rate for a High / New-Technology Enterprise — 15 %."
  0.15M)

(defn cn-eit-provider
  "CN Enterprise Income Tax provider. Config:
     :hnte? — true for the High / New-Technology Enterprise 15 % rate
     :rate  — optional explicit override (small-low-profit regimes)"
  [{:keys [hnte? rate]}]
  (cit/corporate-income-tax-provider
   {:id        :cn-eit
    :rate      (or rate (if hnte? eit-hnte-rate eit-standard-rate))
    :authority :cn-tax
    :commodity :CNY
    :statute   "中华人民共和国企业所得税法"}))

;; ============================================================================
;; 个人所得税 — Individual Income Tax on comprehensive income (综合所得)
;; ============================================================================
;;
;; This provider models the ANNUAL RECONCILIATION (年度汇算清缴 — the once-a-year
;; settlement an individual files between 1 Mar and 30 Jun for the prior year).
;; The MONTHLY cumulative-withholding computation (累计预扣法) stays in the
;; payroll layer (note 103 §9-E) — it is engine/employer-authoritative and not a
;; period-tax return. The annual reconciliation reconciles that withholding
;; against the true annual liability: the consumer feeds the cumulative
;; withholding as `:inputs :prepaid` and the provider records it as a credit so
;; the residual (`:gross-liability − prepaid`) is the refund-or-top-up.

(def iit-basic-deduction
  "The standard basic deduction (基本减除费用) against comprehensive
   income — ¥60,000/year (¥5,000/month), 个人所得税法 §6. Verify
   against current law."
  60000M)

(def iit-comprehensive-income-brackets
  "个人所得税 — the 7-bracket ANNUAL progressive rate table for
   comprehensive income (综合所得适用, 个人所得税税率表一). `:upper`
   bounds are TAXABLE income (应纳税所得额 — comprehensive income
   already net of the basic + special additional deductions).
   Rates 3/10/20/25/30/35/45 %. Verify against current law."
  [{:rate 0.03M :upper 36000M}
   {:rate 0.10M :upper 144000M}
   {:rate 0.20M :upper 300000M}
   {:rate 0.25M :upper 420000M}
   {:rate 0.30M :upper 660000M}
   {:rate 0.35M :upper 960000M}
   {:rate 0.45M :upper nil}])

(defn iit-comprehensive-base-transform
  "Build the `:base-transform` that turns gross comprehensive income
   (综合所得) into taxable income (应纳税所得额): subtract the standard
   ¥60,000 basic deduction and every supplied special additional
   deduction (专项附加扣除 — children's education, continuing
   education, major medical, mortgage interest, housing rent, elderly
   care). `special-additional-deductions` is a seq of BigDecimal
   annual amounts the consumer has already substantiated.

   Other statutory subtractions of the same shape — 专项扣除 (the
   employee social-insurance + housing-fund contributions) and 依法
   确定的其他扣除 — can be passed in the same seq; they are arithmetic
   peers of the special additional deductions, so the substrate does
   not need to distinguish them. `apply-base-transform` floors nothing
   itself, but `PersonalIncomeTaxProvider` runs the schedule through
   `(max 0M …)`, so an over-deduction yields zero tax, not a credit."
  [special-additional-deductions]
  {:transform/type :adjustments
   :additions      []
   :deductions     (into [iit-basic-deduction]
                         (map bigdec special-additional-deductions))})

(defn cn-iit-provider
  "CN Individual Income Tax (个人所得税) on comprehensive income
   (综合所得 — wages & salaries, labour-service remuneration,
   author's remuneration, royalties) — the ANNUAL RECONCILIATION
   provider (年度汇算清缴). A 7-bracket annual progressive schedule
   (`iit-comprehensive-income-brackets`).

   The base-selector marginalizes (σ_E) the individual's income
   postings into gross comprehensive income; the standard ¥60,000
   basic deduction + the six special additional deductions ride
   `context :inputs` as a `:base-transform` — build it with
   `iit-comprehensive-base-transform`.

   Monthly cumulative withholding (累计预扣) feeds the reconciliation
   via `:inputs :prepaid` — it populates the component's `:prepaid`,
   and `kontor.period-tax-provider/balance` yields the residual
   (refund-or-top-up). The liability stays the full annual IIT. (The
   `:inputs :prepaid` path was added to the generic
   `PersonalIncomeTaxProvider` in the note-104 Stage-1 synthesis —
   ADR-099 addendum 3 — closing the gap this provider first surfaced.)

   Config: takes no options today (the schedule is fixed); accepts a
   map for forward-compatibility and l10n-pattern symmetry."
  [_]
  (pit/personal-income-tax-provider
   {:id        :cn-iit
    :schedule  (ts/progressive iit-comprehensive-income-brackets)
    :authority :cn-tax
    :commodity :CNY
    :statute   "中华人民共和国个人所得税法 §3 §6"}))
