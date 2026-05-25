(ns kontor.l10n-fr.investment-income-statute
  "FR investment-income tax — dividends / interest / assurance-vie /
   régime mère-fille — encoded as `kontor.statute` data per ADR-101.
   Research note 149.

   THE KEY INSIGHT (note 149 §4): most parameters this provider needs
   ALREADY EXIST in `cgt-statute.clj` (the PS placement-rate at 18.6%
   and the PFU IR-rate at 12.8% are the same wires the mobilière CGT
   provider rides):

     - `FR.CGT.PFU.IR-rate`            12.8% IR (CGI Art. 200 A 1°)
     - `FR.CGT.PS.placement-rate`      18.6% PS post-LFSS-2026 on
                                       revenus de placement (dividends /
                                       interest)
     - `FR.CGT.PS.real-estate-rate`    17.2% PS carve-out for
                                       assurance-vie / PEL / CEL / PEP

   This namespace adds investment-income-SPECIFIC parameters:

     - The 40 % abattement sur dividendes (CGI 158-3-2°) — barème only.
     - The 6.8 pp CSG-déductible share (CGI 154 quinquies).
     - The 5 % QPFC quote-part for régime mère-fille (CGI 216).
     - The 5 % stake threshold + 2-year holding period (CGI 145).

   Provisions slot reserved but empty in v1 (the substrate fit per
   note 149 §3 keeps the math provider-internal — the abattement /
   QPFC are single-multipliers, not statute-rules with conditions).

   No new `:parameter-bracket` rows; no per-status rate gradation."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "FR investment-income parameter definitions. The PFU IR rate + PS
   placement-rate / real-estate-rate are reused from `cgt-statute.clj`;
   only the dividend abattement, CSG-déductible, and mère-fille
   parameters anchor here."

  [;; --- 40 % abattement sur dividendes (barème only) -----------------
   {:parameter/code         "FR.INV.bareme.abattement-dividendes"
    :parameter/label        "Abattement 40 % sur dividendes éligibles — applicable UNIQUEMENT sous l'option barème (CGI Art. 158-3-2°)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051765203"}

   ;; --- CSG déductible (barème only) ---------------------------------
   {:parameter/code         "FR.INV.bareme.CSG-deductible-share"
    :parameter/label        "Part de CSG déductible sur revenus du capital sous option barème — 6,8 points sur 10,6 (CGI Art. 154 quinquies)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000047288608"}

   ;; --- Régime mère-fille — 5 % QPFC ---------------------------------
   {:parameter/code         "FR.INV.mere-fille.QPFC-rate"
    :parameter/label        "Régime mère-fille — quote-part de frais et charges 5 % réintégrée à l'IS (CGI Art. 216)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577/LEGISCTA000006197185/"}

   {:parameter/code         "FR.INV.mere-fille.minimum-holding-fraction"
    :parameter/label        "Régime mère-fille — seuil minimal de détention (5 %, CGI Art. 145)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000038589881"}

   {:parameter/code         "FR.INV.mere-fille.minimum-holding-period-years"
    :parameter/label        "Régime mère-fille — engagement de détention minimum (2 ans, CGI Art. 145)"
    :parameter/jurisdiction :fr
    :parameter/unit         :years
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000038589881"}

   ;; --- Régime intégration fiscale — 1 % QPFC ------------------------
   ;; Not modelled by v1 provider (note 149 §1.5 — multi-entity construct);
   ;; the parameter is shipped for citation completeness, the consumer can
   ;; override via :inputs :mere-fille {:integration-fiscale? true}.
   {:parameter/code         "FR.INV.integration-fiscale.QPFC-rate"
    :parameter/label        "Régime intégration fiscale — QPFC 1 % sur dividendes intra-groupe (CGI Art. 223 A)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577"}])

;; ============================================================================
;; Parameter values
;; ============================================================================

(def parameter-values
  "FR investment-income parameter values. All stable since their
   respective introductions; no LFSS 2026 amendments."

  [;; 40 % abattement on dividends — stable since loi 2008
   {:parameter-value/parameter      [:parameter/code "FR.INV.bareme.abattement-dividendes"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.40M
    :parameter-value/citation       "CGI Art. 158-3-2° — abattement 40 % sur dividendes versés par sociétés FR/UE/État conventionné soumises à l'IS, applicable UNIQUEMENT sous option barème"}

   ;; CSG déductible — historically 5.1 pp / 6.8 pp depending on era;
   ;; current value 6.8 pp confirmed at CGI 154 quinquies (LEGIARTI000047288608).
   {:parameter-value/parameter      [:parameter/code "FR.INV.bareme.CSG-deductible-share"]
    :parameter-value/effective-from #inst "2018-01-01"
    :parameter-value/decimal-value  0.068M
    :parameter-value/citation       "CGI Art. 154 quinquies — 6,8 points de CSG déductibles sur revenus du capital sous option barème (Indy / Legifrance)"}

   ;; Régime mère-fille — 5 % QPFC stable since 2011 (loi 2010-1657)
   {:parameter-value/parameter      [:parameter/code "FR.INV.mere-fille.QPFC-rate"]
    :parameter-value/effective-from #inst "2011-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "CGI Art. 216 I — QPFC 5 % stable depuis LF 2011 (relevée de 5 % à 5 % par LF 2011 ; resté à 5 %)"}

   {:parameter-value/parameter      [:parameter/code "FR.INV.mere-fille.minimum-holding-fraction"]
    :parameter-value/effective-from #inst "2001-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "CGI Art. 145 — détention minimale 5 % du capital de la filiale"}

   {:parameter-value/parameter      [:parameter/code "FR.INV.mere-fille.minimum-holding-period-years"]
    :parameter-value/effective-from #inst "2001-01-01"
    :parameter-value/decimal-value  2M
    :parameter-value/citation       "CGI Art. 145 — engagement de détention 2 ans"}

   ;; Régime intégration fiscale — 1 % QPFC stable since LFR 2016
   {:parameter-value/parameter      [:parameter/code "FR.INV.integration-fiscale.QPFC-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.01M
    :parameter-value/citation       "CGI Art. 223 B — QPFC 1 % sur dividendes intra-groupe sous régime d'intégration fiscale (LFR 2015 art. 40)"}])

;; ============================================================================
;; Provisions — v1 is empty (note 149 §3 motivates provider-internal math)
;; ============================================================================

(def provisions
  "FR investment-income does not currently encode provisions through the
   `kontor.statute` evaluator. The reason (note 149 §3):

   - The 40 % abattement on dividendes is a single-multiplier, applied
     ONLY under barème election — the provider routes natively from
     `:tax-unit :pfu-or-bareme`.
   - The mère-fille 5 % QPFC is a single multiplier on qualifying
     gross dividends; the eligibility test (5 % stake + 2y hold) is a
     consumer-supplied fact pair (`:partner/holding-fraction` +
     `:partner/held-since` per note 149 G2), not a statute provision.
   - PEA / assurance-vie exemptions ride per-disposal flags on the
     posting (`:account-tag :fr-investment-income/pea-*` family); the
     provider filters at component-build time.

   The slot is reserved for future amendment-driven provisions (a
   future abattement amendment, an integration-fiscale activation,
   etc.) — the install! function transacts even when empty to keep
   the API stable."
  [])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install FR investment-income statute (parameters + provisions) into
   `conn`. Idempotent — `:parameter/code` and `:provision/code` are
   unique identity attrs.

   ASSUMES the FR CGT statute has already been installed (this statute
   references the CGT PS placement-rate + PFU IR-rate parameters by
   code; the consumer wires both statutes per period close)."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))
