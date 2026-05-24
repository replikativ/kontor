(ns kontor.l10n-de.cgt-statute
  "DE capital-gains tax — §8b KStG (corp 95/5) + §6b EStG (rollover) +
   §17 EStG (qualified shareholding Teileinkünfteverfahren) + §20 EStG
   (Abgeltungsteuer flat 25 %) + §23 EStG (private speculation) —
   encoded as `kontor.statute` data per ADR-101. Research note 113.

   DE does NOT have ONE capital-gains tax. It has FIVE overlapping
   statutory shapes which split cleanly along the corporate /
   individual axis. This file encodes the RATES + THRESHOLDS as
   `:parameter` rows and the SURTAX overlay (Soli on §20
   Abgeltungsteuer) as a `:provision`; the lane classification +
   bucket-isolated loss netting + holding-period cutoffs are all
   provider-internal (provider sees the disposal-level data the
   statute parameters do not encode).

   Six parameter families (date-keyed so a future rate change is a
   one-row migration):

   - **§8b KStG** — DE.KStG.§8b.exemption-rate (95 %) and
     DE.KStG.§8b.addback-rate (5 %).
   - **§20 EStG / Abgeltungsteuer** — DE.EStG.§20.flat-rate (25 %).
   - **§17 EStG** — DE.EStG.§17.freibetrag (€9 060) +
     DE.EStG.§17.taper-start (€36 100) + DE.EStG.§17.inclusion-rate
     (60 % Teileinkünfteverfahren).
   - **§23 EStG** — DE.EStG.§23.freigrenze (€1 000) +
     DE.EStG.§23.real-estate-cutoff-days (3650 = 10 y) +
     DE.EStG.§23.movable-cutoff-days (365 = 1 y).
   - **Soli on Abgeltungsteuer** — DE.Soli.rate (5.5 %) is already
     installed by the CIT statute; we re-reference rather than re-add
     (parameters are idempotent on `:parameter/code`).
   - **KSt rate** — same observation; the CIT statute installs
     DE.KSt.rate (15 %).

   One provision in this file: `DE-SolZG-§4-on-§20` — Soli surtax on
   the §20 Abgeltungsteuer component (scoped via `:eq :component
   :de-§20`, parallel to the CIT statute's KSt-scoped Soli).

   Everything else — §8b lane (the 5 % add-back surfaces in
   `:cit-base-additions` for the CIT provider, NOT a Soli surtax),
   §17 Teileinkünfte fold (60 % inclusion + Freibetrag taper), §23
   cutoff math + Freigrenze — is provider-internal because it depends
   on per-disposal data the statute does not see.

   Citations point at gesetze-im-internet.de for the statute text;
   parameter-values carry their own citations (BMF / EStG / KStG
   references)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "DE CGT parameter definitions. Values live in `parameter-values`
   keyed by `:effective-from`."
  [;; --- §8b KStG — 95/5 participation exemption ----------------------------
   {:parameter/code         "DE.KStG.§8b.cgt-exemption-rate"
    :parameter/label        "§8b Abs. 2 KStG — fraction of corporate share-disposal gain exempt (95 %)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/kstg_1977/__8b.html"}

   {:parameter/code         "DE.KStG.§8b.cgt-addback-rate"
    :parameter/label        "§8b Abs. 3 KStG — fraction of corporate share-disposal gain treated as fiktive Betriebsausgaben (5 %)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/kstg_1977/__8b.html"}

   ;; --- §17 EStG — Teileinkünfteverfahren + Freibetrag --------------------
   {:parameter/code         "DE.EStG.§17.inclusion-rate"
    :parameter/label        "§3 Nr. 40 / §3c Abs. 2 EStG — Teileinkünfte inclusion rate (60 % of gain taxable)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__17.html"}

   {:parameter/code         "DE.EStG.§17.freibetrag"
    :parameter/label        "§17 Abs. 3 S. 1 EStG — Freibetrag on Teileinkünfte-veranlagter gain (€9 060)"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__17.html"}

   {:parameter/code         "DE.EStG.§17.taper-start"
    :parameter/label        "§17 Abs. 3 S. 2 EStG — Freibetrag taper-start (Abschmelzungsgrenze, €36 100)"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__17.html"}

   ;; --- §20 EStG — Abgeltungsteuer ---------------------------------------
   {:parameter/code         "DE.EStG.§20.flat-rate"
    :parameter/label        "§32d Abs. 1 EStG — Abgeltungsteuer flat rate (25 %)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__32d.html"}

   ;; --- §23 EStG — private speculation -----------------------------------
   {:parameter/code         "DE.EStG.§23.freigrenze"
    :parameter/label        "§23 Abs. 3 S. 5 EStG — Freigrenze (€1 000 — hard threshold, not a Freibetrag)"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__23.html"}

   {:parameter/code         "DE.EStG.§23.real-estate-cutoff-days"
    :parameter/label        "§23 Abs. 1 Nr. 1 EStG — real-estate Spekulationsfrist (10 y = 3650 d)"
    :parameter/jurisdiction :de
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__23.html"}

   {:parameter/code         "DE.EStG.§23.movable-cutoff-days"
    :parameter/label        "§23 Abs. 1 Nr. 2 EStG — movable-property Spekulationsfrist (1 y = 365 d)"
    :parameter/jurisdiction :de
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__23.html"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "DE CGT parameter values with their statutory effective windows.
   The §8b 95/5 split has been stable since the 2004 SEStEG cleanup;
   the §20 Abgeltungsteuer dates from the 2009 Unternehmenssteuer-
   reform; the §17 €9 060 Freibetrag / €36 100 taper has been stable
   for many years; the §23 €1 000 Freigrenze was raised from €600 via
   the WtChancenG (BGBl. I 2023 Nr. 412) effective 2024-01-01."
  [;; --- §8b KStG ---------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "DE.KStG.§8b.cgt-exemption-rate"]
    :parameter-value/effective-from #inst "2004-01-01"
    :parameter-value/decimal-value  0.95M
    :parameter-value/citation       "§8b Abs. 2 + Abs. 3 KStG — 95 % exemption stable since SEStEG"}

   {:parameter-value/parameter      [:parameter/code "DE.KStG.§8b.cgt-addback-rate"]
    :parameter-value/effective-from #inst "2004-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "§8b Abs. 3 S. 1 KStG — 5 % Pauschalzuschlag fiktive Betriebsausgaben"}

   ;; --- §17 EStG ---------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "DE.EStG.§17.inclusion-rate"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  0.60M
    :parameter-value/citation       "§3 Nr. 40 lit. c EStG + §3c Abs. 2 EStG — Teileinkünfteverfahren 60 % seit Abgeltungsteuer 2009"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§17.freibetrag"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  9060M
    :parameter-value/citation       "§17 Abs. 3 S. 1 EStG — €9 060 Freibetrag stable since Unternehmenssteuerreform 2008"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§17.taper-start"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  36100M
    :parameter-value/citation       "§17 Abs. 3 S. 2 EStG — €36 100 Abschmelzungsgrenze (1:1 taper above)"}

   ;; --- §20 EStG ---------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "DE.EStG.§20.flat-rate"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "§32d Abs. 1 EStG — 25 % Abgeltungsteuer seit Unternehmenssteuerreform 2008"}

   ;; --- §23 EStG ---------------------------------------------------------
   {:parameter-value/parameter       [:parameter/code "DE.EStG.§23.freigrenze"]
    :parameter-value/effective-from  #inst "2008-01-01"
    :parameter-value/effective-until #inst "2024-01-01"
    :parameter-value/decimal-value   600M
    :parameter-value/citation        "§23 Abs. 3 S. 5 EStG pre-WtChancenG (€600 Freigrenze)"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§23.freigrenze"]
    :parameter-value/effective-from #inst "2024-01-01"
    :parameter-value/decimal-value  1000M
    :parameter-value/citation       "§23 Abs. 3 S. 5 EStG (WtChancenG, BGBl. I 2023 Nr. 412 — €1 000 ab 2024)"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§23.real-estate-cutoff-days"]
    :parameter-value/effective-from #inst "1999-01-01"
    :parameter-value/decimal-value  3650M
    :parameter-value/citation       "§23 Abs. 1 Nr. 1 EStG — 10-Jahres-Frist seit Steuerentlastungsgesetz 1999"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§23.movable-cutoff-days"]
    :parameter-value/effective-from #inst "1999-01-01"
    :parameter-value/decimal-value  365M
    :parameter-value/citation       "§23 Abs. 1 Nr. 2 EStG — 1-Jahres-Frist für andere Wirtschaftsgüter"}])

;; ============================================================================
;; Provisions — Soli on §20 Abgeltungsteuer
;; ============================================================================

(def provisions
  "DE CGT statutory provisions. Only Soli on §20 is encoded here as a
   `:provision` — the rest of DE CGT logic is provider-internal lane
   classification and bucket-isolated loss netting (the kind of
   per-disposal arithmetic the statute substrate does not see).

   The §8b 5 % add-back is NOT a Soli surtax — it surfaces in
   `:cit-base-additions` for the CIT provider to compose into the KSt
   base; that flow goes through the existing DE-KStG-§8b-Abs-5
   provision (CIT statute) which fires on the
   `:inputs :participation-gain` consumer-supplied fact."

  [;; --------------------------------------------------------------------
   ;; §4 SolZG on §20 Abgeltungsteuer — 5.5 % surtax on the §20 component
   ;; --------------------------------------------------------------------
   {:provision/code            "DE-SolZG-§4-on-§20"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "§4 SolZG — Solidaritätszuschlag (5.5 %) on §20 Abgeltungsteuer"
    :provision/citation        "https://www.gesetze-im-internet.de/solzg_1995/__4.html"
    :provision/effective-from  #inst "2009-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :de-§20])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :soli-on-§20
                                        :label "Solidaritätszuschlag (5.5 %) auf §20 Abgeltungsteuer"
                                        :amount-from :compute-fn
                                        :fn :de-soli-on-abgeltungsteuer})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install DE CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs, so re-running the install is a no-op on unchanged
   rows. Note that DE.Soli.rate + DE.KSt.rate are owned by the CIT
   statute; this installer references but does NOT re-add them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
