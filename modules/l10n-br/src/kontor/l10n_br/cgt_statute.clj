(ns kontor.l10n-br.cgt-statute
  "BR capital-gains tax — Ganho de Capital + Renda Variável — encoded
   as `kontor.tax.statute` data per ADR-101. 

   The BR CGT regime splits sharply across THREE orthogonal lanes
:

   - **Lane A — PF ganho de capital** on real assets and unlisted
     participations — the FOUR-bracket progressive ladder
     15 / 17.5 / 20 / 22.5 %, Lei 13.259/2016 art. 21. Paid via
     DARF 4600, due the last business day of the month following the
     disposal.
   - **Lane B — PF renda variável** on B3 — 15 % swing-trade
     (ações à vista) + 20 % day-trade + 20 % FII, with two distinct
     monthly aggregate isenções (R$ 35k pequeno-valor for real assets,
     R$ 20k for ações swing only). Lei 11.033/2004 art. 2-3. Broker
     IRRF (0.005 % swing dedo-duro, 1 % day-trade) is a prepayment.
   - **Lane C — PJ** — capital gains fold into IRPJ + CSLL at the
     34 % combined rate (Lucro Real); Lucro Presumido has a different
     presumption base — V1 supports only Lucro Real.

   Per, the four-bracket PF ladder is recorded as
   `:parameter-bracket`s (ADR-101 date-keyed history). The two monthly
   aggregate isenções (R$ 35k + R$ 20k), the two flat B3 rates (15 %
   swing / 20 % day-trade / 20 % FII), and the IRRF dedo-duro rates
   are scalar parameters. PL 1087/2025 (Lei 15.270/2025) — the
   10 % IRPFM minimum tax effective 2026-01-01 — EXPLICITLY CARVES
   OUT capital gains; no CGT-provider impact.

   The art. 39 residence-reinvestment exemption is condition-rich
   (`:residence?`, `:rollover-into-asset`, 180-day deadline) and the
   monthly aggregate-isenção logic is a provider-side fold (a single
   disposal's eligibility depends on others in the same month) — so
 both stay record-shaped in the provider; only
   the scalar / bracket parameters live here as statute data.

   Citations point at planalto.gov.br (Brazilian government legal
   portal — the canonical primary source) and RFB Instruções
   Normativas at normas.receita.fazenda.gov.br."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "BR CGT parameter definitions. Scalar values live in
   `parameter-values`; the four-bracket PF ladder lives in
   `parameter-brackets` (a `:bracket-scale` parent)."
  [;; --- Lane A — PF ganho de capital — the four-bracket ladder -------------
   {:kontor.parameter/code         "BR.CGT.PF.ganho-capital-brackets"
    :kontor.parameter/label        "PF ganho de capital — tabela progressiva (Lei 13.259/2016 art. 21)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2016/lei/l13259.htm"}

   ;; --- Lane A — pequeno-valor R$ 35k monthly aggregate exemption ----------
   {:kontor.parameter/code         "BR.CGT.PF.pequeno-valor-cap"
    :kontor.parameter/label        "PF pequeno-valor — monthly aggregate sale-price isenção (Lei 9.250/95 art. 22)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9250.htm"}

   ;; --- Lane B — PF renda variável swing-trade rate ------------------------
   {:kontor.parameter/code         "BR.CGT.PF.renda-variavel.swing-rate"
    :kontor.parameter/label        "PF renda variável — swing-trade rate (Lei 11.033/2004 art. 2)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   ;; --- Lane B — PF renda variável day-trade rate --------------------------
   {:kontor.parameter/code         "BR.CGT.PF.renda-variavel.day-rate"
    :kontor.parameter/label        "PF renda variável — day-trade rate (Lei 11.033/2004 art. 2)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   ;; --- Lane B — R$ 20k bolsa swing isenção --------------------------------
   {:kontor.parameter/code         "BR.CGT.PF.bolsa-swing-cap"
    :kontor.parameter/label        "PF bolsa swing — monthly aggregate sale-price isenção (Lei 11.033/2004 art. 3 I)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   ;; --- Lane B — IRRF dedo-duro swing rate (broker prepayment) -------------
   {:kontor.parameter/code         "BR.CGT.PF.irrf.swing-rate"
    :kontor.parameter/label        "IRRF dedo-duro — swing-trade broker withholding (IN-RFB-1585/2015 art. 63)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=70004"}

   ;; --- Lane B — IRRF dedo-duro day-trade rate -----------------------------
   {:kontor.parameter/code         "BR.CGT.PF.irrf.day-rate"
    :kontor.parameter/label        "IRRF dedo-duro — day-trade broker withholding (IN-RFB-1585/2015 art. 63)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=70004"}

   ;; --- Lane A — art. 39 residence-reinvest window (180 days) --------------
   {:kontor.parameter/code         "BR.CGT.PF.art39-reinvest-days"
    :kontor.parameter/label        "PF art. 39 residence-reinvestment window (days)"
    :kontor.parameter/jurisdiction :br
    :kontor.parameter/unit         :days
    :kontor.parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2005/lei/l11196.htm#art39"}])

;; ============================================================================
;; Parameter values — scalar values with their statutory effective windows
;; ============================================================================

(def parameter-values
  "BR CGT scalar parameter values. The four-bracket PF ladder is in
   `parameter-brackets` (separate, child rows on the bracket-scale
   parent)."
  [;; --- PF pequeno-valor R$ 35k monthly cap (Lei 9.250/95 art. 22) ---------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.pequeno-valor-cap"]
    :kontor.parameter-value/effective-from #inst "1996-01-01"
    :kontor.parameter-value/decimal-value  35000M
    :kontor.parameter-value/citation       "Lei 9.250/1995 art. 22 — R$ 35 000 monthly aggregate price isenção"}

   ;; --- PF renda variável swing 15 % (Lei 11.033/2004 art. 2) -------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.renda-variavel.swing-rate"]
    :kontor.parameter-value/effective-from #inst "2005-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "Lei 11.033/2004 art. 2 II — 15 % swing-trade rate"}

   ;; --- PF renda variável day-trade 20 % (Lei 11.033/2004 art. 2) ----------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.renda-variavel.day-rate"]
    :kontor.parameter-value/effective-from #inst "2005-01-01"
    :kontor.parameter-value/decimal-value  0.20M
    :kontor.parameter-value/citation       "Lei 11.033/2004 art. 2 § 1 — 20 % day-trade rate"}

   ;; --- PF bolsa swing R$ 20k monthly cap (Lei 11.033/2004 art. 3 I) -------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.bolsa-swing-cap"]
    :kontor.parameter-value/effective-from #inst "2005-01-01"
    :kontor.parameter-value/decimal-value  20000M
    :kontor.parameter-value/citation       "Lei 11.033/2004 art. 3 I — R$ 20 000 monthly aggregate isenção for ações swing"}

   ;; --- IRRF dedo-duro swing 0.005 % --------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.irrf.swing-rate"]
    :kontor.parameter-value/effective-from #inst "2005-01-01"
    :kontor.parameter-value/decimal-value  0.00005M
    :kontor.parameter-value/citation       "IN-RFB-1585/2015 art. 63 II — 0,005 % swing broker withholding"}

   ;; --- IRRF dedo-duro day-trade 1 % --------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.irrf.day-rate"]
    :kontor.parameter-value/effective-from #inst "2005-01-01"
    :kontor.parameter-value/decimal-value  0.01M
    :kontor.parameter-value/citation       "IN-RFB-1585/2015 art. 63 I — 1 % day-trade broker withholding"}

   ;; --- art. 39 residence-reinvest window — 180 days ----------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "BR.CGT.PF.art39-reinvest-days"]
    :kontor.parameter-value/effective-from #inst "2005-11-21"
    :kontor.parameter-value/decimal-value  180M
    :kontor.parameter-value/citation       "Lei 11.196/2005 art. 39 — 180-day reinvestment window"}])

