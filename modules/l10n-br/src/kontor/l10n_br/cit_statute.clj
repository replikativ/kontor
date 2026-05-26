(ns kontor.l10n-br.cit-statute
  "BR corporate income tax — IRPJ + CSLL — encoded as `kontor.statute`
   data per ADR-101. The second-to-fourth end-to-end consumer of the
   statute-as-data substrate (after DE / FR / JP / CA). Research note
   162 (the implementation blueprint) + note 121 (substrate-fit
   cross-check).

   The encoding splits cleanly along the substrate seams:

   - **Parameters** (date-keyed value history) — the statutory rates
     and thresholds: IRPJ 15 % alíquota base + 10 % adicional + R$ 20k
     monthly threshold; CSLL 9 % standard + 15 % / 20 % bank windows
     (Lei 14.183/2021 bitemporally critical); Lucro Presumido
     presumption ratios (1.6 / 8 / 16 / 32 % for IRPJ, 12 / 32 % for
     CSLL); trava-30 % loss-offset cap; JCP 50 % cap; Lucro Arbitrado
     1.20 multiplier (parameter-only, no provider compute path in v1);
     LC 224/2025 +10 % Presumido majoração slots reserved for v1.1.

   - **Regimes** (elective containers) — three: `:br-lucro-real`
     (default), `:br-lucro-presumido` (elective ≤ R$ 78M revenue,
     trimestral), `:br-lucro-arbitrado` (penalty / fallback —
     parameter-only in v1).

   - **Provisions** (per-jurisdiction rules) — 14 in v1:
       - 6 IRPJ Lucro Real base-side (multas, doações, brindes, CSLL
         addback, dividendos excluídos, trava-30 %)
       - 5 CSLL Lucro Real base-side (parallels IRPJ minus the
         CSLL-addback + IRPJ-only deductions; collapses brindes into
         doações for CSLL since the law treats both as art. 13)
       - 2 IRPJ-only deducts (PAT, Rouanet — not applicable to CSLL)
       - 1 regime-agnostic IRPJ adicional 10 % surtax (Lei 9.430 art. 4)

   - **Scoping** — provisions are scoped to one component (`:irpj` or
     `:csll`) via `:condition [:eq :component <kw>]`; further scoped
     to one regime via `:provision/regime`. The IRPJ adicional surtax
     is regime-AGNOSTIC (`:provision/regime` absent — fires under
     Real + Presumido + Arbitrado).

   The trava-30 % provisions read `:running` at fold time (the cap is
   30 % of the post-all-other-adjustments base), so they are
   late-bound compute-fns at `:priority 900` after every other
   base-adjustment.

   Citations point at planalto.gov.br (`/ccivil_03/leis/`) which is
   the Casa Civil canonical repository — Brazilian federal law is
   public-domain per Lei 9.610/1998 art. 8. Note 162 §6 documents the
   citation discipline (no text lifted from PwC / Contabilizei /
   Portal Tributário; pattern mirrors DE / FR / JP / CA in-house EPL
   work)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "BR CIT parameter definitions — one row per `:parameter/code`. Values
   live in `parameter-values` keyed by `:effective-from`."
  [;; --------------------------------------------------------------------
   ;; IRPJ — federal alíquota + adicional + threshold
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.rate"
    :parameter/label        "IRPJ alíquota base (15 %)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art3"}

   {:parameter/code         "BR.IRPJ.adicional-rate"
    :parameter/label        "IRPJ adicional 10 % sobre excesso mensal × meses-no-período"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"}

   {:parameter/code         "BR.IRPJ.adicional-threshold-mensal"
    :parameter/label        "Limite mensal R$ 20 000 para adicional IRPJ"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"}

   ;; --------------------------------------------------------------------
   ;; CSLL — federal alíquota base + bank rates (three effective windows
   ;; for the bank rate: 15 % permanente (Lei 13.169/2015) → 20 % temporária
   ;; (Lei 14.183/2021, set/2021 → dez/2024) → 15 % volta (jan/2025)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.CSLL.rate"
    :parameter/label        "CSLL alíquota base 9 % (empresa em geral)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   {:parameter/code         "BR.CSLL.rate-financial"
    :parameter/label        "CSLL alíquota majorada para instituições financeiras"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   ;; --------------------------------------------------------------------
   ;; Lucro Presumido — IRPJ + CSLL per-atividade presumption ratios
   ;; (Lei 9.249/1995 art. 15 IRPJ + art. 20 CSLL)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.presumido.ratio-comercio"
    :parameter/label        "IRPJ presunção 8 % comércio / indústria"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-servicos"
    :parameter/label        "IRPJ presunção 32 % serviços em geral"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-combustivel"
    :parameter/label        "IRPJ presunção 1,6 % revenda de combustíveis"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-transporte-passageiros"
    :parameter/label        "IRPJ presunção 16 % transporte de passageiros"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-transporte-cargas"
    :parameter/label        "IRPJ presunção 8 % transporte de cargas / hospitalar"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.CSLL.presumido.ratio-comercio"
    :parameter/label        "CSLL presunção 12 % comércio / indústria (art. 20 Lei 9249)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art20"}

   {:parameter/code         "BR.CSLL.presumido.ratio-servicos"
    :parameter/label        "CSLL presunção 32 % serviços em geral (alt. Lei 10.684/2003)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art20"}

   ;; --------------------------------------------------------------------
   ;; Lucro Real — trava dos 30 % (Lei 8.981/1995 art. 42 + Lei 9.065/1995
   ;; art. 15 IRPJ + art. 16 CSLL)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.Real.compensacao-prejuizo-cap"
    :parameter/label        "Trava dos 30 % — limite de compensação prejuízo / base negativa"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"}

   ;; --------------------------------------------------------------------
   ;; JCP — Juros sobre o Capital Próprio cap (Lei 9.249/1995 art. 9 §1)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.JCP.deducao-cap-50pct"
    :parameter/label        "JCP — 50 % do MAIOR entre lucro do período E lucros acumulados"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"}

   ;; --------------------------------------------------------------------
   ;; Lucro Arbitrado multiplier (Lei 9.430/1996 art. 27 + art. 16) —
   ;; v1 parameter only, no compute path tested
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.Arbitrado.presumido-multiplier"
    :parameter/label        "Lucro Arbitrado — Presumido × 1,20"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art27"}

   ;; --------------------------------------------------------------------
   ;; LC 224/2025 — Presumido majoração (2026 +10 % on slice > R$ 5M)
   ;; — parameter slots reserved; provision deferred to v1.1
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.presumido.lc224.majoracao"
    :parameter/label        "LC 224/2025 — +10 % Presumido sobre faixa anual > R$ 5M (v1.1)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp224.htm"}

   {:parameter/code         "BR.IRPJ.presumido.lc224.threshold"
    :parameter/label        "LC 224/2025 — faixa anual R$ 5 000 000 (v1.1)"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp224.htm"}])

(def parameter-values
  "BR CIT parameter values with their statutory effective windows. Most
   rates are stable since 1996; the bank-CSLL window (Lei 14.183/2021)
   has three rows — 15 % permanente / 20 % temporária 2021-09-01 →
   2024-12-31 / 15 % volta from 2025-01-01."
  [;; IRPJ rates — stable since 1996
   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.rate"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Lei 9.249/1995 art. 3 caput — IRPJ 15 % estável desde 1996"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.adicional-rate"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 9.430/1996 art. 4 — adicional 10 % em vigor desde 1997"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.adicional-threshold-mensal"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  20000M
    :parameter-value/citation       "Lei 9.430/1996 art. 4 — R$ 20 000 × meses-no-período"}

   ;; CSLL standard 9 % — Lei 10.637/2002 art. 37 fixed permanent 9 % from set/2003
   {:parameter-value/parameter      [:parameter/code "BR.CSLL.rate"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.09M
    :parameter-value/citation       "Lei 10.637/2002 art. 37 — CSLL 9 % padrão desde set/2003"}

   ;; CSLL bancos — three windows (note 162 §1.1 — bitemporally critical
   ;; via Lei 14.183/2021)
   {:parameter-value/parameter       [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from  #inst "2015-09-01"
    :parameter-value/effective-until #inst "2021-09-01"
    :parameter-value/decimal-value   0.15M
    :parameter-value/citation        "Lei 13.169/2015 art. 1 — 15 % bancos permanente (substituiu Lei 11.727/2008)"}

   {:parameter-value/parameter       [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from  #inst "2021-09-01"
    :parameter-value/effective-until #inst "2025-01-01"
    :parameter-value/decimal-value   0.20M
    :parameter-value/citation        "Lei 14.183/2021 + Lei 14.388/2022 — majoração temporária 20 % bancos, sunset 31-dez-2024"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from #inst "2025-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Lei 14.183/2021 sunset — bancos voltam a 15 % em 2025"}

   ;; Presumido ratios — stable since 1996
   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.08M
    :parameter-value/citation       "Lei 9.249/1995 art. 15 caput — 8 % comércio/indústria"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.32M
    :parameter-value/citation       "Lei 9.249/1995 art. 15 §1 III — 32 % serviços"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-combustivel"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.016M
    :parameter-value/citation       "Lei 9.249/1995 art. 15 §1 I — 1,6 % revenda combustíveis"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-transporte-passageiros"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.16M
    :parameter-value/citation       "Lei 9.249/1995 art. 15 §1 II — 16 % transporte passageiros"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-transporte-cargas"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.08M
    :parameter-value/citation       "Lei 9.249/1995 art. 15 caput — 8 % transporte cargas / hospitalar"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.12M
    :parameter-value/citation       "Lei 9.249/1995 art. 20 — CSLL 12 % padrão (comércio/indústria)"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.32M
    :parameter-value/citation       "Lei 9.249/1995 art. 20 I (alt. Lei 10.684/2003) — CSLL 32 % serviços"}

   ;; Trava 30 % — Lei 9.065/1995 art. 15 IRPJ + art. 16 CSLL, em vigor desde 1995
   {:parameter-value/parameter      [:parameter/code "BR.Real.compensacao-prejuizo-cap"]
    :parameter-value/effective-from #inst "1995-01-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "Lei 8.981/1995 art. 42 + Lei 9.065/1995 art. 15 — trava dos 30 %"}

   ;; JCP cap 50 % — Lei 9.249/1995 art. 9 §1, em vigor desde 1996
   {:parameter-value/parameter      [:parameter/code "BR.JCP.deducao-cap-50pct"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "Lei 9.249/1995 art. 9 §1 — MAIOR entre 50 % do lucro do período E 50 % dos lucros acumulados"}

   ;; Arbitrado multiplier — Lei 9.430/1996 art. 27
   {:parameter-value/parameter      [:parameter/code "BR.Arbitrado.presumido-multiplier"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  1.20M
    :parameter-value/citation       "Lei 9.430/1996 art. 27 — Arbitrado = Presumido × 1,20"}])

;; ============================================================================
;; Regimes (elective containers) — three; v1 happy path covers Real + Presumido
;; ============================================================================

(def regimes
  "BR CIT regimes — `:provision/regime` ref-targets. Lucro Real is the
   default; Lucro Presumido the elective ≤ R$ 78M / quarterly path;
   Lucro Arbitrado is parameter-only in v1 (no compute path)."
  [{:regime/code         :br-lucro-real
    :regime/label        "Lucro Real (apuração anual ou trimestral)"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art1"}

   {:regime/code         :br-lucro-presumido
    :regime/label        "Lucro Presumido (receita ≤ R$ 78M/ano, trimestral)"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art25"}

   {:regime/code         :br-lucro-arbitrado
    :regime/label        "Lucro Arbitrado (Presumido × 1,20) — v1 parameter-only"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art27"}])

;; ============================================================================
;; Provisions — BR CIT statute as :provision data
;; ============================================================================

(def provisions
  "BR CIT statutory provisions encoded for the `kontor.statute`
   evaluator. Conditions reference `:component` (set by the provider
   on each per-component pass — `:irpj` or `:csll`) and use vector
   fact-keys `[:inputs <fact>]` to read consumer-supplied facts —
   gating each provision on its driver fact so absent ⇒ no-op (silent
   skip rather than firing with a 0 amount). Consequences are
   compute-fns or `:tax-context-fact` amounts, never literal — rates
   live in `:parameter` data, NOT inlined here.

   Regime scoping: every Lucro Real provision carries
   `:provision/regime [:regime/code :br-lucro-real]`. The IRPJ
   adicional surtax is regime-AGNOSTIC (no `:provision/regime` —
   fires under Real + Presumido + Arbitrado, per note 162 §2.3)."

  [;; ====================================================================
   ;; IRPJ LUCRO REAL — base-side (6 + 1 dividendos-excluídos = 7)
   ;; ====================================================================

   ;; ----- ADD: multas indedutíveis (Lei 9.430/1996 art. 41) -----
   {:provision/code            "BR-IRPJ-Real-multas-indedutiveis"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição IRPJ — multas indedutíveis (penais)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art41"
    :provision/effective-from  #inst "1997-01-01"
    :provision/priority        100
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :multas-indedutiveis] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :irpj-multas
                                        :label        "Multas de natureza penal (IRPJ)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :multas-indedutiveis]})}

   ;; ----- ADD: doações acima dos limites (Lei 9.249/1995 art. 13) -----
   {:provision/code            "BR-IRPJ-Real-doacoes-acima-limite"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição IRPJ — doações acima dos limites legais"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        110
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :doacoes-acima-limite] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :irpj-doacoes
                                        :label        "Doações acima dos limites (Lei 9.249 art. 13)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :doacoes-acima-limite]})}

   ;; ----- ADD: brindes (Lei 9.249/1995 art. 13 §2) -----
   {:provision/code            "BR-IRPJ-Real-brindes"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição IRPJ — brindes (Lei 9.249 art. 13 §2 — não dedutíveis)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        120
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :brindes] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :irpj-brindes
                                        :label        "Brindes (Lei 9.249 art. 13 §2)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :brindes]})}

   ;; ----- ADD: CSLL não dedutível do IRPJ (Lei 9.316/1996 art. 1) -----
   {:provision/code            "BR-IRPJ-Real-csll-addback"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição IRPJ — CSLL provisão do período (não dedutível)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9316.htm#art1"
    :provision/effective-from  #inst "1997-01-01"
    :provision/priority        130
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :csll-provisao-periodo] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :irpj-csll-addback
                                        :label        "CSLL provisionada (não dedutível do IRPJ)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :csll-provisao-periodo]})}

   ;; ----- DEDUCT: dividendos isentos (Lei 9.249/1995 art. 10) -----
   {:provision/code            "BR-IRPJ-Real-dividendos-excluidos"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão IRPJ — dividendos recebidos isentos"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art10"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        140
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :dividendos-recebidos] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :irpj-dividendos
                                        :label        "Dividendos isentos (Lei 9.249 art. 10)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :dividendos-recebidos]})}

   ;; ----- DEDUCT: JCP (Lei 9.249/1995 art. 9) -----
   {:provision/code            "BR-IRPJ-Real-jcp-deduction"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão IRPJ — JCP pago dentro dos limites (TJLP×PL ∧ 50 % lucro)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        200
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :jcp-pago] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :irpj-jcp
                                        :label        "JCP — limite TJLP×PL ∧ 50 % lucro (Lei 9.249 art. 9)"
                                        :amount-from  :compute-fn
                                        :fn           :br-jcp-cap-irpj})}

   ;; ----- DEDUCT: trava-30 % compensação prejuízo fiscal -----
   ;; Late-bound on :running — cap depends on the post-all-other-adjustments base
   {:provision/code            "BR-IRPJ-Real-trava-30pct"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Compensação prejuízo fiscal — trava 30 % (Lei 9.065 art. 15)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"
    :provision/effective-from  #inst "1995-01-01"
    :provision/priority        900    ; runs LAST — all other base-adj before
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :prejuizo-fiscal-acumulado] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :irpj-compensacao
                                        :label        "Compensação prejuízo (≤ 30 % Lucro Real pré-compensação)"
                                        :amount-from  :compute-fn
                                        :fn           :br-trava-30pct-irpj})}

   ;; ====================================================================
   ;; IRPJ-only Lucro Real DEDUCTs — PAT + Rouanet (2)
   ;; ====================================================================

   ;; ----- DEDUCT: PAT — Programa de Alimentação do Trabalhador (Lei 6.321/1976) -----
   {:provision/code            "BR-IRPJ-Real-pat-deduction"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão IRPJ — PAT (Lei 6.321/1976) — não aplicável à CSLL"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l6321.htm"
    :provision/effective-from  #inst "1976-04-14"
    :provision/priority        210
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :pat-deducao] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :irpj-pat
                                        :label        "PAT — Programa de Alimentação do Trabalhador (IRPJ-only)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :pat-deducao]})}

   ;; ----- DEDUCT: Lei Rouanet / Lei do Audiovisual (Lei 8.313/1991 + Lei 8.685/1993) -----
   {:provision/code            "BR-IRPJ-Real-rouanet-audiovisual"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão IRPJ — Lei Rouanet + Lei do Audiovisual (IRPJ-only)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l8313cons.htm"
    :provision/effective-from  #inst "1991-12-23"
    :provision/priority        220
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :irpj]
                                        [:gt [:inputs :rouanet-deducao] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :irpj-rouanet
                                        :label        "Lei Rouanet / Lei do Audiovisual (IRPJ-only)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :rouanet-deducao]})}

   ;; ====================================================================
   ;; CSLL LUCRO REAL — base-side (5)
   ;; NOTE: no CSLL-addback (CSLL is dedutível from CSLL); no PAT;
   ;; no Rouanet (both IRPJ-only). Brindes folded into doações for CSLL
   ;; per note 162 §2.3 (the law treats both as art. 13).
   ;; ====================================================================

   ;; ----- ADD CSLL: multas indedutíveis -----
   {:provision/code            "BR-CSLL-Real-multas-indedutiveis"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição CSLL — multas indedutíveis"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art41"
    :provision/effective-from  #inst "1997-01-01"
    :provision/priority        100
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :csll]
                                        [:gt [:inputs :multas-indedutiveis] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :csll-multas
                                        :label        "Multas penais (CSLL)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :multas-indedutiveis]})}

   ;; ----- ADD CSLL: doações acima dos limites -----
   {:provision/code            "BR-CSLL-Real-doacoes-acima-limite"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-add]
    :provision/title           "Adição CSLL — doações acima dos limites legais"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        110
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :csll]
                                        [:gt [:inputs :doacoes-acima-limite] 0M]])
    :provision/consequence     (pr-str {:op           :base-add
                                        :code         :csll-doacoes
                                        :label        "Doações acima dos limites (CSLL)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :doacoes-acima-limite]})}

   ;; ----- DEDUCT CSLL: dividendos isentos -----
   {:provision/code            "BR-CSLL-Real-dividendos-excluidos"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão CSLL — dividendos recebidos isentos"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art10"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        140
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :csll]
                                        [:gt [:inputs :dividendos-recebidos] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :csll-dividendos
                                        :label        "Dividendos isentos (CSLL)"
                                        :amount-from  :tax-context-fact
                                        :fact         [:inputs :dividendos-recebidos]})}

   ;; ----- DEDUCT CSLL: JCP (Lei 9.249/1995 art. 9 §10) -----
   {:provision/code            "BR-CSLL-Real-jcp-deduction"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Exclusão CSLL — JCP pago dentro dos limites"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"
    :provision/effective-from  #inst "1996-01-01"
    :provision/priority        200
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :csll]
                                        [:gt [:inputs :jcp-pago] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :csll-jcp
                                        :label        "JCP — limite TJLP×PL ∧ 50 % lucro (CSLL)"
                                        :amount-from  :compute-fn
                                        :fn           :br-jcp-cap-csll})}

   ;; ----- DEDUCT CSLL: trava-30 % base negativa (Lei 9.065/1995 art. 16) -----
   {:provision/code            "BR-CSLL-Real-trava-30pct"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "Compensação base negativa CSLL — trava 30 % (Lei 9.065 art. 16)"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art16"
    :provision/effective-from  #inst "1995-01-01"
    :provision/priority        900
    :provision/regime          [:regime/code :br-lucro-real]
    :provision/condition       (pr-str [:and
                                        [:eq :component :csll]
                                        [:gt [:inputs :base-negativa-csll-acumulada] 0M]])
    :provision/consequence     (pr-str {:op           :base-deduct
                                        :code         :csll-compensacao
                                        :label        "Compensação base negativa CSLL (≤ 30 %)"
                                        :amount-from  :compute-fn
                                        :fn           :br-trava-30pct-csll})}

   ;; ====================================================================
   ;; IRPJ ADICIONAL — regime-agnostic surtax (1)
   ;; ====================================================================
   ;; The threshold = R$ 20k × months-in-period (provider supplies the
   ;; resolved base to ctx :base-amount before invoking apply-adjustments).
   ;; Regime-agnostic: NO :provision/regime — fires under Real, Presumido,
   ;; Arbitrado. Per note 162 §2.3.
   {:provision/code            "BR-IRPJ-adicional-10pct"
    :provision/jurisdiction    :br
    :provision/concept         [:kontor.tax-concept/code :surtax]
    :provision/title           "IRPJ adicional 10 % — Lei 9.430 art. 4"
    :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"
    :provision/effective-from  #inst "1997-01-01"
    :provision/priority        100
    ;; no :provision/regime — regime-agnostic
    :provision/condition       (pr-str [:eq :component :irpj])
    :provision/consequence     (pr-str {:op           :surtax
                                        :code         :irpj-adicional
                                        :label        "Adicional 10 % sobre excesso R$ 20k × meses"
                                        :amount-from  :compute-fn
                                        :fn           :br-irpj-adicional})}])

;; ============================================================================
;; Install! — transact parameters + values + regimes + provisions
;; ============================================================================

(defn install!
  "Install BR CIT statute (parameters + values + regimes + provisions)
   into `conn`. Idempotent — `:parameter/code`, `:regime/code` and
   `:provision/code` are unique identity attrs, so re-running the
   install is a no-op on unchanged rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn regimes)
  (d/transact conn provisions))
