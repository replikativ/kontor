(ns kontor.l10n-at.cgt-statute
  "AT capital-gains tax — §27/§27a EStG (KESt-Endbesteuerung on
   financial assets), §30/§30a EStG (ImmoESt on real estate), §10 KStG
   (Schachtelbeteiligung) — encoded as `kontor.statute` data per
   ADR-101. Research note 134.

   AT does NOT have ONE capital-gains tax. It has FOUR overlapping
   statutory shapes (note 134 §1):

   - **§27/§27a EStG** — KESt-Endbesteuerung. Flat 27.5 % on financial-
     asset capital gains for individuals; 25 % on bank-deposit interest.
     Final-withholding (§97 EStG Endbesteuerungswirkung) — KESt
     discharges the income-tax liability; investor does not report
     unless they elect Regelbesteuerung. Losses offset within
     Verlustverrechnungstopf BUT do NOT carry forward — the bucket
     resets every Jan 1 (§27 Abs 8 EStG; note 134 §6.3).
   - **§30/§30a EStG** — ImmoESt. Flat 30 % on real-estate gains.
     Altvermögen (pre-2002): pauschale 4.2 % of gross sales price
     (unwidmet) OR 18 % (gewidmet — building-permit changed land use).
     Hauptwohnsitzbefreiung (2-of-2-years OR 5-of-last-10-years) +
     Herstellerbefreiung (building only, land taxable).
     §30 Abs 7: real-estate losses × 60 % × 15-year carry against
     §28-Vermietung income (note 134 §6.2 — first cross-category CGT
     loss in the kontor substrate).
   - **§10 KStG** — Schachtelbeteiligung. CIT rate ladder
     25 → 24 → 23 % (2022 → 2023 → 2024+). Qualifying participation
     (>10 % + 1-year holding): capital gains EXEMPT by default. Opt-in
     to taxable via Option zur Steuerwirksamkeit (§10 Abs 3 KStG —
     INVERSION of the usual opt-in-to-exempt structure; note 134 §6.1).
     §10-Option losses spread over 7 years (§12 Abs 3 Z 2 KStG —
     Siebentelregelung).
   - **§6 Z 2 lit c EStG** — 55 % Begrenzung. Business-side rule for
     KESt-Vermögen losses in Betriebsvermögen offset against ordinary
     income. The 55 % factor matches the 27.5 % flat rate × 2 to keep
     the integration ratio honest (note 134 §1.6).
   - **§9 KStG** — Gruppenbesteuerung — out of scope for v1; overlaps
     with future group-tax-consolidation ADR.

   Citations point at jusline.at + ris.bka.gv.at for the EStG / KStG
   text; parameter-values carry their own citations (BMF / findok
   / WKO references).

   See note 134 for the substrate fit + the four worked examples."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "AT CGT parameter definitions. Values live in `parameter-values`
   keyed by `:effective-from`."
  [;; --- §27a EStG — KESt-Endbesteuerung rates ----------------------------
   {:parameter/code         "AT.EStG.§27a.kest-financial-rate"
    :parameter/label        "§27a Abs 1 Z 2 EStG — KESt flat rate on financial-asset capital gains (27.5 %)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/27a"}

   {:parameter/code         "AT.EStG.§27a.kest-interest-rate"
    :parameter/label        "§27a Abs 1 Z 1 EStG — KESt flat rate on bank-deposit interest (25 %)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/27a"}

   {:parameter/code         "AT.EStG.§6-Z2-lit-c.business-loss-limit"
    :parameter/label        "§6 Z 2 lit c EStG — 55 % Begrenzung on KESt-Vermögen losses against ordinary income (Betriebsvermögen)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/6"}

   ;; --- §30a EStG — ImmoESt -----------------------------------------------
   {:parameter/code         "AT.EStG.§30a.immoest-rate"
    :parameter/label        "§30a Abs 1 EStG — ImmoESt flat rate on real-estate gains (30 % since StRefG 2015/2016)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30a"}

   {:parameter/code         "AT.EStG.§30.altvermoegen-unwidmet-effective-rate"
    :parameter/label        "§30 Abs 4 Z 2 EStG — Altvermögen pauschale effective rate on proceeds (4.2 % = 14 % deemed gain × 30 %, unwidmet)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30"}

   {:parameter/code         "AT.EStG.§30.altvermoegen-gewidmet-effective-rate"
    :parameter/label        "§30 Abs 4 Z 1 EStG — Altvermögen pauschale effective rate on proceeds (18 % = 60 % deemed gain × 30 %, gewidmet/rezoned post-1987)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30"}

   {:parameter/code         "AT.EStG.§30-Abs-7.loss-carry-factor"
    :parameter/label        "§30 Abs 7 EStG — ImmoESt loss carry factor (60 % of net loss carries against §28-Vermietung income)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30"}

   {:parameter/code         "AT.EStG.§30-Abs-7.loss-carry-years"
    :parameter/label        "§30 Abs 7 EStG — ImmoESt loss carry distribution window (15 calendar years: loss year + 14)"
    :parameter/jurisdiction :at
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30"}

   ;; --- §10 KStG — Körperschaftsteuer rate ladder -------------------------
   {:parameter/code         "AT.KStG.cit-rate"
    :parameter/label        "§22 KStG — Körperschaftsteuer rate (25 % 2022 → 24 % 2023 → 23 % 2024+; ÖkoStRefG 2022 stepped reduction)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/22"}

   {:parameter/code         "AT.KStG.§10.qualifying-ownership-fraction"
    :parameter/label        "§10 Abs 2 KStG — internationale Schachtelbeteiligung qualifying ownership threshold (≥ 10 %)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/10"}

   {:parameter/code         "AT.KStG.§10.qualifying-holding-days"
    :parameter/label        "§10 Abs 2 KStG — internationale Schachtelbeteiligung qualifying holding-period (≥ 365 days)"
    :parameter/jurisdiction :at
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/10"}

   {:parameter/code         "AT.KStG.§12-Abs-3-Z-2.siebentel-years"
    :parameter/label        "§12 Abs 3 Z 2 KStG — Siebentelregelung: §10-Option losses spread over 7 years"
    :parameter/jurisdiction :at
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/12"}

   ;; --- §30 Abs 6a EStG — Umwidmungszuschlag (BBG 2025) ------------------
   ;; 30 % surcharge on the LAND-slice positive gain when previously
   ;; agricultural/forest land was rezoned to Bauland. Applies to
   ;; disposals after 2025-06-30 where the rezoning was legally
   ;; effective after 2024-12-31. The enhanced base is capped at
   ;; proceeds: Bemessungsgrundlage = min(1.30 × Gewinn ; Erlös).
   ;; The consumer flags eligibility via
   ;; `:elective-regime :at-umwidmungszuschlag` on the disposal AND
   ;; supplies the LAND-slice basis via `:disposal/basis-amount`
   ;; (building portion documented in `:notes`), per the existing
   ;; Herstellerbefreiung convention. Research note 146 §3.1.
   {:parameter/code         "AT.EStG.§30-Abs-6a.umwidmungszuschlag-rate"
    :parameter/label        "§30 Abs 6a EStG — Umwidmungszuschlag (30 % surcharge on land-slice gain for post-2024 rezoning to Bauland)"
    :parameter/jurisdiction :at
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/30"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "AT CGT parameter values with their statutory effective windows.

   - KESt rates: 27.5 % financial / 25 % interest stable since
     BBG 2011 (effective 2012-04-01); §6 Z 2 lit c 55 % Begrenzung
     since the 2014 alignment with the 27.5 % rate.
   - ImmoESt: 25 % from 2012-04-01 (BBG 2011); raised to 30 % effective
     2016-01-01 (StRefG 2015/2016, BGBl I 2015/118). Altvermögen
     pauschale 3.5 % / 15 % proceeds-effective pre-2016; 4.2 % / 18 %
     post-2016 (14 % × 30 % / 60 % × 30 %).
   - CIT rate ladder per ÖkoStRefG 2022 (BGBl I 2022/10): 25 %
     (≤2022) → 24 % (2023) → 23 % (2024+)."
  [;; --- KESt rates --------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "AT.EStG.§27a.kest-financial-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.275M
    :parameter-value/citation       "§27a Abs 1 Z 2 EStG (StRefG 2015/2016, BGBl I 2015/118) — 27.5 % flat KESt on Wertsteigerungen ab 2016-01-01"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§27a.kest-interest-rate"]
    :parameter-value/effective-from #inst "2012-04-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "§27a Abs 1 Z 1 EStG (BBG 2011, BGBl I 2010/111) — 25 % flat KESt on Zinsen aus Geldeinlagen (stable)"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§6-Z2-lit-c.business-loss-limit"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.55M
    :parameter-value/citation       "§6 Z 2 lit c EStG (AbgÄG 2014) — 55 % integration ratio for KESt-Vermögen losses in Betriebsvermögen against ordinary income (matches 27.5 % × 2)"}

   ;; --- ImmoESt -----------------------------------------------------------
   {:parameter-value/parameter       [:parameter/code "AT.EStG.§30a.immoest-rate"]
    :parameter-value/effective-from  #inst "2012-04-01"
    :parameter-value/effective-until #inst "2016-01-01"
    :parameter-value/decimal-value   0.25M
    :parameter-value/citation        "§30a Abs 1 EStG (BBG 2011) — 25 % ImmoESt 2012-04-01 to 2015-12-31"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30a.immoest-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "§30a Abs 1 EStG (StRefG 2015/2016, BGBl I 2015/118) — 30 % ImmoESt ab 2016-01-01"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30.altvermoegen-unwidmet-effective-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.042M
    :parameter-value/citation       "§30 Abs 4 Z 2 EStG — Altvermögen unwidmet: 14 % deemed gain × 30 % = 4.2 % effective rate on proceeds (StRefG 2015/2016 alignment)"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30.altvermoegen-gewidmet-effective-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.18M
    :parameter-value/citation       "§30 Abs 4 Z 1 EStG — Altvermögen umgewidmet (post-1987-12-31): 60 % deemed gain × 30 % = 18 % effective rate on proceeds"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30-Abs-7.loss-carry-factor"]
    :parameter-value/effective-from #inst "2012-04-01"
    :parameter-value/decimal-value  0.60M
    :parameter-value/citation       "§30 Abs 7 EStG (BBG 2011) — 60 % of ImmoESt loss residual carries against §28 Vermietung income"}

   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30-Abs-7.loss-carry-years"]
    :parameter-value/effective-from #inst "2012-04-01"
    :parameter-value/decimal-value  15M
    :parameter-value/citation       "§30 Abs 7 EStG — distribution over 15 calendar years (loss year + 14 following years)"}

   ;; --- §10 KStG + CIT rate ladder ----------------------------------------
   {:parameter-value/parameter       [:parameter/code "AT.KStG.cit-rate"]
    :parameter-value/effective-from  #inst "2005-01-01"
    :parameter-value/effective-until #inst "2023-01-01"
    :parameter-value/decimal-value   0.25M
    :parameter-value/citation        "§22 KStG — 25 % CIT 2005 to 2022 (StRefG 2005 reduction; held until ÖkoStRefG 2022)"}

   {:parameter-value/parameter       [:parameter/code "AT.KStG.cit-rate"]
    :parameter-value/effective-from  #inst "2023-01-01"
    :parameter-value/effective-until #inst "2024-01-01"
    :parameter-value/decimal-value   0.24M
    :parameter-value/citation        "§22 KStG (ÖkoStRefG 2022, BGBl I 2022/10) — 24 % CIT calendar year 2023"}

   {:parameter-value/parameter      [:parameter/code "AT.KStG.cit-rate"]
    :parameter-value/effective-from #inst "2024-01-01"
    :parameter-value/decimal-value  0.23M
    :parameter-value/citation       "§22 KStG (ÖkoStRefG 2022, BGBl I 2022/10) — 23 % CIT ab 2024-01-01"}

   {:parameter-value/parameter      [:parameter/code "AT.KStG.§10.qualifying-ownership-fraction"]
    :parameter-value/effective-from #inst "2011-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "§10 Abs 2 KStG — Schachtelbeteiligung ≥ 10 % stake threshold"}

   {:parameter-value/parameter      [:parameter/code "AT.KStG.§10.qualifying-holding-days"]
    :parameter-value/effective-from #inst "2011-01-01"
    :parameter-value/decimal-value  365M
    :parameter-value/citation       "§10 Abs 2 KStG — 12 consecutive months minimum holding period"}

   {:parameter-value/parameter      [:parameter/code "AT.KStG.§12-Abs-3-Z-2.siebentel-years"]
    :parameter-value/effective-from #inst "2011-01-01"
    :parameter-value/decimal-value  7M
    :parameter-value/citation       "§12 Abs 3 Z 2 KStG — Siebentelregelung: 7-year spread for §10-Option-elected losses"}

   ;; --- Umwidmungszuschlag — BBG 2025, effective for sales post-2025-06-30
   {:parameter-value/parameter      [:parameter/code "AT.EStG.§30-Abs-6a.umwidmungszuschlag-rate"]
    :parameter-value/effective-from #inst "2025-07-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "§30 Abs 6a EStG (BBG 2025) — 30 % Umwidmungszuschlag on the LAND-slice positive gain for disposals after 2025-06-30 where rezoning effective after 2024-12-31; Bemessungsgrundlage = min(1.30 × Gewinn ; Erlös)"}])

;; ============================================================================
;; Provisions — empty for AT CGT v1
;; ============================================================================

(def provisions
  "AT CGT statutory provisions. Empty for v1 — every AT CGT mechanism
   is provider-internal:
   - KESt 27.5 / 25 % flat: parameter-driven `ts/flat` schedule.
   - ImmoESt 30 % + Altvermögen pauschale: provider branches on
     `:asset-class` + `:elective-regime` per disposal.
   - Hauptwohnsitz / Hersteller exemptions: provider short-circuits
     when `:exemption-claimed` carries the flag.
   - §10 KStG default-exempt inversion: provider checks
     `:elective-regime :at-§10-tax-effective-option` first, otherwise
     short-circuits gain into `:cit-base-deductions`.
   - §30 Abs 7 carry: provider writes to
     `:pit-base-deductions {:§28-vermietung ...}` (note 134 §6.2 —
     first cross-category CGT loss). This is the AT companion's
     extended shape on the otherwise opaque `:inputs` map.
   - §12 Abs 3 Z 2 Siebentelregelung: consumer-supplied
     `:inputs :at-§10-loss-siebentel` per year.

   Future iterations may move some of these to `:provision` data as
   they accumulate cross-jurisdiction commonality (note 134 §5)."
  [])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install AT CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs, so re-running the install is a no-op on unchanged
   rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions) (d/transact conn provisions)))