;; ============================================================================
;; Parameter brackets — the four-bracket PF ladder
;; ============================================================================

(def parameter-brackets
  "PF ganho-capital brackets — Lei 13.259/2016 art. 21, effective
   2017-01-01. Each row is a single bracket; the schedule walks them
   in `:index` order. The `:upper` carries the cumulative ceiling (not
   the bracket width) so this maps directly to
   `kontor.tax.tax-schedule/progressive` — first bracket up to R$ 5M,
   second up to R$ 10M, third up to R$ 30M, fourth open."
  [{:kontor.parameter-bracket/parameter       [:kontor.parameter/code "BR.CGT.PF.ganho-capital-brackets"]
    :kontor.parameter-bracket/index            0
    :kontor.parameter-bracket/rate             0.15M
    :kontor.parameter-bracket/upper            5000000M
    :kontor.parameter-bracket/effective-from   #inst "2017-01-01"}
   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "BR.CGT.PF.ganho-capital-brackets"]
    :kontor.parameter-bracket/index            1
    :kontor.parameter-bracket/rate             0.175M
    :kontor.parameter-bracket/upper            10000000M
    :kontor.parameter-bracket/effective-from   #inst "2017-01-01"}
   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "BR.CGT.PF.ganho-capital-brackets"]
    :kontor.parameter-bracket/index            2
    :kontor.parameter-bracket/rate             0.20M
    :kontor.parameter-bracket/upper            30000000M
    :kontor.parameter-bracket/effective-from   #inst "2017-01-01"}
   ;; The open top band — `:kontor.parameter-bracket/upper` is omitted per the
   ;; schema's "Absent ⇒ open top band" convention (bigdec attr cannot
   ;; carry nil).
   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "BR.CGT.PF.ganho-capital-brackets"]
    :kontor.parameter-bracket/index            3
    :kontor.parameter-bracket/rate             0.225M
    :kontor.parameter-bracket/effective-from   #inst "2017-01-01"}])

;; ============================================================================
;; Provisions — kept empty for v1
;; ============================================================================

(def provisions
  "BR CGT v1 ships ZERO provisions — every regime call lives in the
   provider, 39 residence-reinvestment
   exemption is condition-rich (depends on `:residence?` +
   `:rollover-into-asset` + 180-day window — none of which a literal
   `:kontor.provision/condition` can express today without per-disposal
   threading); the monthly aggregate-isenção logic is a fold across
   disposals; and the four-bracket schedule is read directly via
   `parameter-brackets-at`. A future iteration may migrate the art. 39
   residence-
   reinvest rule into a `:provision` once the conditions vocabulary
   covers ref-equality on rollover-into-asset + deadline arithmetic."
  [])

;; ============================================================================
;; Install! — transact parameters + values + brackets + provisions
;; ============================================================================

(defn install!
  "Install BR CGT statute (parameters + values + brackets) into `conn`.
   Idempotent — `:kontor.parameter/code` is the unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn parameter-brackets)
  (when (seq provisions)
    (d/transact conn provisions)))
