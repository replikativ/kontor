(ns kontor.l10n-fr.cgt-statute
  "FR capital-gains tax — five-regime substrate — encoded as
   `kontor.statute` parameter / provision data per ADR-101. Research
   note 128 is the FR-CGT fit assessment that motivated this encoding.

   FR does NOT have one CGT regime — it has FIVE overlapping shapes
   (note 128 §1), and a provider classifies each `:disposal` into the
   right lane by `:disposal/asset-class` + `:disposal/subject-form`:

   - **Plus-values mobilières** (CGI Art. 150-0 A) — PFU 31.4 % default
     (12.8 % IR + 18.6 % PS post-LFSS-2026) OR barème election + the
     abattement-durée ladders (Art. 150-0 D 1 ter général 50/65 %; 1
     quater renforcé 50/65/85 %, both pre-2018-acquisition gated).
   - **Plus-values immobilières** (CGI Art. 150 U) — 19 % IR + 17.2 %
     PS (NOT subject to LFSS 2026 raise) + progressive surtaxe (Art.
     1609 nonies G, 2–6 % > €50 k). Two separate abattement-durée
     ladders (IR fully exempt at 22y; PS fully exempt at 30y).
   - **Plus-values professionnelles** (CGI Art. 39 duodecies + 39
     quindecies) — court-terme (ordinary BIC/BNC) vs long-terme
     (12.8 % IR + 17.2 % PS). §151 septies revenue-tested exemption
     (€90 k / €250 k / €350 k thresholds + degressive band);
     §238 quindecies transmission exemption (€700 k / €1.2 M from
     FY-2025).
   - **Titres de participation** (CGI Art. 219, I a quinquies) — IS-side
     exonération + 12 % quote-part de frais et charges (QPFC) reintegrated
     at standard 25 % IS rate (≈ 3 % effective). FR equivalent of DE §8b
     KStG 95/5 but with a 12 % QPFC, not 5 %.
   - **Brevets / IP box** (CGI Art. 238) — preferential 10 % corporate
     rate, nexus-ratio-weighted, annual election.

   ## Two parameter families, two provision families

   The kernel evaluator (`kontor.statute/apply-provisions`) is the engine;
   FR uses it for what fits cleanly:

   - **Statute-driven, date-keyed** — every rate / threshold lives as a
     `:parameter` + `:parameter-value`. The 2026 LFSS rate change
     (CSG 9.2 → 10.6 %, PS 17.2 → 18.6 % on securities; UNCHANGED for
     real estate / life insurance) IS date-keyed; a future amendment is
     a row add. The §238 quindecies 2025 raise (€500 k → €700 k cliff)
     IS date-keyed. The PME bracket cap (€42 500) is already in
     `cit-statute` and the FR CGT brevets provider reads it from there.

   - **Provider-internal** — lane classification, abattement-durée math
     (per-year arithmetic on `:acquired-on` + `:disposed-on`), surtaxe
     1609 nonies G bracket fold, PFU vs barème election routing, and
     the per-asset-class PS-rate map (note 128 §5.4) live in the
     provider. They depend on too many cross-disposal facts (loss
     bucketing, IR-vs-PS asymmetric ladders) to express as single-pass
     `:provision` data, AND they don't change per parameter row — they
     change per ADR.

   ## Statute scope (v1)

   - Parameters: PFU IR rate, PS base rate (CSG + CRDS + prélèvement),
     PS rate for real estate / life insurance (carve-out), 19 %
     immobilière IR rate, 12 % QPFC quote-part, 10 % brevets rate,
     §238 quindecies cliffs (pre-2025 + post-2025), §151 septies
     revenue thresholds, surtaxe 1609 nonies G threshold, abattement
     ladders (encoded as deltas; the provider folds).

   - Provisions: NIIT-equivalent surtaxes none in FR (the PS layer IS
     the surtax and it's per-asset-class so it lives in the provider).
     A single elective-regime gating shape (`fr-pfu-vs-bareme`) is
     captured here for completeness but the provider routes natively.

   ## Out of scope for v1 (note 128 §1.9 / §1.10)

   - **Sursis d'imposition 150-0 B** — the share-for-share exchange is
     non-recognition; the substrate convention is to NOT emit a disposal
     at all (lot's `:acquired-on` rides through). No statute needed.
   - **Report d'imposition 150-0 B ter** (apport-cession) — the data
     shape uses `:rollover-into-asset` / `-amount` / `-deadline` but
     the v1 provider does NOT check the 60 % / 70 % reinvest at the
     deadline; deferred per note 128.
   - **Exit tax 167 bis** — handled by `:kind :deemed` disposals + the
     mobilière component (the gain is computed and folded normally);
     the €800 k aggregation test is consumer-supplied.

   Citations point at legifrance.gouv.fr for the CGI articles and
   bofip.impots.gouv.fr for the administrative commentary."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "FR CGT parameter definitions. Values live in `parameter-values`
   keyed by `:effective-from`. Per-asset-class PS rates surface as
   TWO parameters: the post-2026 default (18.6 %) and the carve-out
   for real estate / life insurance (17.2 %); the provider picks
   per `:disposal/asset-class`."
  [;; --- Mobilière PFU layer (CGI Art. 200 A) -------------------------
   {:parameter/code         "FR.CGT.PFU.IR-rate"
    :parameter/label        "PFU IR component (12.8 % flat tax) — CGI Art. 200 A 1°"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000045760882"}

   {:parameter/code         "FR.CGT.PS.default-rate"
    :parameter/label        "Prélèvements sociaux default rate — securities + ordinary investment income (CSG 10.6 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 18.6 % post-LFSS 2026)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006073189/LEGIARTI000006740051/"}

   {:parameter/code         "FR.CGT.PS.real-estate-rate"
    :parameter/label        "Prélèvements sociaux carve-out rate — real estate / life insurance / PEL / CEL / PEP / revenus fonciers (STAYS at 17.2 % per LFSS 2026 exclusion)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026"}

   ;; --- Immobilière (CGI Art. 150 U + 200 B + 1609 nonies G) ----------
   {:parameter/code         "FR.CGT.Immo.IR-rate"
    :parameter/label        "Plus-values immobilières — IR taux fixe (19 %)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000006304108"}

   {:parameter/code         "FR.CGT.Immo.surtaxe-floor"
    :parameter/label        "Surtaxe plus-values immobilières (CGI Art. 1609 nonies G) — floor below which surtaxe = 0 (€50 000)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006069577/LEGIARTI000027577763/"}

   ;; Abattement-durée ladder values for immobilière. The provider folds;
   ;; the parameters here are for citation + future amendment audit.
   {:parameter/code         "FR.CGT.Immo.abat-IR-y6to21"
    :parameter/label        "Abattement IR plus-value immobilière — années 6 à 21 (6 %/an)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:parameter/code         "FR.CGT.Immo.abat-IR-y22"
    :parameter/label        "Abattement IR plus-value immobilière — année 22 (4 %, terminale)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:parameter/code         "FR.CGT.Immo.abat-PS-y6to21"
    :parameter/label        "Abattement PS plus-value immobilière — années 6 à 21 (1.65 %/an)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:parameter/code         "FR.CGT.Immo.abat-PS-y22"
    :parameter/label        "Abattement PS plus-value immobilière — année 22 (1.6 %)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:parameter/code         "FR.CGT.Immo.abat-PS-y23to30"
    :parameter/label        "Abattement PS plus-value immobilière — années 23 à 30 (9 %/an)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   ;; --- Plus-values professionnelles long-terme ---------------------
   {:parameter/code         "FR.CGT.ProLT.IR-rate"
    :parameter/label        "Plus-values pro long-terme — IR (12.8 %, CGI Art. 39 quindecies)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577/LEGISCTA000006197185/"}

   ;; --- §151 septies revenue-tested exemption -----------------------
   {:parameter/code         "FR.CGT.§151-septies.threshold-services-full"
    :parameter/label        "§151 septies — services BIC/BNC: full-exemption ceiling (€90 000 turnover)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:parameter/code         "FR.CGT.§151-septies.threshold-services-degressive"
    :parameter/label        "§151 septies — services BIC/BNC: degressive ceiling (€126 000 turnover)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:parameter/code         "FR.CGT.§151-septies.threshold-goods-full"
    :parameter/label        "§151 septies — ventes marchandises / hébergement: full-exemption ceiling (€250 000 turnover)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:parameter/code         "FR.CGT.§151-septies.threshold-goods-degressive"
    :parameter/label        "§151 septies — ventes marchandises: degressive ceiling (€350 000 turnover)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   ;; --- §238 quindecies transmission-d'entreprise exemption ----------
   {:parameter/code         "FR.CGT.§238-quindecies.threshold-full"
    :parameter/label        "§238 quindecies — full-exemption value cliff (€700 000 from FY-2025; €500 000 prior)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   {:parameter/code         "FR.CGT.§238-quindecies.threshold-degressive"
    :parameter/label        "§238 quindecies — degressive-band upper cliff (€1 200 000 from FY-2025; €1 000 000 prior)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   ;; --- IS-side titres de participation (CGI Art. 219 I a quinquies) -
   {:parameter/code         "FR.CGT.§219.QPFC-rate"
    :parameter/label        "Titres de participation — quote-part de frais et charges (CGI Art. 219, I a quinquies — 12 %)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   {:parameter/code         "FR.CGT.§219.holding-period-years"
    :parameter/label        "Titres de participation — minimum holding period for the régime (2 years)"
    :parameter/jurisdiction :fr
    :parameter/unit         :years
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   ;; --- Brevets / IP box (CGI Art. 238) ------------------------------
   {:parameter/code         "FR.CGT.§238.IP-box-rate"
    :parameter/label        "Régime IP box (brevets, logiciels, COV, certificats) — taux réduit 10 % (CGI Art. 238)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000037946060"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "FR CGT parameter values. The 2026-01-01 LFSS bump moves PS from 17.2
   % to 18.6 % on securities (CSG 9.2 → 10.6 % component) — encoded as
   a new `:parameter-value` row that closes the 2018-2025 row's window.
   Real-estate / life-insurance PS stays at 17.2 % (carve-out — separate
   parameter). The §238 quindecies cliff bump (€500 k → €700 k / €1 M →
   €1.2 M) is the FY-2025 LFI change, same pattern."

  [;; PFU IR — 12.8 % stable since 2018
   {:parameter-value/parameter      [:parameter/code "FR.CGT.PFU.IR-rate"]
    :parameter-value/effective-from #inst "2018-01-01"
    :parameter-value/decimal-value  0.128M
    :parameter-value/citation       "CGI Art. 200 A 1° — PFU 12.8 % stable since loi de finances 2018"}

   ;; PS default — 17.2 % through 2025-12-31, then 18.6 % from 2026-01-01
   {:parameter-value/parameter       [:parameter/code "FR.CGT.PS.default-rate"]
    :parameter-value/effective-from  #inst "2018-01-01"
    :parameter-value/effective-until #inst "2026-01-01"
    :parameter-value/decimal-value   0.172M
    :parameter-value/citation        "CSG 9.2 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 17.2 % (2018-2025)"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.PS.default-rate"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  0.186M
    :parameter-value/citation       "CSG raised 9.2 → 10.6 % by LFSS 2026 (loi 2025-1403) → total PS 18.6 % on securities + ordinary investment income"}

   ;; PS real-estate carve-out — stays at 17.2 %
   {:parameter-value/parameter      [:parameter/code "FR.CGT.PS.real-estate-rate"]
    :parameter-value/effective-from #inst "2018-01-01"
    :parameter-value/decimal-value  0.172M
    :parameter-value/citation       "LFSS 2026 EXCLUDES real estate / life insurance / PEL / CEL / PEP / revenus fonciers from the CSG raise — PS stays 17.2 %"}

   ;; Immobilière IR — 19 % flat
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.IR-rate"]
    :parameter-value/effective-from #inst "2012-02-01"
    :parameter-value/decimal-value  0.19M
    :parameter-value/citation       "CGI Art. 200 B — 19 % stable since 2012"}

   ;; Immobilière surtaxe — €50 k threshold
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.surtaxe-floor"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  50000M
    :parameter-value/citation       "CGI Art. 1609 nonies G — surtaxe progressive 2 → 6 % au-dessus de €50 000 stable"}

   ;; Immobilière abattement ladder rates (the provider folds)
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.abat-IR-y6to21"]
    :parameter-value/effective-from #inst "2014-09-01"
    :parameter-value/decimal-value  0.06M
    :parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement IR 6 %/an années 6 à 21 (loi 2014)"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.abat-IR-y22"]
    :parameter-value/effective-from #inst "2014-09-01"
    :parameter-value/decimal-value  0.04M
    :parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement IR 4 % année 22 (terminale → exonération IR à 22 ans)"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.abat-PS-y6to21"]
    :parameter-value/effective-from #inst "2014-09-01"
    :parameter-value/decimal-value  0.0165M
    :parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 1.65 %/an années 6 à 21"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.abat-PS-y22"]
    :parameter-value/effective-from #inst "2014-09-01"
    :parameter-value/decimal-value  0.016M
    :parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 1.6 % année 22"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.Immo.abat-PS-y23to30"]
    :parameter-value/effective-from #inst "2014-09-01"
    :parameter-value/decimal-value  0.09M
    :parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 9 %/an années 23 à 30 (→ exonération PS à 30 ans)"}

   ;; Plus-values pro long-terme — IR 12.8 %
   {:parameter-value/parameter      [:parameter/code "FR.CGT.ProLT.IR-rate"]
    :parameter-value/effective-from #inst "2018-01-01"
    :parameter-value/decimal-value  0.128M
    :parameter-value/citation       "CGI Art. 39 quindecies — IR plus-value pro LT 12.8 % (aligné PFU)"}

   ;; §151 septies thresholds — stable since 2008
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§151-septies.threshold-services-full"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  90000M
    :parameter-value/citation       "CGI Art. 151 septies II — services BIC/BNC exonération totale CA ≤ €90 000"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§151-septies.threshold-services-degressive"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  126000M
    :parameter-value/citation       "CGI Art. 151 septies II — services BIC/BNC band supérieure €90 001-€126 000"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§151-septies.threshold-goods-full"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  250000M
    :parameter-value/citation       "CGI Art. 151 septies II — marchandises / hébergement exonération totale CA ≤ €250 000"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§151-septies.threshold-goods-degressive"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  350000M
    :parameter-value/citation       "CGI Art. 151 septies II — marchandises band supérieure €250 001-€350 000"}

   ;; §238 quindecies — pre-2025 (€500 k / €1 M), post-2025 (€700 k / €1.2 M)
   {:parameter-value/parameter       [:parameter/code "FR.CGT.§238-quindecies.threshold-full"]
    :parameter-value/effective-from  #inst "2006-01-01"
    :parameter-value/effective-until #inst "2025-01-01"
    :parameter-value/decimal-value   500000M
    :parameter-value/citation        "CGI Art. 238 quindecies — full-exemption cliff €500 000 (pré-LFI 2024)"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§238-quindecies.threshold-full"]
    :parameter-value/effective-from #inst "2025-01-01"
    :parameter-value/decimal-value  700000M
    :parameter-value/citation       "CGI Art. 238 quindecies — full-exemption cliff relevée à €700 000 par LFI 2024 (exercices ouverts à compter du 1er janvier 2025)"}

   {:parameter-value/parameter       [:parameter/code "FR.CGT.§238-quindecies.threshold-degressive"]
    :parameter-value/effective-from  #inst "2006-01-01"
    :parameter-value/effective-until #inst "2025-01-01"
    :parameter-value/decimal-value   1000000M
    :parameter-value/citation        "CGI Art. 238 quindecies — degressive cliff €1 000 000 (pré-LFI 2024)"}
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§238-quindecies.threshold-degressive"]
    :parameter-value/effective-from #inst "2025-01-01"
    :parameter-value/decimal-value  1200000M
    :parameter-value/citation       "CGI Art. 238 quindecies — degressive cliff relevée à €1 200 000 par LFI 2024"}

   ;; QPFC 12 % titres de participation — stable since 2013
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§219.QPFC-rate"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  0.12M
    :parameter-value/citation       "CGI Art. 219 I a quinquies — QPFC 12 % stable depuis LF 2013 (relevée de 10 %; CE 2017-06-14 n° 400855 confirme base brute)"}

   {:parameter-value/parameter      [:parameter/code "FR.CGT.§219.holding-period-years"]
    :parameter-value/effective-from #inst "2007-01-01"
    :parameter-value/decimal-value  2M
    :parameter-value/citation       "CGI Art. 219 I a quinquies — détention minimale 2 ans (stable)"}

   ;; IP box brevets — 10 % flat since 2019 reform (Art. 14 LF 2019)
   {:parameter-value/parameter      [:parameter/code "FR.CGT.§238.IP-box-rate"]
    :parameter-value/effective-from #inst "2019-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "CGI Art. 238 — IP box 10 % (loi de finances 2019 — passage de 15 % à 10 % + introduction du ratio nexus)"}])

;; ============================================================================
;; Provisions — v1 is empty
;; ============================================================================

(def provisions
  "FR CGT does not currently encode provisions through the
   `kontor.statute` evaluator. The reason (note 128 §5.4):

   - The PFU vs barème election is per-tax-unit and per-period — the
     provider routes natively from `:tax-unit :pfu-or-bareme` (no
     `:schedule-override` swap needed; the substrate evaluator's
     condition-based gating would force a per-disposal flag which
     contradicts the per-foyer election shape).
   - The per-asset-class PS-rate map (note 128 §5.4) lives in the
     provider; expressing each carve-out as its own provision would
     conflate eligibility with rate-selection.
   - §151 septies + §238 quindecies exemptions are cliff-tests on
     consumer-supplied facts — provider-resolved.

   The slot is reserved for future amendment-driven provisions (an
   abattement-durée date-shift, a sursis activation, etc.) — the
   install! function transacts even when empty to keep the API stable."
  [])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install FR CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))
