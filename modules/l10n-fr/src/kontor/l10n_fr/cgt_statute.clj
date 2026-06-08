(ns kontor.l10n-fr.cgt-statute
  "FR capital-gains tax — five-regime substrate — encoded as
   `kontor.tax.statute` parameter / provision data per ADR-101.

   FR does NOT have one CGT regime — it has FIVE overlapping shapes
, and a provider classifies each `:disposal` into the
   right lane by `:kontor.disposal/asset-class` + `:kontor.disposal/subject-form`:

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

   The kernel evaluator (`kontor.tax.statute/apply-provisions`) is the engine;
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
     the per-asset-class PS-rate map live in the
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

   ## Out of scope for v1

   - **Sursis d'imposition 150-0 B** — the share-for-share exchange is
     non-recognition; the substrate convention is to NOT emit a disposal
     at all (lot's `:acquired-on` rides through). No statute needed.
   - **Report d'imposition 150-0 B ter** (apport-cession) — the data
     shape uses `:rollover-into-asset` / `-amount` / `-deadline` but
     the v1 provider does NOT check the 60 % / 70 % reinvest at the
     deadline; deferred
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
   per `:kontor.disposal/asset-class`."
  [;; --- Mobilière PFU layer (CGI Art. 200 A) -------------------------
   {:kontor.parameter/code         "FR.CGT.PFU.IR-rate"
    :kontor.parameter/label        "PFU IR component (12.8 % flat tax) — CGI Art. 200 A 1°"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000045760882"}

   ;; PS split per LFSS 2026 effective-date semantics:
   ;; - revenus du PATRIMOINE (plus-values mobilières, revenus fonciers,
   ;;   rentes viagères à titre onéreux) — hike RETROACTIVE to 2025-income;
   ;; - revenus de PLACEMENT (dividendes, intérêts, produits d'assurance-vie,
   ;;   gains soumis au PFLU) — hike applies to revenus versés à compter
   ;;   du 1er janvier 2026.
   ;; Mobilière disposals route via :patrimoine-rate; PEA pool / dividend
   ;; income / placement income route via :placement-rate.
   {:kontor.parameter/code         "FR.CGT.PS.patrimoine-rate"
    :kontor.parameter/label        "Prélèvements sociaux — revenus du patrimoine (plus-values mobilières + revenus fonciers + rentes viagères constituées à titre onéreux) — CSG 10.6 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 18.6 % post-LFSS 2026, RÉTROACTIF aux revenus 2025"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006073189/LEGIARTI000006740051/"}

   {:kontor.parameter/code         "FR.CGT.PS.placement-rate"
    :kontor.parameter/label        "Prélèvements sociaux — revenus de placement (dividendes, intérêts, produits d'assurance-vie, certains gains soumis au PFLU) — CSG 10.6 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 18.6 % post-LFSS 2026, applicable aux revenus versés à compter du 1er janvier 2026"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006073189/LEGIARTI000006740051/"}

   {:kontor.parameter/code         "FR.CGT.PS.real-estate-rate"
    :kontor.parameter/label        "Prélèvements sociaux carve-out rate — real estate / life insurance / PEL / CEL / PEP / revenus fonciers (STAYS at 17.2 % per LFSS 2026 exclusion)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026"}

   ;; --- Immobilière (CGI Art. 150 U + 200 B + 1609 nonies G) ----------
   {:kontor.parameter/code         "FR.CGT.Immo.IR-rate"
    :kontor.parameter/label        "Plus-values immobilières — IR taux fixe (19 %)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000006304108"}

   {:kontor.parameter/code         "FR.CGT.Immo.surtaxe-floor"
    :kontor.parameter/label        "Surtaxe plus-values immobilières (CGI Art. 1609 nonies G) — floor below which surtaxe = 0 (€50 000)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006069577/LEGIARTI000027577763/"}

   ;; Abattement-durée ladder values for immobilière. The provider folds;
   ;; the parameters here are for citation + future amendment audit.
   {:kontor.parameter/code         "FR.CGT.Immo.abat-IR-y6to21"
    :kontor.parameter/label        "Abattement IR plus-value immobilière — années 6 à 21 (6 %/an)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:kontor.parameter/code         "FR.CGT.Immo.abat-IR-y22"
    :kontor.parameter/label        "Abattement IR plus-value immobilière — année 22 (4 %, terminale)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:kontor.parameter/code         "FR.CGT.Immo.abat-PS-y6to21"
    :kontor.parameter/label        "Abattement PS plus-value immobilière — années 6 à 21 (1.65 %/an)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:kontor.parameter/code         "FR.CGT.Immo.abat-PS-y22"
    :kontor.parameter/label        "Abattement PS plus-value immobilière — année 22 (1.6 %)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   {:kontor.parameter/code         "FR.CGT.Immo.abat-PS-y23to30"
    :kontor.parameter/label        "Abattement PS plus-value immobilière — années 23 à 30 (9 %/an)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410"}

   ;; --- Plus-values professionnelles long-terme ---------------------
   {:kontor.parameter/code         "FR.CGT.ProLT.IR-rate"
    :kontor.parameter/label        "Plus-values pro long-terme — IR (12.8 %, CGI Art. 39 quindecies)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577/LEGISCTA000006197185/"}

   ;; --- §151 septies revenue-tested exemption -----------------------
   {:kontor.parameter/code         "FR.CGT.§151-septies.threshold-services-full"
    :kontor.parameter/label        "§151 septies — services BIC/BNC: full-exemption ceiling (€90 000 turnover)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:kontor.parameter/code         "FR.CGT.§151-septies.threshold-services-degressive"
    :kontor.parameter/label        "§151 septies — services BIC/BNC: degressive ceiling (€126 000 turnover)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:kontor.parameter/code         "FR.CGT.§151-septies.threshold-goods-full"
    :kontor.parameter/label        "§151 septies — ventes marchandises / hébergement: full-exemption ceiling (€250 000 turnover)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   {:kontor.parameter/code         "FR.CGT.§151-septies.threshold-goods-degressive"
    :kontor.parameter/label        "§151 septies — ventes marchandises: degressive ceiling (€350 000 turnover)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469"}

   ;; --- §238 quindecies transmission-d'entreprise exemption ----------
   ;; Note 141 split: the LFI 2024 raise to €700k/€1.2M is reserved
   ;; to AGRICULTURAL transmissions only (CGI Art. 238 quindecies VII bis
   ;; — young-farmer installation aid). Standard non-agricultural
   ;; transmissions stay at the legacy €500k/€1M cliffs. The provider
   ;; routes per consumer-supplied
   ;;   :inputs :238-quindecies {:activity :agricultural | :standard}
   ;; defaulting to :standard.
   {:kontor.parameter/code         "FR.CGT.§238-quindecies.threshold-full"
    :kontor.parameter/label        "§238 quindecies — STANDARD non-agricultural full-exemption value cliff (€500 000, stable)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   {:kontor.parameter/code         "FR.CGT.§238-quindecies.threshold-degressive"
    :kontor.parameter/label        "§238 quindecies — STANDARD non-agricultural degressive-band upper cliff (€1 000 000, stable)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   {:kontor.parameter/code         "FR.CGT.§238-quindecies.agri-threshold-full"
    :kontor.parameter/label        "§238 quindecies VII bis — AGRICULTURAL transmissions full-exemption value cliff (€700 000 from FY-2025; €500 000 prior)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   {:kontor.parameter/code         "FR.CGT.§238-quindecies.agri-threshold-degressive"
    :kontor.parameter/label        "§238 quindecies VII bis — AGRICULTURAL transmissions degressive-band upper cliff (€1 200 000 from FY-2025; €1 000 000 prior)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496"}

   ;; --- IS-side titres de participation (CGI Art. 219 I a quinquies) -
   {:kontor.parameter/code         "FR.CGT.§219.QPFC-rate"
    :kontor.parameter/label        "Titres de participation — quote-part de frais et charges (CGI Art. 219, I a quinquies — 12 %)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   {:kontor.parameter/code         "FR.CGT.§219.holding-period-years"
    :kontor.parameter/label        "Titres de participation — minimum holding period for the régime (2 years)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :years
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   ;; --- Brevets / IP box (CGI Art. 238) ------------------------------
   {:kontor.parameter/code         "FR.CGT.§238.IP-box-rate"
    :kontor.parameter/label        "Régime IP box (brevets, logiciels, COV, certificats) — taux réduit 10 % (CGI Art. 238)"
    :kontor.parameter/jurisdiction :fr
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000037946060"}])

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
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.PFU.IR-rate"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.128M
    :kontor.parameter-value/citation       "CGI Art. 200 A 1° — PFU 12.8 % stable since loi de finances 2018"}

   ;; PS revenus du patrimoine (mobilière + revenus fonciers + rentes
   ;; viagères à titre onéreux) — 17.2 % through 2024-12-31, then 18.6 %
   ;; RÉTROACTIF aux revenus 2025 (LFSS 2026 — DLA Piper / Actu-Juridique /
   ;; TGS France;.2).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "FR.CGT.PS.patrimoine-rate"]
    :kontor.parameter-value/effective-from  #inst "2018-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   0.172M
    :kontor.parameter-value/citation        "CSG 9.2 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 17.2 % (2018-2024)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.PS.patrimoine-rate"]
    :kontor.parameter-value/effective-from #inst "2025-01-01"
    :kontor.parameter-value/decimal-value  0.186M
    :kontor.parameter-value/citation       "LFSS 2026 (loi 2025-1403) — CSG 9.2 → 10.6 % → PS 18.6 %, application RÉTROACTIVE aux revenus du patrimoine perçus dès 2025 (DLA Piper / TGS France / Actu-Juridique commentary)"}

   ;; PS revenus de placement (dividendes, intérêts, AV, gains PFLU) —
   ;; 17.2 % through 2025-12-31, then 18.6 % from 2026-01-01 (forward,
   ;; date-of-payment semantics).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "FR.CGT.PS.placement-rate"]
    :kontor.parameter-value/effective-from  #inst "2018-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   0.172M
    :kontor.parameter-value/citation        "CSG 9.2 % + CRDS 0.5 % + prélèvement solidarité 7.5 % = 17.2 % (2018-2025)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.PS.placement-rate"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  0.186M
    :kontor.parameter-value/citation       "LFSS 2026 (loi 2025-1403) — CSG 9.2 → 10.6 % → PS 18.6 % sur revenus de placement versés à compter du 1er janvier 2026"}

   ;; PS real-estate carve-out — stays at 17.2 %
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.PS.real-estate-rate"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.172M
    :kontor.parameter-value/citation       "LFSS 2026 EXCLUDES real estate / life insurance / PEL / CEL / PEP / revenus fonciers from the CSG raise — PS stays 17.2 %"}

   ;; Immobilière IR — 19 % flat
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.IR-rate"]
    :kontor.parameter-value/effective-from #inst "2012-02-01"
    :kontor.parameter-value/decimal-value  0.19M
    :kontor.parameter-value/citation       "CGI Art. 200 B — 19 % stable since 2012"}

   ;; Immobilière surtaxe — €50 k threshold
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.surtaxe-floor"]
    :kontor.parameter-value/effective-from #inst "2013-01-01"
    :kontor.parameter-value/decimal-value  50000M
    :kontor.parameter-value/citation       "CGI Art. 1609 nonies G — surtaxe progressive 2 → 6 % au-dessus de €50 000 stable"}

   ;; Immobilière abattement ladder rates (the provider folds)
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.abat-IR-y6to21"]
    :kontor.parameter-value/effective-from #inst "2014-09-01"
    :kontor.parameter-value/decimal-value  0.06M
    :kontor.parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement IR 6 %/an années 6 à 21 (loi 2014)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.abat-IR-y22"]
    :kontor.parameter-value/effective-from #inst "2014-09-01"
    :kontor.parameter-value/decimal-value  0.04M
    :kontor.parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement IR 4 % année 22 (terminale → exonération IR à 22 ans)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.abat-PS-y6to21"]
    :kontor.parameter-value/effective-from #inst "2014-09-01"
    :kontor.parameter-value/decimal-value  0.0165M
    :kontor.parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 1.65 %/an années 6 à 21"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.abat-PS-y22"]
    :kontor.parameter-value/effective-from #inst "2014-09-01"
    :kontor.parameter-value/decimal-value  0.016M
    :kontor.parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 1.6 % année 22"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.Immo.abat-PS-y23to30"]
    :kontor.parameter-value/effective-from #inst "2014-09-01"
    :kontor.parameter-value/decimal-value  0.09M
    :kontor.parameter-value/citation       "BOI-RFPI-PVI-20-20 — abattement PS 9 %/an années 23 à 30 (→ exonération PS à 30 ans)"}

   ;; Plus-values pro long-terme — IR 12.8 %
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.ProLT.IR-rate"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.128M
    :kontor.parameter-value/citation       "CGI Art. 39 quindecies — IR plus-value pro LT 12.8 % (aligné PFU)"}

   ;; §151 septies thresholds — stable since 2008
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§151-septies.threshold-services-full"]
    :kontor.parameter-value/effective-from #inst "2008-01-01"
    :kontor.parameter-value/decimal-value  90000M
    :kontor.parameter-value/citation       "CGI Art. 151 septies II — services BIC/BNC exonération totale CA ≤ €90 000"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§151-septies.threshold-services-degressive"]
    :kontor.parameter-value/effective-from #inst "2008-01-01"
    :kontor.parameter-value/decimal-value  126000M
    :kontor.parameter-value/citation       "CGI Art. 151 septies II — services BIC/BNC band supérieure €90 001-€126 000"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§151-septies.threshold-goods-full"]
    :kontor.parameter-value/effective-from #inst "2008-01-01"
    :kontor.parameter-value/decimal-value  250000M
    :kontor.parameter-value/citation       "CGI Art. 151 septies II — marchandises / hébergement exonération totale CA ≤ €250 000"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§151-septies.threshold-goods-degressive"]
    :kontor.parameter-value/effective-from #inst "2008-01-01"
    :kontor.parameter-value/decimal-value  350000M
    :kontor.parameter-value/citation       "CGI Art. 151 septies II — marchandises band supérieure €250 001-€350 000"}

   ;; §238 quindecies STANDARD non-agricultural — €500k / €1M, stable
   ;; since 2006 (NOT raised by LFI 2024 — the €700k / €1.2M raise is
   ;; reserved to agricultural transmissions per VII bis;.8
   ;; + Bpifrance Création + Légifrance current text).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§238-quindecies.threshold-full"]
    :kontor.parameter-value/effective-from #inst "2006-01-01"
    :kontor.parameter-value/decimal-value  500000M
    :kontor.parameter-value/citation       "CGI Art. 238 quindecies I — full-exemption cliff €500 000 pour les transmissions non-agricoles (stable depuis 2006 ; le relèvement LFI 2024 ne concerne que les transmissions agricoles VII bis)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§238-quindecies.threshold-degressive"]
    :kontor.parameter-value/effective-from #inst "2006-01-01"
    :kontor.parameter-value/decimal-value  1000000M
    :kontor.parameter-value/citation       "CGI Art. 238 quindecies I — degressive cliff €1 000 000 pour les transmissions non-agricoles (stable)"}

   ;; §238 quindecies VII bis AGRICULTURAL — pre-2025 €500k / €1M,
   ;; post-2025 €700k / €1.2M (LFI 2024, exercices ouverts à compter
   ;; du 1er janvier 2025 — aide à la transmission agricole / installation
   ;; jeunes agriculteurs).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "FR.CGT.§238-quindecies.agri-threshold-full"]
    :kontor.parameter-value/effective-from  #inst "2006-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   500000M
    :kontor.parameter-value/citation        "CGI Art. 238 quindecies VII bis — transmissions agricoles, cliff €500 000 (pré-LFI 2024)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§238-quindecies.agri-threshold-full"]
    :kontor.parameter-value/effective-from #inst "2025-01-01"
    :kontor.parameter-value/decimal-value  700000M
    :kontor.parameter-value/citation       "CGI Art. 238 quindecies VII bis — transmissions agricoles, cliff relevée à €700 000 par LFI 2024 (exercices ouverts à compter du 1er janvier 2025)"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "FR.CGT.§238-quindecies.agri-threshold-degressive"]
    :kontor.parameter-value/effective-from  #inst "2006-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   1000000M
    :kontor.parameter-value/citation        "CGI Art. 238 quindecies VII bis — transmissions agricoles, degressive cliff €1 000 000 (pré-LFI 2024)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§238-quindecies.agri-threshold-degressive"]
    :kontor.parameter-value/effective-from #inst "2025-01-01"
    :kontor.parameter-value/decimal-value  1200000M
    :kontor.parameter-value/citation       "CGI Art. 238 quindecies VII bis — transmissions agricoles, degressive cliff relevée à €1 200 000 par LFI 2024"}

   ;; QPFC 12 % titres de participation — stable since 2013
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§219.QPFC-rate"]
    :kontor.parameter-value/effective-from #inst "2013-01-01"
    :kontor.parameter-value/decimal-value  0.12M
    :kontor.parameter-value/citation       "CGI Art. 219 I a quinquies — QPFC 12 % stable depuis LF 2013 (relevée de 10 %; CE 2017-06-14 n° 400855 confirme base brute)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§219.holding-period-years"]
    :kontor.parameter-value/effective-from #inst "2007-01-01"
    :kontor.parameter-value/decimal-value  2M
    :kontor.parameter-value/citation       "CGI Art. 219 I a quinquies — détention minimale 2 ans (stable)"}

   ;; IP box brevets — 10 % flat since 2019 reform (Art. 14 LF 2019)
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "FR.CGT.§238.IP-box-rate"]
    :kontor.parameter-value/effective-from #inst "2019-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "CGI Art. 238 — IP box 10 % (loi de finances 2019 — passage de 15 % à 10 % + introduction du ratio nexus)"}])

;; ============================================================================
;; Provisions — v1 is empty
;; ============================================================================

(def provisions
  "FR CGT does not currently encode provisions through the
   `kontor.tax.statute` evaluator. The reason:

   - The PFU vs barème election is per-tax-unit and per-period — the
     provider routes natively from `:tax-unit :pfu-or-bareme` (no
     `:schedule-override` swap needed; the substrate evaluator's
     condition-based gating would force a per-disposal flag which
     contradicts the per-foyer election shape).
   - The per-asset-class PS-rate map lives in the
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
   Idempotent — `:kontor.parameter/code` and `:kontor.provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))
