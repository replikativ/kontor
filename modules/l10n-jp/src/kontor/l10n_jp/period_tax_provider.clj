(ns kontor.l10n-jp.period-tax-provider
  "Japanese personal income tax — as kontor `PeriodTaxProvider`s
   (ADR-099; research note 104, Stage 1 of the tax-completion program).

   Japan levies personal income tax through TWO governments on the
   same income, and this namespace ships one provider for each:

   - **`jp-income-tax-provider`** — the NATIONAL income tax (所得税).
     A 7-bracket progressive schedule (5/10/20/23/33/40/45 %) on
     taxable income (課税所得金額), which is gross income less the
     employment-income deduction (給与所得控除) and personal
     deductions (所得控除). The employment-income deduction rides
     `:inputs :base-transform`. The 2.1 % reconstruction surtax
     (復興特別所得税) — a tax ON the national income tax — is a
     computed `:surtax-fn`.

   - **`jp-inhabitant-tax-provider`** — the LOCAL inhabitant tax
     (住民税): a roughly flat ~10 % income levy (所得割 — 6 %
     municipal 市町村民税 + 4 % prefectural 道府県民税) plus a small
     fixed per-capita levy (均等割). CRITICAL: the inhabitant tax is
     assessed on the PRIOR calendar year's income — the `:period` is
     the assessment/billing year but the income base comes from
     `context :base-period` (note 102 §9-E).

   ## Audit — see the ns-level ADR note in research 104

   The two taxes are modelled as TWO SEPARATE PROVIDERS (not two
   `:components` on one `TaxReturnFacts`) because they (a) have
   different taxing authorities (`:jp-nta` vs `:jp-municipality`),
   (b) are assessed on different periods (current vs prior year) and
   (c) are filed / billed independently — the national tax via the
   year-end adjustment / 確定申告, the inhabitant tax via a municipal
   assessment the following June. A single `period-tax-facts` call
   cannot honour two different base windows, so they cannot share one
   provider invocation. The substrate handles this cleanly: a
   consumer registers both providers and calls each with the
   appropriate `context`. See the structured finding in note 104.

   All statutory figures below are 2024/2025-law constants bundled
   with a verify caveat — the established l10n pattern."
  (:require [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; National income tax (所得税) — the 7-bracket schedule
;; ============================================================================

(def income-tax-brackets
  "所得税 — the national income-tax brackets, applied to taxable
   income 課税所得金額 (gross less the employment-income deduction
   and personal deductions). Seven bands, in JPY. Verify against
   current law — National Tax Agency 所得税法 §89; these figures have
   been stable since the 2015 introduction of the 45 % top band.

     5 %   on income up to            ¥1,950,000
     10 %  on                ¥1,950,001 – ¥3,300,000
     20 %  on                ¥3,300,001 – ¥6,950,000
     23 %  on                ¥6,950,001 – ¥9,000,000
     33 %  on                ¥9,000,001 – ¥18,000,000
     40 %  on               ¥18,000,001 – ¥40,000,000
     45 %  on        anything over     ¥40,000,000"
  [{:rate 0.05M :upper 1950000M}
   {:rate 0.10M :upper 3300000M}
   {:rate 0.20M :upper 6950000M}
   {:rate 0.23M :upper 9000000M}
   {:rate 0.33M :upper 18000000M}
   {:rate 0.40M :upper 40000000M}
   {:rate 0.45M :upper nil}])

;; ============================================================================
;; Employment-income deduction (給与所得控除) — the :base-transform
;; ============================================================================

(defn employment-income-deduction
  "給与所得控除 — the standard employment-income deduction subtracted
   from gross salary (収入金額) to reach employment income (給与所得).
   2020-reform schedule; verify against current law (所得税法 §28).

     gross ≤ ¥1,625,000             → ¥550,000 (the floor)
     ¥1,625,001 – ¥1,800,000        → gross×40 % − ¥100,000
     ¥1,800,001 – ¥3,600,000        → gross×30 % + ¥80,000
     ¥3,600,001 – ¥6,600,000        → gross×20 % + ¥440,000
     ¥6,600,001 – ¥8,500,000        → gross×10 % + ¥1,100,000
     gross > ¥8,500,000             → ¥1,950,000 (the cap)"
  ^java.math.BigDecimal [^java.math.BigDecimal gross]
  (cond
    (<= gross 1625000M) 550000M
    (<= gross 1800000M) (- (* gross 0.40M) 100000M)
    (<= gross 3600000M) (+ (* gross 0.30M) 80000M)
    (<= gross 6600000M) (+ (* gross 0.20M) 440000M)
    (<= gross 8500000M) (+ (* gross 0.10M) 1100000M)
    :else               1950000M))

(defn employment-income-base-transform
  "Build the `:base-transform` that turns gross salary into taxable
   income: subtract the 給与所得控除 and any personal deductions
   (所得控除 — the basic ¥480,000 deduction, dependents, social-
   insurance premiums, etc.). `personal-deductions` is the BigDecimal
   total of 所得控除 the consumer supplies — out-of-books facts that
   ride `context :inputs` (note 102 §7).

   Expressed as a `:formula` transform because the
   employment-income deduction is itself a function of the base."
  ([] (employment-income-base-transform 0M))
  ([^java.math.BigDecimal personal-deductions]
   {:transform/type :formula
    :fn (fn [^java.math.BigDecimal gross]
          (max 0M (- gross
                     (employment-income-deduction gross)
                     personal-deductions)))}))

;; ============================================================================
;; Reconstruction surtax (復興特別所得税) — a computed surtax-fn
;; ============================================================================

(def reconstruction-surtax-rate
  "復興特別所得税 — the Special Reconstruction Income Tax: 2.1 % OF
   the national income tax (NOT 2.1 % of income). Levied 2013–2037
   to fund Tohoku-earthquake reconstruction. Verify against current
   law (東日本大震災復興特別措置法)."
  0.021M)

(def reconstruction-surtax-adjustment
  "復興特別所得税 as an adjustment-layer item (note 105) — a surtax of
   2.1 % of the running national income tax."
  {:code   :reconstruction-surtax
   :label  "復興特別所得税 (Special Reconstruction Income Tax)"
   :op     :surtax
   :amount (fn [ctx] (ts/surtax-on reconstruction-surtax-rate
                                   (:running ctx)))})

(defn jp-income-tax-provider
  "JP NATIONAL personal income tax — 所得税 — provider. The 7-band
   progressive schedule (所得税法 §89) plus the 2.1 % reconstruction
   surtax (a computed surtax in the adjustment layer). The
   employment-income deduction (給与所得控除) and personal deductions
   ride `context :inputs` as a `:base-transform` — see
   `employment-income-base-transform`."
  [_]
  (pit/personal-income-tax-provider
   {:id          :jp-shotokuzei
    :schedule    (ts/progressive income-tax-brackets)
    :authority   :jp-nta
    :commodity   :JPY
    :statute     "所得税法 §89 + 復興特別所得税 (東日本大震災復興特別措置法)"
    :adjustments [reconstruction-surtax-adjustment]}))

;; ============================================================================
;; Local inhabitant tax (住民税) — prior-year base via :base-period
;; ============================================================================

(def inhabitant-tax-income-rate
  "住民税 所得割 — the income-proportional part of the inhabitant
   tax: a flat 10 % nationwide standard rate, split 6 % municipal
   (市町村民税) + 4 % prefectural (道府県民税). Municipalities may set
   non-standard rates; 10 % is the統一 standard. Verify against
   current law (地方税法)."
  0.10M)

(def inhabitant-tax-municipal-rate
  "市町村民税 所得割 — municipal share of the inhabitant income levy.
   Standard 6 %."
  0.06M)

(def inhabitant-tax-prefectural-rate
  "道府県民税 所得割 — prefectural share of the inhabitant income
   levy. Standard 4 %."
  0.04M)

(def inhabitant-tax-per-capita-levy
  "住民税 均等割 — the flat per-capita component of the inhabitant
   tax, a fixed yen amount independent of income: ¥3,500 municipal +
   ¥1,500 prefectural = ¥5,000, plus the separate ¥1,000 national
   forest-environment tax (森林環境税) collected together with it —
   ¥6,000 in total from FY2024. Verify against current law; the
   ¥1,000 reconstruction add-on that ran 2014–2023 was replaced by
   the forest tax."
  6000M)

(defn inhabitant-tax-schedule
  "住民税 as a `:formula` schedule: the 10 % 所得割 on prior-year
   taxable income PLUS the fixed ¥6,000 均等割 per-capita levy. The
   per-capita levy is a flat addition, so the whole thing is not a
   pure `:flat` schedule — `:formula` carries it faithfully."
  []
  {:schedule/type :formula
   :fn (fn [^java.math.BigDecimal taxable-income _ctx]
         (if (pos? taxable-income)
           (+ (* taxable-income inhabitant-tax-income-rate)
              inhabitant-tax-per-capita-levy)
           ;; below the 均等割 non-taxation threshold the levy is
           ;; waived; a precise threshold is municipality-specific
           ;; and rides :inputs when a consumer needs it.
           0M))})

(defrecord JpInhabitantTaxProvider [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period base-period inputs] :as context}]
    ;; 住民税 is assessed in `:period` (the billing year, June–May)
    ;; on income earned in `:base-period` (the PRIOR calendar year).
    ;; The base-selector must marginalize over the base window, NOT
    ;; the liability window — this is the whole reason `:base-period`
    ;; exists (note 102 §9-E). Fall back to `:period` if no separate
    ;; base window is supplied.
    (let [base-window (or base-period period)
          income      (pit/gross-income (assoc context :period base-window)
                                        commodity)
          transform   (:base-transform inputs)
          taxable     (ts/apply-base-transform transform (:amount income))
          schedule    (inhabitant-tax-schedule)
          gross       (max 0M (ts/apply-schedule schedule taxable nil))
          income-levy (if (pos? taxable)
                        (* taxable inhabitant-tax-income-rate)
                        0M)
          per-capita  (if (pos? taxable) inhabitant-tax-per-capita-levy 0M)]
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
          :credits         []
          :surtaxes        []
          :liability       (money/money gross commodity)
          :prepaid         (money/zero commodity)
          :provenance      {:provider-id id :statute statute
                            :base-period base-window}
          :line-items
          [{:line :gross-income :label "前年中の収入金額 (prior-year income)"
            :value income}
           {:line :taxable-income
            :label "前年中の課税所得金額 (prior-year taxable income)"
            :value (money/money taxable commodity)}
           {:line :inhabitant-income-levy
            :label "所得割 (income-proportional levy, 10%)"
            :value (money/money income-levy commodity)}
           {:line :inhabitant-per-capita-levy
            :label "均等割 (per-capita levy)"
            :value (money/money per-capita commodity)}]}]}))))

(defn jp-inhabitant-tax-provider
  "JP LOCAL inhabitant tax — 住民税 — provider. A flat 10 % 所得割
   plus the fixed ¥6,000 均等割 per-capita levy. Assessed on the
   PRIOR year's income: the caller passes the billing year as
   `:period` and the prior calendar year as `:base-period`.

   Separate from `jp-income-tax-provider` deliberately — different
   authority (`:jp-municipality`), different base window. See the
   ns docstring's audit note."
  [_]
  (->JpInhabitantTaxProvider :jp-juminzei
                             :jp-municipality
                             :JPY
                             "地方税法 (住民税 所得割 + 均等割)"))
