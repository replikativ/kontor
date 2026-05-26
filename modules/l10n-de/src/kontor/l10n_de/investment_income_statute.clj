(ns kontor.l10n-de.investment-income-statute
  "DE investment-income tax — §20 EStG (dividends + interest) under the
   Abgeltungsteuer regime — encoded as `kontor.statute` data per
   ADR-101. Research note 147.

   THE KEY INSIGHT (note 147 §3.3): most parameters this provider
   needs ALREADY EXIST in `cgt-statute.clj` + `cit-statute.clj`:

     - `DE.EStG.§20.flat-rate` (25 %) — installed by `cgt-statute`.
     - `DE.Soli.rate` (5.5 %) — installed by `cit-statute`.
     - `DE.EStG.§17.inclusion-rate` (60 %) — installed by
       `cgt-statute`; reused here for §32d Abs. 2 Nr. 3
       Teileinkünfteverfahren on dividends.

   This namespace adds:

     - `DE.EStG.§20.sparer-pauschbetrag.single` — €1 000 from
       2023-01-01 (was €801 from 2009-01-01 .. 2022-12-31).
     - `DE.EStG.§20.sparer-pauschbetrag.joint`  — €2 000 from
       2023-01-01 (was €1 602 from 2009-01-01 .. 2022-12-31).
     - `DE.KiSt.rate.by-bw` — 8 % (BY / BW) since 1995-01-01.
     - `DE.KiSt.rate.other` — 9 % standard rate since 1995-01-01.

   ONE new provision — KiStG-on-§20 — the Kirchensteuer surtax on
   the §20 Abgeltungsteuer. Sibling of cgt-statute's
   `DE-SolZG-§4-on-§20`; the compute-fn `:de-kist-on-abgeltungsteuer`
   reads `(get-in ctx [:tax-unit :church-tax-rate])` (a BigDecimal
   0M / 0.08M / 0.09M) and multiplies it by the running gross
   Abgeltungsteuer.

   The § 32d Abs. 1 formula's church-tax-aware
   **Sonderausgaben-effect** ((e − 4q) / (4 + k)) is handled
   PROVIDER-INTERNAL — when `k > 0` the provider uses an effective
   flat rate of `1 / (4 + k)` instead of `0.25`. The parameter
   `DE.EStG.§20.flat-rate` (0.25) remains untouched; the formula
   adjustment is a function of the consumer-supplied
   `:tax-unit :church-tax-rate`. NO new substrate operator."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "DE investment-income parameter definitions. The §20 flat-rate,
   §17 inclusion-rate, and Soli rate are owned by the CGT + CIT
   statutes; this file adds only what's unique to the income side."
  [{:parameter/code         "DE.EStG.§20.sparer-pauschbetrag.single"
    :parameter/label        "§20 Abs. 9 S. 1 EStG — Sparer-Pauschbetrag (single filer)"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__20.html"}

   {:parameter/code         "DE.EStG.§20.sparer-pauschbetrag.joint"
    :parameter/label        "§20 Abs. 9 S. 2 EStG — Sparer-Pauschbetrag (joint filers)"
    :parameter/jurisdiction :de
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gesetze-im-internet.de/estg/__20.html"}

   {:parameter/code         "DE.KiSt.rate.by-bw"
    :parameter/label        "KiStG BY/BW — Kirchensteuer 8 % (Bayern + Baden-Württemberg)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.bzst.de/DE/Unternehmen/Kapitalertraege/KirchensteuerAbgeltungsteuer/kirchensteuerabgeltungsteuer_node.html"}

   {:parameter/code         "DE.KiSt.rate.other"
    :parameter/label        "KiStG übrige Länder — Kirchensteuer 9 % (standard)"
    :parameter/jurisdiction :de
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.bzst.de/DE/Unternehmen/Kapitalertraege/KirchensteuerAbgeltungsteuer/kirchensteuerabgeltungsteuer_node.html"}])

;; ============================================================================
;; Parameter values — date-keyed value history
;; ============================================================================

(def parameter-values
  "DE investment-income parameter values with their statutory
   effective windows. The Sparer-Pauschbetrag carries TWO rows per
   filing status: the pre-2023 €801/€1 602 value (Unternehmenssteuer-
   reform 2008) and the from-2023 €1 000/€2 000 value
   (Zinsanpassungsgesetz, BGBl. I 2022 Nr. 384). The KiSt rates are
   stable since 1995."
  [;; --- Sparer-Pauschbetrag — single (€801 → €1 000) ---------------------
   {:parameter-value/parameter       [:parameter/code "DE.EStG.§20.sparer-pauschbetrag.single"]
    :parameter-value/effective-from  #inst "2009-01-01"
    :parameter-value/effective-until #inst "2023-01-01"
    :parameter-value/decimal-value   801M
    :parameter-value/citation        "§20 Abs. 9 S. 1 EStG (Unternehmenssteuerreform 2008) — €801 from 2009"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§20.sparer-pauschbetrag.single"]
    :parameter-value/effective-from #inst "2023-01-01"
    :parameter-value/decimal-value  1000M
    :parameter-value/citation       "§20 Abs. 9 S. 1 EStG (Zinsanpassungsgesetz, BGBl. I 2022 Nr. 384) — €1 000 ab 2023"}

   ;; --- Sparer-Pauschbetrag — joint (€1 602 → €2 000) --------------------
   {:parameter-value/parameter       [:parameter/code "DE.EStG.§20.sparer-pauschbetrag.joint"]
    :parameter-value/effective-from  #inst "2009-01-01"
    :parameter-value/effective-until #inst "2023-01-01"
    :parameter-value/decimal-value   1602M
    :parameter-value/citation        "§20 Abs. 9 S. 2 EStG (Unternehmenssteuerreform 2008) — €1 602 from 2009"}

   {:parameter-value/parameter      [:parameter/code "DE.EStG.§20.sparer-pauschbetrag.joint"]
    :parameter-value/effective-from #inst "2023-01-01"
    :parameter-value/decimal-value  2000M
    :parameter-value/citation       "§20 Abs. 9 S. 2 EStG (Zinsanpassungsgesetz, BGBl. I 2022 Nr. 384) — €2 000 ab 2023"}

   ;; --- KiSt rates --------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "DE.KiSt.rate.by-bw"]
    :parameter-value/effective-from #inst "1995-01-01"
    :parameter-value/decimal-value  0.08M
    :parameter-value/citation       "KiStG BY Art. 22 + KiStG BW § 5 — 8 % stable rate"}

   {:parameter-value/parameter      [:parameter/code "DE.KiSt.rate.other"]
    :parameter-value/effective-from #inst "1995-01-01"
    :parameter-value/decimal-value  0.09M
    :parameter-value/citation       "KiStG der übrigen Länder — 9 % standard rate"}])

;; ============================================================================
;; Provisions
;; ============================================================================

(def provisions
  "DE investment-income provisions. TWO provisions: Soli + KiSt on
   §20 Abgeltungsteuer. Both reference compute-fns registered by
   `kontor.l10n-de.investment-income-provider`'s `register!` (called
   at namespace load).

   Single install path — `install!` here ships everything (note 159
   §F8, consolidated by note 168 §S2). A consumer who calls only
   `inv-statute/install!` gets the complete tax stack.

   - **`DE-SolZG-§4-on-§20-income`** (priority 100): Soli 5.5 % surtax,
     fires on `:component :de-§20-income`. Sibling of the CGT statute's
     `DE-SolZG-§4-on-§20` (which fires on `:de-§20` for gains). Both
     reference 5.5 % × Abgeltungsteuer; the income side has its own
     compute-fn `:de-soli-on-§20-income` so the two providers stay
     standalone-runnable.

   - **`DE-KiStG-on-§20`** (priority 110, fires after Soli): Kirchensteuer
     surtax 8 % (BY/BW) or 9 % (other Länder), reads `(:church-tax-rate
     (:tax-unit ctx))`. Per § 32d Abs. 1 EStG, Soli + KiSt are both
     surtaxes on the Abgeltungsteuer (linear, so ordering doesn't change
     the sum — the priority follows the CIT-statute ordering pattern).

   - Condition `[:eq :component :de-§20-income]` scopes both to the
     investment-income provider's standalone Abgeltungsteuer component,
     NOT the CGT provider's `:de-§20` (gains) component."

  [;; Soli on §20-income Abgeltungsteuer — sibling of cgt-statute's
   ;; DE-SolZG-§4-on-§20 (gains side). Lives in the IC statute so that
   ;; `install!` here ships the full Soli+KiSt stack.
   {:provision/code            "DE-SolZG-§4-on-§20-income"
    :provision/jurisdiction    :de
    :provision/concept         [:kontor.tax-concept/code :surtax]
    :provision/title           "§4 SolZG — Solidaritätszuschlag (5.5 %) on §20 Abgeltungsteuer (income side)"
    :provision/citation        "https://www.gesetze-im-internet.de/solzg_1995/__4.html"
    :provision/effective-from  #inst "2009-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :de-§20-income])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :soli-on-§20-income
                                        :label "Solidaritätszuschlag (5.5 %) auf §20 Abgeltungsteuer"
                                        :amount-from :compute-fn
                                        :fn :de-soli-on-§20-income})}

   {:provision/code            "DE-KiStG-on-§20"
    :provision/jurisdiction    :de
    :provision/concept         [:kontor.tax-concept/code :surtax]
    :provision/title           "KiStG — Kirchensteuer (8 %/9 %) auf §20 Abgeltungsteuer"
    :provision/citation        "https://www.gesetze-im-internet.de/estg/__32d.html (formula); KiStG der Länder (rates)"
    :provision/effective-from  #inst "1995-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:eq :component :de-§20-income])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :kist-on-§20
                                        :label "Kirchensteuer auf §20 Abgeltungsteuer"
                                        :amount-from :compute-fn
                                        :fn :de-kist-on-abgeltungsteuer})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install DE investment-income statute (parameters + provisions) into
   `conn`. Idempotent via `:parameter/code` + `:provision/code` unique
   identity attrs.

   ASSUMES the DE CIT statute (for `DE.Soli.rate`) AND the DE CGT
   statute (for `DE.EStG.§20.flat-rate` + `DE.EStG.§17.inclusion-rate`)
   have already been installed."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
