(ns kontor.l10n-de.cit-statute
  "DE corporate income tax — KSt + Soli + GewSt — encoded as
   `kontor.statute` data per ADR-101. The first end-to-end consumer
   of the statute-as-data substrate; the reference example for
   per-jurisdiction provider authoring.

   The encoding splits cleanly along the substrate seams:

   - **Parameters** (date-keyed value history) — the statutory rates
     and thresholds: KSt 15%, Soli 5.5%, GewSt Messzahl 3.5%, §8 GewSt
     fractional shares, §9 GewSt real-estate rate, §10 KStG fractional
     shares. Hebesatz is NOT a parameter (varies per municipality and
     comes through `:tax-unit` ctx).

   - **Provisions** (per-jurisdiction rules) — the four adjustment
     paths:
       - DE-KStG-§10 — KSt base-add for non-deductible expenses
       - DE-SolZG-§4 — Soli surtax (5.5% on KSt amount)
       - DE-GewStG-§8 — GewSt base-add for §8 Hinzurechnungen
         (interest, rental, royalties; the consumer supplies the
         post-Freibetrag totals via `:inputs`)
       - DE-GewStG-§9 — GewSt base-deduct for §9 Kürzungen
         (real-estate, qualifying participations)

   - **Scoping** — provisions are scoped to one component (KSt or
     GewSt) via a `:condition [:eq :component <kw>]` predicate; the
     provider sets `:component` in ctx on each per-component pass.
     This sidesteps any need for a new `:provision/component-scope`
     attr — the closed predicate vocabulary already carries it.

   Citations point at gesetze-im-internet.de for the statute text;
   parameter-values carry their own citations (BMF / SolZG / GewStG
   references). Research note 108."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "DE CIT parameter definitions — one row per `:parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`."
  [{:parameter/code         "DE.KSt.rate"
    :parameter/label        "Körperschaftsteuer (KSt) federal flat rate"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/kstg/__23.html"}

   {:parameter/code         "DE.Soli.rate"
    :parameter/label        "Solidaritätszuschlag (Soli) surcharge rate on KSt"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/solzg_1995/__4.html"}

   {:parameter/code         "DE.GewSt.messzahl"
    :parameter/label        "Gewerbesteuer (GewSt) federal Steuermesszahl"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__11.html"}

   {:parameter/code         "DE.GewSt.§8.freibetrag"
    :parameter/label        "§8 Nr. 1 GewStG Freibetrag — applied ONCE to the weighted sum of all six §8 buckets"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.hinzurechnung-fraction"
    :parameter/label        "§8 Nr. 1 GewStG universal ¼ factor — applied AFTER weighted sum minus Freibetrag"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   ;; Per-bucket statutory weights (§8 Nr. 1 a-f). These are stable
   ;; data, but parametrising them lets a future weight amendment be a
   ;; one-row migration (note 120 §1.6 — the law's six sub-letters have
   ;; been stable since 2008-01-01 UntStRefG; the d/e/f weights came in
   ;; via Unternehmensteuerreform).
   {:parameter/code         "DE.GewSt.§8.a-interest-weight"
    :parameter/label        "§8 Nr. 1a — interest expense weight (100%)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.b-annuity-weight"
    :parameter/label        "§8 Nr. 1b — annuities + permanent burdens weight (100%)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.c-silent-partner-weight"
    :parameter/label        "§8 Nr. 1c — silent-partner profit shares weight (100%)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.d-rent-movable-weight"
    :parameter/label        "§8 Nr. 1d — rent on movable property (20% / ein Fünftel)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.e-rent-immovable-weight"
    :parameter/label        "§8 Nr. 1e — rent on immovable property (50% / die Hälfte)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§8.f-royalty-weight"
    :parameter/label        "§8 Nr. 1f — royalty / licence expense weight (25% / ein Viertel)"
    :parameter/jurisdiction :de :parameter/unit :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__8.html"}

   {:parameter/code         "DE.GewSt.§9.real-estate-rate"
    :parameter/label        "§9 Nr. 1 GewStG real-estate reduction rate (1.2% × Einheitswert × 1.4) — SUNSET 2024-12-31"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/gewstg/__9.html"}

   {:parameter/code         "DE.KStG.§8b.exemption-rate"
    :parameter/label        "§8b Abs. 1/2 KStG participation exemption fraction (95% exempt)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/kstg/__8b.html"}])

(def parameter-values
  "DE CIT parameter values with their statutory effective windows.
   Most rates are stable for 15+ years; the schema's date-keyed
   history is here for when amendments land (e.g. a hypothetical
   future KSt rate change is a one-row migration: insert a new
   `:parameter-value` with the new `:effective-from`)."
  [{:parameter-value/parameter      [:parameter/code "DE.KSt.rate"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "§23 Abs. 1 KStG (since 2008 Unternehmenssteuerreform)"}

   {:parameter-value/parameter      [:parameter/code "DE.Soli.rate"]
    :parameter-value/effective-from #inst "1998-01-01"
    :parameter-value/decimal-value  0.055M
    :parameter-value/citation       "§4 SolZG 1995 — stable since 1998"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.messzahl"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.035M
    :parameter-value/citation       "§11 Abs. 2 GewStG (since 2008 Unternehmenssteuerreform)"}

   {:parameter-value/parameter       [:parameter/code "DE.GewSt.§8.freibetrag"]
    :parameter-value/effective-from  #inst "2008-01-01"
    :parameter-value/effective-until #inst "2020-01-01"
    :parameter-value/decimal-value   100000M
    :parameter-value/citation        "§8 Nr. 1 GewStG (UntStRefG 2008 → €100k Freibetrag)"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.freibetrag"]
    :parameter-value/effective-from #inst "2020-01-01"
    :parameter-value/decimal-value  200000M
    :parameter-value/citation       "§8 Nr. 1 GewStG (Zweites Corona-Steuerhilfegesetz raised to €200k, BGBl. I 2020 S. 1512)"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.hinzurechnung-fraction"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "§8 Nr. 1 closing clause: \"ein Viertel der Summe aus …\""}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.a-interest-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  1.00M
    :parameter-value/citation       "§8 Nr. 1a — full amount of interest expense"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.b-annuity-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  1.00M
    :parameter-value/citation       "§8 Nr. 1b — Renten und dauernden Lasten in full"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.c-silent-partner-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  1.00M
    :parameter-value/citation       "§8 Nr. 1c — Gewinnanteile stiller Gesellschafter in full"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.d-rent-movable-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "§8 Nr. 1d — ein Fünftel der Miet- und Pachtzinsen für bewegliche Wirtschaftsgüter"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.e-rent-immovable-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "§8 Nr. 1e — die Hälfte der Miet- und Pachtzinsen für unbewegliche Wirtschaftsgüter"}

   {:parameter-value/parameter      [:parameter/code "DE.GewSt.§8.f-royalty-weight"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "§8 Nr. 1f — ein Viertel der Lizenzaufwendungen"}

   {:parameter-value/parameter       [:parameter/code "DE.GewSt.§9.real-estate-rate"]
    :parameter-value/effective-from  #inst "2008-01-01"
    :parameter-value/effective-until #inst "2025-01-01"
    :parameter-value/decimal-value   0.012M
    :parameter-value/citation        "§9 Nr. 1 S. 1 GewStG pre-JStG-2024 (1.2% × Einheitswert × 1.4) — SUNSET via Grundsteuerreform"}

   {:parameter-value/parameter      [:parameter/code "DE.KStG.§8b.exemption-rate"]
    :parameter-value/effective-from #inst "2004-01-01"
    :parameter-value/decimal-value  0.95M
    :parameter-value/citation       "§8b Abs. 5 KStG — 5% Pauschalzuschlag stable since 2004"}])

;; ============================================================================
;; Provisions — DE CIT statute as :provision data
;; ============================================================================

(def provisions
  "DE CIT statutory provisions encoded for the `kontor.statute`
   evaluator. Conditions reference `:component` (set by the provider
   on each per-component pass — :kst or :gewst) and use vector
   fact-keys `[:inputs <fact>]` to read consumer-supplied facts —
   gating each provision on the presence of its driver fact so an
   absent fact silently no-ops (rather than firing with a nil
   amount). Consequences are compute-fns or `:tax-context-fact`
   amounts, never literal — DE rates and shares live in `:parameter`
   data, NOT inlined here."

  [;; --------------------------------------------------------------------
   ;; KSt — Körperschaftsteuer
   ;; --------------------------------------------------------------------
   ;; §10 KStG — non-deductible expenses add back to taxable income.
   ;; Consumer supplies the total via `:inputs :kst-non-deductibles`.
   ;; (Covers §10 Nr. 1-7: half supervisory-board fees, non-deductible
   ;; business expenses, fines, etc. — kontor doesn't itemize; the
   ;; consumer's accounting system should.)
   {:provision/code            "DE-KStG-§10"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :base-transform-add]
    :provision/title           "§10 KStG — Nicht abzugsfähige Aufwendungen"
    :provision/citation        "https://www.gesetze-im-internet.de/kstg/__10.html"
    :provision/effective-from  #inst "2008-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :kst]
                                        [:gt [:inputs :kst-non-deductibles] 0M]])
    :provision/consequence     (pr-str {:op :base-add
                                        :code :kst-§10
                                        :label "§10 KStG add-back (non-deductible expenses)"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :kst-non-deductibles]})}

   ;; §8b Abs. 5 KStG — 5% of dividend / participation gains stays
   ;; non-deductible even when the underlying gain is 95% exempt.
   ;; Computed via compute-fn (reads the §8b exemption-rate parameter).
   {:provision/code            "DE-KStG-§8b-Abs-5"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :base-transform-add]
    :provision/title           "§8b Abs. 5 KStG — 5% Pauschalzuschlag auf Beteiligungserträge"
    :provision/citation        "https://www.gesetze-im-internet.de/kstg/__8b.html"
    :provision/effective-from  #inst "2004-01-01"
    :provision/priority        200
    :provision/condition       (pr-str [:and
                                        [:eq :component :kst]
                                        [:gt [:inputs :participation-gain] 0M]])
    :provision/consequence     (pr-str {:op :base-add
                                        :code :kst-§8b-addback
                                        :label "§8b 5% non-deductible addback"
                                        :amount-from :compute-fn
                                        :fn :de-§8b-addback})}

   ;; §4 SolZG — Solidaritätszuschlag, 5.5% surtax on the KSt amount.
   ;; Always applies whenever KSt does (no Freigrenze for corps post-2021).
   {:provision/code            "DE-SolZG-§4"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "§4 SolZG — Solidaritätszuschlag (5.5% surtax on KSt)"
    :provision/citation        "https://www.gesetze-im-internet.de/solzg_1995/__4.html"
    :provision/effective-from  #inst "1998-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :kst])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :soli
                                        :label "Solidaritätszuschlag (5.5%)"
                                        :amount-from :compute-fn
                                        :fn :de-soli-on-kst})}

   ;; --------------------------------------------------------------------
   ;; GewSt — Gewerbesteuer
   ;; --------------------------------------------------------------------
   ;; §8 Nr. 1 GewStG — Hinzurechnung von Finanzierungsanteilen.
   ;; ONE provision covers all six sub-letters (a-f) — the statute is
   ;; "ein Viertel der Summe aus a + b + c + d + e + f, soweit die
   ;; Summe €200k übersteigt." So the weighted sum is taken FIRST, the
   ;; Freibetrag applied ONCE, and the universal ¼ once. Note 120 P0-1
   ;; + P0-2 — splitting interest/rental into separate provisions with
   ;; per-category Freibetrag is structurally wrong.
   ;;
   ;; Consumer supplies the six RAW expense buckets via :inputs :gewst-§8
   ;; sub-map (any missing key defaults to 0M). The compute-fn does:
   ;;   weighted-sum = Σ (bucket × weight-parameter)
   ;;   post-freibetrag = max(0, weighted-sum − DE.GewSt.§8.freibetrag)
   ;;   hinzurechnung = post-freibetrag × DE.GewSt.§8.hinzurechnung-fraction
   {:provision/code            "DE-GewStG-§8-Nr-1"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :base-transform-add]
    :provision/title           "§8 Nr. 1 GewStG — Hinzurechnungen Finanzierungsanteile (a-f, ¼ × (Σweighted − €200k))"
    :provision/citation        "https://www.gesetze-im-internet.de/gewstg/__8.html"
    :provision/effective-from  #inst "2008-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :gewst]
                                        [:or
                                         [:gt [:inputs :gewst-§8 :interest] 0M]
                                         [:gt [:inputs :gewst-§8 :annuity] 0M]
                                         [:gt [:inputs :gewst-§8 :silent-partner] 0M]
                                         [:gt [:inputs :gewst-§8 :rent-movable] 0M]
                                         [:gt [:inputs :gewst-§8 :rent-immovable] 0M]
                                         [:gt [:inputs :gewst-§8 :royalties] 0M]]])
    :provision/consequence     (pr-str {:op :base-add
                                        :code :gewst-§8
                                        :label "§8 Nr. 1 Hinzurechnungen (Finanzierungsanteile)"
                                        :amount-from :compute-fn
                                        :fn :de-gewst-§8-hinzurechnung})}

   ;; §9 Nr. 1 GewStG — Kürzung für Grundbesitz. Pre-Grundsteuerreform
   ;; (effective until 2025-01-01): 1.2% × Einheitswert × 1.4 (the
   ;; "1.2% × bewertungsrechtlicher Wert" rule). Consumer supplies the
   ;; already-×1.4 value via `:inputs :gewst-real-estate-value`.
   {:provision/code            "DE-GewStG-§9-Nr-1-pre-2025"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :base-transform-deduct]
    :provision/title           "§9 Nr. 1 GewStG (pre-2025) — 1.2% × Einheitswert × 1.4"
    :provision/citation        "https://www.gesetze-im-internet.de/gewstg/__9.html"
    :provision/effective-from  #inst "2008-01-01"
    :provision/effective-until #inst "2025-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :gewst]
                                        [:gt [:inputs :gewst-real-estate-value] 0M]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :gewst-§9-real-estate
                                        :label "§9 Nr. 1 (pre-2025) real-estate deduction"
                                        :amount-from :compute-fn
                                        :fn :de-gewst-§9-real-estate})}

   ;; §9 Nr. 1 GewStG — Kürzung für Grundbesitz. Post-Grundsteuerreform
   ;; (effective from 2025-01-01 per JStG 2024 / BGBl. I 2024 Nr. 387):
   ;; deduction equals the actual Grundsteuer paid as a business
   ;; expense. Consumer supplies `:inputs :grundsteuer-paid`.
   {:provision/code            "DE-GewStG-§9-Nr-1-from-2025"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :base-transform-deduct]
    :provision/title           "§9 Nr. 1 GewStG (from 2025) — actual Grundsteuer paid"
    :provision/citation        "https://www.gesetze-im-internet.de/gewstg/__9.html"
    :provision/effective-from  #inst "2025-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :gewst]
                                        [:gt [:inputs :grundsteuer-paid] 0M]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :gewst-§9-grundsteuer
                                        :label "§9 Nr. 1 (from 2025) Grundsteuer-deduction"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :grundsteuer-paid]})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install DE CIT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs, so re-running the install is a no-op on unchanged
   rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
