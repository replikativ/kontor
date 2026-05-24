(ns kontor.l10n-fr.cit-statute
  "FR corporate income tax — Impôt sur les sociétés (IS) — encoded as
   `kontor.statute` data per ADR-101. Mirrors the DE CIT statute
   (`kontor.l10n-de.cit-statute`) — the reference template for
   per-jurisdiction CIT authoring (ADR-104). Research note 109 is the
   statute-fit assessment that motivated this encoding.

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the statutory rates
     and thresholds: IS standard rate 25 %, PME bracket scale
     (15 %/25 % @ €42 500), Contribution sociale rate 3.3 % +
     €763 000 abattement, mère-fille 5 % quote-part, CIR 30 %/5 %
     thresholds.

   - **Provisions** (per-jurisdiction rules) — five adjustment paths:
       - FR-CGI-219-I-b-PME — `:schedule-override` swapping the flat
         25 % rate for the PME progressive 15 %/25 % bracket when the
         consumer signals PME eligibility via `:tax-unit :pme?`
       - FR-CGI-145-216-mere-fille — base add-back of the 5 % quote-part
         de frais et charges on qualifying dividends (mirrors DE §8b
         Abs. 5 — assumes book-profit input excludes the dividends per
         French réintégration practice; we add back the 5 % non-
         deductible portion)
       - FR-CGI-235-ter-ZC-CGE — Contribution sociale 3.3 % surtax on
         IS amount exceeding €763 000 (the abattement is built into the
         compute-fn; PME-exempt companies set `:tax-unit :cge-exempt?
         true`)
       - FR-CGI-244-quater-B-CIR — Crédit d'Impôt Recherche, 30 % on
         the first €100 M of qualifying R&D expenses + 5 % above
         (refundable for PMEs via `:tax-unit :cir-refundable?`)

   - **Scoping** — all provisions are scoped to the single IS
     component via `[:eq :component :is]`, mirroring DE's per-component
     gating (KSt / GewSt). FR has only one component in v1; the
     scoping discipline costs nothing and matches the template.

   ## Out of scope for v1

   - Contribution exceptionnelle CEBGE (LF 2025 / LF 2026; 20.6 % /
     41.2 % for turnover ≥ €1 B) — temporary statute, two-year
     averaging not yet on the substrate; defer per note 109 §3.3.
   - Déficit reportable (CGI Art. 209 I al. 3; €1 M + 50 % cap) — this
     is note 105 frontier 2 (inter-period carry), explicitly deferred;
     FR IS is the demand-trigger, but the substrate work is out of
     scope here per the assignment.
   - CVAE — separate provider on a separate base (note 109 §1).

   ## Citations

   Citations point at legifrance.gouv.fr for the statute text and
   impots.gouv.fr / bofip.impots.gouv.fr for the administrative
   interpretations. Parameter values carry their own citations
   (Légifrance article references; BOFiP IDs)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "FR CIT parameter definitions — one row per `:parameter/code`.
   Values live in `parameter-values` / `parameter-brackets` keyed by
   `:effective-from`."
  [{:parameter/code         "FR.IS.standard-rate"
    :parameter/label        "Impôt sur les sociétés (IS) — taux normal (flat rate when not PME)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   {:parameter/code         "FR.IS.pme-brackets"
    :parameter/label        "IS PME progressive bracket scale (CGI Art. 219 I-b) — 15 % then 25 %"
    :parameter/jurisdiction :fr
    :parameter/unit         :bracket-scale
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   {:parameter/code         "FR.IS.pme-bracket-upper"
    :parameter/label        "IS PME reduced-rate bracket upper (CGI Art. 219 I-b) — €42 500"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"}

   {:parameter/code         "FR.CGE.rate"
    :parameter/label        "Contribution sociale sur l'IS (CGE) — 3.3 % surtax (CGI Art. 235 ter ZC)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000031011715"}

   {:parameter/code         "FR.CGE.abattement"
    :parameter/label        "CGE abattement — IS amount below which CGE is 0 (€763 000)"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000031011715"}

   {:parameter/code         "FR.MereFille.quote-part"
    :parameter/label        "Régime mère-fille — quote-part de frais et charges (5 % réintégration sur dividendes exonérés)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048831340"}

   {:parameter/code         "FR.CIR.rate-base"
    :parameter/label        "Crédit d'Impôt Recherche (CIR) — base rate on first €100 M (30 %)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051215816"}

   {:parameter/code         "FR.CIR.rate-above"
    :parameter/label        "Crédit d'Impôt Recherche (CIR) — reduced rate above €100 M (5 %)"
    :parameter/jurisdiction :fr
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051215816"}

   {:parameter/code         "FR.CIR.threshold"
    :parameter/label        "Crédit d'Impôt Recherche (CIR) — €100 M kink between base + above rates"
    :parameter/jurisdiction :fr
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051215816"}])

(def parameter-values
  "FR CIT scalar parameter values with their statutory effective
   windows. The standard 25 % rate is the post-Macron-trajectory rate
   stable since 2022-01-01 (loi de finances 2018 staged reduction
   landed at 25 % on 2022-01-01). Mère-fille 5 % stable since 2000;
   CIR rates stable since 2008 (the 30 % / 5 % structure)."
  [{:parameter-value/parameter      [:parameter/code "FR.IS.standard-rate"]
    :parameter-value/effective-from #inst "2022-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "CGI Art. 219 I — taux normal 25 % (loi de finances 2018 staged reduction landed 2022-01-01)"}

   {:parameter-value/parameter      [:parameter/code "FR.IS.pme-bracket-upper"]
    :parameter-value/effective-from #inst "2023-01-01"
    :parameter-value/decimal-value  42500M
    :parameter-value/citation       "CGI Art. 219 I-b — PME upper raised from €38 120 to €42 500 by LF 2023 Art. 37"}

   {:parameter-value/parameter      [:parameter/code "FR.CGE.rate"]
    :parameter-value/effective-from #inst "2000-01-01"
    :parameter-value/decimal-value  0.033M
    :parameter-value/citation       "CGI Art. 235 ter ZC — Contribution sociale 3.3 % stable since 2000"}

   {:parameter-value/parameter      [:parameter/code "FR.CGE.abattement"]
    :parameter-value/effective-from #inst "2000-01-01"
    :parameter-value/decimal-value  763000M
    :parameter-value/citation       "CGI Art. 235 ter ZC — abattement €763 000 stable since enactment"}

   {:parameter-value/parameter      [:parameter/code "FR.MereFille.quote-part"]
    :parameter-value/effective-from #inst "2000-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "CGI Art. 216 I al. 2 — quote-part de frais et charges 5 % (stable since enactment)"}

   {:parameter-value/parameter      [:parameter/code "FR.CIR.rate-base"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "CGI Art. 244 quater B I — 30 % sur dépenses ≤ €100 M (stable since LF 2008)"}

   {:parameter-value/parameter      [:parameter/code "FR.CIR.rate-above"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "CGI Art. 244 quater B I — 5 % sur dépenses > €100 M (stable since LF 2008)"}

   {:parameter-value/parameter      [:parameter/code "FR.CIR.threshold"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  100000000M
    :parameter-value/citation       "CGI Art. 244 quater B I — €100 M kink between 30 % and 5 % rates"}])

(def parameter-brackets
  "FR PME bracket scale — the two-bracket ladder swapped in by the
   `FR-CGI-219-I-b-PME` `:schedule-override` provision when the
   consumer signals PME eligibility via `:tax-unit :pme?`. The upper
   value €42 500 mirrors the `FR.IS.pme-bracket-upper` scalar parameter
   (kept as a separate scalar for citation / display) — both move
   together if LF amends the cap."
  [{:parameter-bracket/parameter      [:parameter/code "FR.IS.pme-brackets"]
    :parameter-bracket/index          0
    :parameter-bracket/rate           0.15M
    :parameter-bracket/upper          42500M
    :parameter-bracket/effective-from #inst "2023-01-01"}

   {:parameter-bracket/parameter      [:parameter/code "FR.IS.pme-brackets"]
    :parameter-bracket/index          1
    :parameter-bracket/rate           0.25M
    :parameter-bracket/effective-from #inst "2023-01-01"}])

;; ============================================================================
;; Provisions — FR IS statute as :provision data
;; ============================================================================

(def provisions
  "FR IS statutory provisions encoded for the `kontor.statute`
   evaluator. Conditions reference `:component` (set by the provider on
   the per-component pass — always `:is` in v1) and use vector fact-keys
   `[:inputs ...]` / `[:tax-unit ...]` for consumer-supplied facts /
   flags — each provision is gated on the presence of its driver so an
   absent fact silently no-ops.

   Consequences are compute-fns or parameter-driven `:schedule-override`
   shapes — FR rates and thresholds live in `:parameter` data, NOT
   inlined."

  [;; ----------------------------------------------------------------
   ;; PME schedule swap — CGI Art. 219 I-b
   ;; ----------------------------------------------------------------
   ;; When `:tax-unit :pme?` is true, swap the flat 25 % schedule for
   ;; the PME progressive 15 %/25 % ladder. The eligibility test (CA HT
   ;; ≤ €10 M + capital libéré + ≥75 % individual ownership per CGI
   ;; Art. 219 I-b) lives OUTSIDE the substrate per note 109 §3.1 — the
   ;; consumer adjudicates and signals via `:tax-unit :pme?`.
   {:provision/code            "FR-CGI-219-I-b-PME"
    :provision/jurisdiction    :fr
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "CGI Art. 219 I-b — Taux réduit PME (15 % puis 25 %)"
    :provision/citation        "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562"
    :provision/effective-from  #inst "2023-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :is]
                                        [:eq [:tax-unit :pme?] true]])
    :provision/consequence     (pr-str {:op       :schedule-override
                                        :code     :fr-is-pme
                                        :label    "FR IS PME — 15 % puis 25 %"
                                        :schedule {:schedule/type :progressive-bracket
                                                   :brackets-from :parameter
                                                   :parameter     "FR.IS.pme-brackets"}})}

   ;; ----------------------------------------------------------------
   ;; Régime mère-fille — CGI Art. 145 + 216 I
   ;; ----------------------------------------------------------------
   ;; Dividends from a qualifying subsidiary (≥5 % holding, ≥2 year
   ;; hold) are 95 % exempt — i.e. a 5 % quote-part de frais et charges
   ;; reintegrates into the taxable base. Mirrors DE §8b Abs. 5: the
   ;; convention is that `:inputs :book-profit` ALREADY excludes the
   ;; full dividend per French réintégration practice (the dividends
   ;; were posted to a non-taxable account), and we add back the 5 %
   ;; quote-part the statute keeps taxable.
   ;;
   ;; Consumer supplies `:inputs :participation-dividends` as the GROSS
   ;; dividend amount received from qualifying subsidiaries.
   {:provision/code            "FR-CGI-145-216-mere-fille"
    :provision/jurisdiction    :fr
    :provision/concept         [:tax-concept/code :base-transform-add]
    :provision/title           "CGI Art. 145 + 216 I — Régime mère-fille (5 % quote-part de frais et charges)"
    :provision/citation        "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048831340"
    :provision/effective-from  #inst "2000-01-01"
    :provision/priority        200
    :provision/condition       (pr-str [:and
                                        [:eq :component :is]
                                        [:gt [:inputs :participation-dividends] 0M]])
    :provision/consequence     (pr-str {:op          :base-add
                                        :code        :fr-mere-fille-quote-part
                                        :label       "Quote-part 5 % (mère-fille)"
                                        :amount-from :compute-fn
                                        :fn          :fr-mere-fille-addback})}

   ;; ----------------------------------------------------------------
   ;; Contribution sociale 3.3 % — CGI Art. 235 ter ZC
   ;; ----------------------------------------------------------------
   ;; 3.3 % × max(0, IS − €763 000). PME exemption (CA HT < €7.63 M +
   ;; capital tests) suppresses the surtax entirely — consumer signals
   ;; via `:tax-unit :cge-exempt?`. The €763 000 abattement is built
   ;; into the compute-fn (reads `:running` IS).
   {:provision/code            "FR-CGI-235-ter-ZC-CGE"
    :provision/jurisdiction    :fr
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "CGI Art. 235 ter ZC — Contribution sociale 3.3 % sur IS (au-delà de €763 000)"
    :provision/citation        "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000031011715"
    :provision/effective-from  #inst "2000-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :is]
                                        [:not [:eq [:tax-unit :cge-exempt?] true]]])
    :provision/consequence     (pr-str {:op          :surtax
                                        :code        :fr-cge
                                        :label       "Contribution sociale 3.3 % (CGE)"
                                        :amount-from :compute-fn
                                        :fn          :fr-cge-on-is})}

   ;; ----------------------------------------------------------------
   ;; Crédit d'Impôt Recherche — CGI Art. 244 quater B
   ;; ----------------------------------------------------------------
   ;; 30 % of qualifying R&D expenses up to €100 M + 5 % above.
   ;; Refundable for PMEs (in the EU sense) — consumer signals via
   ;; `:tax-unit :cir-refundable?` (true ⇒ liability can go negative,
   ;; a transfer to the taxpayer; false ⇒ credit floors liability at
   ;; zero and carries forward 3 years, out-of-substrate inter-period
   ;; carry).
   {:provision/code            "FR-CGI-244-quater-B-CIR"
    :provision/jurisdiction    :fr
    :provision/concept         [:tax-concept/code :refundable-credit]
    :provision/title           "CGI Art. 244 quater B — Crédit d'Impôt Recherche (30 % / 5 %)"
    :provision/citation        "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051215816"
    :provision/effective-from  #inst "2008-01-01"
    :provision/priority        200
    :provision/condition       (pr-str [:and
                                        [:eq :component :is]
                                        [:gt [:inputs :cir-qualifying-expenses] 0M]])
    :provision/consequence     (pr-str {:op          :credit
                                        :code        :fr-cir
                                        :label       "Crédit d'Impôt Recherche (CIR)"
                                        :refundable? true
                                        :amount-from :compute-fn
                                        :fn          :fr-cir})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn- bracket-row-already-present?
  "True iff a `:parameter-bracket` row with the same `(parameter-code,
   index, effective-from)` triple is already in `db`. Used to make the
   bracket install idempotent — `:parameter-bracket` carries no
   `:db/unique :db.unique/identity` attr in the kernel schema (the
   parent `:parameter/code` is the natural-key seam; the bracket's
   identity is the `(parent, index, effective-from)` triple), so the
   provider must do the dedup itself."
  [db {:parameter-bracket/keys [parameter index effective-from]}]
  (boolean
   (seq
    (d/q '[:find ?b
           :in $ ?code ?idx ?from
           :where
           [?p :parameter/code ?code]
           [?b :parameter-bracket/parameter ?p]
           [?b :parameter-bracket/index ?idx]
           [?b :parameter-bracket/effective-from ?from]]
         db (second parameter) index effective-from))))

(defn install!
  "Install FR CIT statute (parameters + parameter-values +
   parameter-brackets + provisions) into `conn`. Idempotent —
   `:parameter/code` and `:provision/code` are unique identity attrs
   (upsert on re-install); parameter-brackets get explicit dedup via
   `bracket-row-already-present?` since the kernel schema does not
   carry a `:db/unique` on them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (let [db (d/db conn)
        new-brackets (remove #(bracket-row-already-present? db %) parameter-brackets)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))
