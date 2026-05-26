(ns kontor.l10n-jp.investment-income-statute
  "JP investment-income tax — 配当所得 + 利子所得 — encoded as
   `kontor.statute` data per ADR-101. Research note 151.

   This namespace is the COMPANION to the existing JP CGT statute
   (`cgt-statute`): the 復興特別所得税 2.1 % surtax provision is
   REUSED from there verbatim (the provider invokes `apply-provisions`
   with `:concept :surtax :jurisdiction :jp` and the `JP-FUKKO-§13`
   provision fires). The investment-income statute itself adds only:

   - **Rate parameters** for the 20.315 % composite (15 % national +
     5 % local, listed-shares) — distinct codes from the CGT rate
     parameters because the statutory hook differs (措置法 §9-3 vs
     §37-10) even though the numbers coincide for v1.
   - **配当控除 (haitō-kōjo)** rate ladder per 所得税法 §92 — the
     two-tier (低 / 高) 10 % / 5 % credit against national income tax
     on the dividend slice ≤ ¥10M / > ¥10M, plus the 投資信託 half-
     rate variant.
   - **大口株主 3 % threshold** parameter (措置法 §8-4) — informational
     value; the cliff itself is handled provider-side via the election-
     validator (see provider docstring §5.2 in note 151) because it
     is per-source consumer-supplied data, not a tax-level statute.
   - **§95 foreign-tax-credit** convention — applied via the adjustment
     layer; no separate parameter (per-country WHT amounts come from
     `:inputs :jp-foreign-tax-paid`).

   The 復興 2.1 % surtax provision is REUSED from the JP CGT statute —
   when both statutes are installed, the same `JP-FUKKO-§13-
   reconstruction-surtax` provision fires for ANY component whose ctx
   includes `:pass :national`. The investment-income provider's
   national-rate pass sets this exact key, so the surtax fires
   without re-registration.

   Citations point at www.nta.go.jp (NTA — National Tax Agency) for
   the No.### Tsutatsu numbers and at elaws.e-gov.go.jp for the
   underlying 所得税法 / 租税特別措置法 articles. Research note 151."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "JP investment-income parameter definitions. Values live in
   `parameter-values`. The 復興 rate parameter is REUSED from
   cgt-statute (`JP.CGT.reconstruction-surtax-rate`); we do NOT
   re-declare it here."
  [;; --- Listed-dividend rates (措置法 §9-3) ---------------------------------
   {:parameter/code         "JP.InvIncome.listed.national-rate"
    :parameter/label        "Listed dividend — national income-tax rate (措置法 §9-3)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_9_3"}
   {:parameter/code         "JP.InvIncome.listed.local-rate"
    :parameter/label        "Listed dividend — inhabitants (local) rate"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=325AC0000000226"}

   ;; --- Major-shareholder (≥3 %) WHT — no 復興 reduction --------------------
   ;; A 3 %+ shareholder cannot use 申告不要 / 申告分離 — must use 総合課税.
   ;; The paying agent's WHT on these dividends is 20.42 % (20 % nat × 1.021
   ;; 復興), NOT 20.315 %, because the base is the un-reduced 20 % rate.
   {:parameter/code         "JP.InvIncome.major-shareholder.national-rate"
    :parameter/label        "≥3 % shareholder dividend — national base WHT rate (措置法 §9-3-2 N/A)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1330.htm"}
   {:parameter/code         "JP.InvIncome.major-shareholder.threshold"
    :parameter/label        "大口株主 cliff threshold — ≥3 % of issued shares (措置法 §8-4)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_8_4"}

   ;; --- 配当控除 (所得税法 §92) — standard cash dividend ---------------------
   {:parameter/code         "JP.InvIncome.haitō-kōjo.standard-rate"
    :parameter/label        "配当控除 standard rate — 10 % on dividend slice ≤ ¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=340AC0000000033#Mp-At_92"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.high-income-rate"
    :parameter/label        "配当控除 high-income rate — 5 % on dividend slice > ¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=340AC0000000033#Mp-At_92"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.threshold"
    :parameter/label        "配当控除 income threshold — ¥10,000,000 (課税総所得金額)"
    :parameter/jurisdiction :jp
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-standard-rate"
    :parameter/label        "配当控除 standard rate — 2.8 % on dividend slice ≤ ¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-high-income-rate"
    :parameter/label        "配当控除 high-income rate — 1.4 % on dividend slice > ¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   ;; Investment-trust variants — half / quarter of the cash dividend.
   ;; Per note 151 §1.3 table: trust-domestic is HALF of cash rate;
   ;; trust-foreign is HALF of trust-domestic (quarter of cash).
   {:parameter/code         "JP.InvIncome.haitō-kōjo.trust-domestic-rate"
    :parameter/label        "配当控除 domestic investment-trust rate — 5 % ≤¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.trust-domestic-high-rate"
    :parameter/label        "配当控除 domestic investment-trust rate — 2.5 % >¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.trust-foreign-rate"
    :parameter/label        "配当控除 foreign-asset investment-trust rate — 2.5 % ≤¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.trust-foreign-high-rate"
    :parameter/label        "配当控除 foreign-asset investment-trust rate — 1.25 % >¥10M (national)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   ;; Trust × inhabitants (local) rates — half / quarter of the cash
   ;; inhabitants rate (2.8 % / 1.4 %).
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-rate"
    :parameter/label        "配当控除 domestic-trust rate — 1.4 % ≤¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-high-rate"
    :parameter/label        "配当控除 domestic-trust rate — 0.7 % >¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-rate"
    :parameter/label        "配当控除 foreign-trust rate — 0.7 % ≤¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}
   {:parameter/code         "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-high-rate"
    :parameter/label        "配当控除 foreign-trust rate — 0.35 % >¥10M (inhabitants)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"}])

;; ============================================================================
;; Parameter values
;; ============================================================================

(def parameter-values
  "JP investment-income parameter values. Stable post-2014 values; the
   復興 surtax sunsets 2037-12-31 and is governed by the CGT statute."
  [;; --- Listed-dividend rates -----------------------------------------------
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.listed.national-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "措置法 §9-3 — 上場株式等 配当 national rate 15 % from 2014"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.listed.local-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "地方税法 — 上場株式等 配当 inhabitants rate 5 % from 2014"}

   ;; --- Major-shareholder (≥3 %) -------------------------------------------
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.major-shareholder.national-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "措置法 §9-3-2 — 大口株主 (≥3 %) national WHT base 20 % (no 措置法 reduction)"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.major-shareholder.threshold"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.03M
    :parameter-value/citation       "措置法 §8-4 — 大口株主 cliff at 3 % of issued shares / voting rights"}

   ;; --- 配当控除 — cash dividend (所得税法 §92) ------------------------------
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.standard-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "所得税法 §92 — 配当控除 10 % on national-tax of dividend slice ≤ ¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.high-income-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "所得税法 §92 — 配当控除 5 % on national-tax of dividend slice > ¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.threshold"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  10000000M
    :parameter-value/citation       "所得税法 §92 — ¥10,000,000 課税総所得金額 threshold"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-standard-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.028M
    :parameter-value/citation       "地方税法 — 配当控除 2.8 % on inhabitants tax of dividend slice ≤ ¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-high-income-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.014M
    :parameter-value/citation       "地方税法 — 配当控除 1.4 % on inhabitants tax of dividend slice > ¥10M"}

   ;; --- 配当控除 — investment-trust variants (national, low + high) --------
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.trust-domestic-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "所得税法 §92 + 措置法 — 投資信託 (domestic asset) 配当控除 5 % ≤¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.trust-domestic-high-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.025M
    :parameter-value/citation       "所得税法 §92 + 措置法 — 投資信託 (domestic asset) 配当控除 2.5 % >¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.trust-foreign-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.025M
    :parameter-value/citation       "所得税法 §92 + 措置法 — 投資信託 (foreign asset) 配当控除 2.5 % ≤¥10M"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.trust-foreign-high-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.0125M
    :parameter-value/citation       "所得税法 §92 + 措置法 — 投資信託 (foreign asset) 配当控除 1.25 % >¥10M"}
   ;; --- 配当控除 — investment-trust variants (inhabitants, low + high) -----
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.014M
    :parameter-value/citation       "地方税法 — 投資信託 (domestic) 配当控除 1.4 % ≤¥10M (inhabitants)"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-high-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.007M
    :parameter-value/citation       "地方税法 — 投資信託 (domestic) 配当控除 0.7 % >¥10M (inhabitants)"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.007M
    :parameter-value/citation       "地方税法 — 投資信託 (foreign) 配当控除 0.7 % ≤¥10M (inhabitants)"}
   {:parameter-value/parameter      [:parameter/code "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-high-rate"]
    :parameter-value/effective-from #inst "1965-04-01"
    :parameter-value/decimal-value  0.0035M
    :parameter-value/citation       "地方税法 — 投資信託 (foreign) 配当控除 0.35 % >¥10M (inhabitants)"}])

