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
  "DE investment-income provisions. ONE provision: the Kirchensteuer
   surtax on §20 Abgeltungsteuer.

   - **Priority 110** (after Soli-on-§20 at 100) so the Soli is folded
     FIRST onto the gross Abgeltungsteuer; the KiSt surtax operates on
     the same `:running` after Soli has been added. Per § 32d Abs. 1
     EStG, Soli + KiSt are both surtaxes on the Abgeltungsteuer; the
     ordering does not change the sum (linear in the running base) but
     the convention follows the CIT-statute ordering pattern.

   - **Condition** `[:eq :component :de-§20-income]` — so this fires
     ONLY on the investment-income provider's standalone Abgeltungsteuer
     component, NOT on the CGT provider's `:de-§20` component (those are
     gains; the consumer can elect to add a KiSt provision for §20 gains
     too via a separate provision keyed on `:de-§20`, but that is out
     of scope for v1 — note 147 §5 deliberately keeps the income- and
     gains-side standalone components separate).

   - The compute-fn `:de-kist-on-abgeltungsteuer` reads
     `(:church-tax-rate (:tax-unit ctx))` (consumer-supplied 0M / 0.08M
     / 0.09M) and multiplies it by `(:running ctx)`."

  [{:provision/code            "DE-KiStG-on-§20"
    :provision/jurisdiction    :de
    :provision/concept         [:tax-concept/code :surtax]
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