;; ============================================================================
;; Provisions
;; ============================================================================

;; The investment-income statute's only ADR-101 provisions are the
;; 配当控除 family — `:credit :refundable? false` items the provider
;; folds via `apply-adjustments` AFTER the marginal national income
;; tax fires on the 総合課税 dividend slice. The 復興 surtax is
;; REUSED from the CGT statute (`JP-FUKKO-§13-reconstruction-surtax`);
;; the 3 % 大口株主 cliff and the §95 foreign-tax-credit are
;; provider-side (per-source consumer data, not a statute fold).

(def provisions
  "JP investment-income provisions. The 配当控除 (所得税法 §92) is
   encoded as a `:credit` provision with a compute-fn that computes
   the per-asset-class, threshold-gated amount. Fires only when the
   provider's ctx carries `:election :sogo` (the aggregation lane).

   The investment-trust split (domestic / foreign / J-REIT) is read
   from the dividend item's asset-class, supplied by the provider via
   ctx — the compute-fn reads `:asset-class` to pick the rate.

   The §95 foreign-tax credit is computed provider-side (per-country
   limitation requires per-source data) and applied through the same
   `apply-adjustments` adjustment-layer mechanism, NOT through this
   statute."

  [;; --------------------------------------------------------------------
   ;; 所得税法 §92 — 配当控除 standard cash dividend (low-income slice)
   ;; Fires when :election :sogo AND total taxable income ≤ ¥10M (the
   ;; threshold gate is implemented inside the compute-fn to handle the
   ;; slice-above-threshold case cleanly).
   ;; --------------------------------------------------------------------
   {:provision/code            "JP-Shotokuzeihō-§92-haitō-kōjo-national"
    :provision/jurisdiction    :jp
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "配当控除 — 所得税法 §92 dividend tax credit (national income tax)"
    :provision/citation        "https://elaws.e-gov.go.jp/document?lawid=340AC0000000033#Mp-At_92"
    :provision/effective-from  #inst "1965-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq [:election] :sogo]
                                        [:eq [:pass] :national]])
    :provision/consequence     (pr-str {:op          :credit
                                        :refundable? false
                                        :code        :jp-haitō-kōjo-national
                                        :label       "配当控除 (national) — 所得税法 §92"
                                        :amount-from :compute-fn
                                        :fn          :jp-haitō-kōjo-national})}

   {:provision/code            "JP-Chihōzeihō-haitō-kōjo-jūmin"
    :provision/jurisdiction    :jp
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "配当控除 — 地方税法 dividend tax credit (inhabitants tax)"
    :provision/citation        "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm"
    :provision/effective-from  #inst "1965-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq [:election] :sogo]
                                        [:eq [:pass] :local]])
    :provision/consequence     (pr-str {:op          :credit
                                        :refundable? false
                                        :code        :jp-haitō-kōjo-jūmin
                                        :label       "配当控除 (inhabitants) — 地方税法"
                                        :amount-from :compute-fn
                                        :fn          :jp-haitō-kōjo-jūmin})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install JP investment-income statute (parameters + provisions) into
   `conn`. Idempotent — `:parameter/code` and `:provision/code` are
   unique identity attrs.

   ASSUMES the JP CGT statute has ALREADY been installed (the
   `JP-FUKKO-§13-reconstruction-surtax` provision lives there and is
   reused). If not installed, the provider's 復興 surtax line will
   silently render as ¥0 (no provisions match the `:surtax` query)."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
