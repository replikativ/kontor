(ns kontor.l10n-at.cit-statute
  "AT corporate income tax — Körperschaftsteuer (KöSt) — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `at-corporate-income-tax-provider` (in `period_tax_provider.clj`)
   to statute-as-data — slice. Mirrors
   `kontor.l10n-fr.cit-statute` (the closest single-component CIT
   comparator); the Mindest-KöSt floor rides
   `kontor.tax.statute/compose-greater-of` (NOT a provision — it is a
   substrate composition between the regular KSt component and a
   minimum-tax arm).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the §22 KStG flat
     rate (25 → 24 → 23 %, ÖkoStRefG 2022) ALREADY LIVES in
     `kontor.l10n-at.cgt-statute` (`AT.KStG.cit-rate`); this file
     adds the §24 KStG Mindest-KöSt amounts (GmbH €1 750 → €500 cliff
     2024-01-01 per BGBl I 2023/179 Startup-Förderungsgesetz; AG
     €3 500 stable).

   - **Provisions** (per-jurisdiction rules) — two required + two
     optional adjustment paths:
       - AT-KStG-§10-cit-base-deductions — reads the lane the
         shipped `investment-income-provider` emits as
         `:cit-base-deductions` (qualifying dividends are tax-free
         under §10 KStG; the consumer harvests + supplies via
         `:inputs :cgt-cit-base-deductions`).
       - AT-KStG-§10-cit-base-additions — reads the lane the same
         provider emits when the consumer elects the §10 KStG
         tax-effective option (`:cit-base-additions`).
       - AT-KStG-§8-Abs-4-verlustvortrag (optional) — surface the
         consumer-pre-computed Verlustvortrag as a provenance-tracked
         deduction (the §2 Abs 2b EStG 75 % cap stays out of substrate
 frontier 2; consumer computes the permitted
         amount).
       - AT-KStG-§12-nicht-abzugsfaehig (optional) — mirror of DE §10
         KStG add-back: surface the consumer-pre-computed non-
         deductible expenses (Aufsichtsratsvergütungen ½, etc.).

   - **Scoping** — all provisions are scoped to the single KöSt
     component via `[:eq :component :koest]`, mirroring FR's
     per-component gating discipline (even single-component providers
     gate to future-proof).

   ## Mindest-KöSt — substrate composition, NOT a provision

   §24 Abs 4 KStG sets a minimum corporate-tax floor: even at a loss
   the entity owes the statutory minimum (GmbH €500/yr post-2024;
   AG €3 500/yr). This is the canonical AMT / minimum-tax pattern
   the substrate handles via
   `kontor.tax.statute/compose-greater-of` (per ADR-101 §3.1 of the
   recipe). The provider builds TWO components (the regular
   KSt and the Mindest-KöSt floor) and the helper picks the prevailing
   arm with `:composed-of` + `:composition` audit trail.

   ## Out of scope for v1 ( slice)

   - **§9 KStG Gruppenbesteuerung** — tax consolidation across a
     parent + subsidiaries (≥50 % shareholding for ≥1 year). The
     N-component multi-jurisdiction substrate primitive is shipped
     (ADR-107 CA T1) but the AT-specific Gruppenträger /
     Gruppenmitglied modelling is deferred to a future
     `kontor-group-consolidation` companion (
     §8.3).
   - **§22 Abs 2 KStG Privatstiftungs-Zwischensteuer** — private
     foundations pay a different rate (23 % currently, 27.5 % from
     2026 per BGBl I 2023/200). Deferred to a foundation-specific v2;
     the consumer routes via a separate provider config until then.
   - **§8 Abs 4 KStG Mantelkaufverbot** — qualitative shell-purchase
     test the substrate cannot adjudicate from the GL; consumer
     pre-computes net loss-carry-forward and folds via
     `:inputs :at-verlustvortrag-applied` (provision #3 below
     surfaces the consumer-supplied figure for audit).
   - **Bank / insurance Mindest-KöSt (€5 452/yr per §24 Abs 4 Z 4)**
     — parameter row deferred until a banking consumer asks.

   ## Audit-doc seam (TODO)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   ~50 LOC kernel sweep tracked separately, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`;
   the posting wire-up lands in a kernel sweep.

   ## Citations

   `jusline.at` for the consolidated statute text (same convention the
   shipped `cgt-statute` / `investment-income-statute` modules use);
   `ris.bka.gv.at` for the official BGBl Fundstelle. Parameter values
   carry their own citations."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================
;;
;; NOTE: `AT.KStG.cit-rate` ALREADY lives in `kontor.l10n-at.cgt-statute`
;; with three :parameter-value rows for the 25 → 24 → 23 % ladder. This
;; file does NOT re-define it; the install order is documented in
;; `kontor.l10n-at.preset/install-all!` (CGT statute runs before CIT in
;; AT — opposite of the recipe's general "CIT first" guidance — because
;; the rate parameter shipped on the CGT side first2).

(def parameters
  "AT CIT parameter definitions — one row per `:kontor.parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`. The
   §22 KStG flat rate (`AT.KStG.cit-rate`) is NOT here — it ships from
   `kontor.l10n-at.cgt-statute` and CIT references it by code at
   evaluation time."
  [{:kontor.parameter/code         "AT.KStG.§24.mindest-koest-gmbh-amount"
    :kontor.parameter/label        "§24 Abs 4 Z 1 KStG — Mindestkörperschaftsteuer for GmbH (annual amount, FlexCo treated identically)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/24"}

   {:kontor.parameter/code         "AT.KStG.§24.mindest-koest-ag-amount"
    :kontor.parameter/label        "§24 Abs 4 Z 1 KStG — Mindestkörperschaftsteuer for AG (annual amount)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/24"}])

(def parameter-values
  "AT CIT scalar parameter values with their statutory effective
   windows. The GmbH Mindest-KöSt was reduced from €1 750/yr to
   €500/yr by the GesRÄG 2023 / Startup-Förderungsgesetz (BGBl I
   2023/179) effective 2024-01-01 — both rows ship so a retrospective
   assessment of pre-2024 fiscal years uses the correct figure."
  [;; GmbH Mindest-KöSt — pre-2024 €1 750/yr (the bug-fix history-row;
   ;; the legacy record-shape provider defaulted to this stale figure).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.KStG.§24.mindest-koest-gmbh-amount"]
    :kontor.parameter-value/effective-from  #inst "1990-01-01"
    :kontor.parameter-value/effective-until #inst "2024-01-01"
    :kontor.parameter-value/decimal-value   1750M
    :kontor.parameter-value/citation        "§24 Abs 4 Z 1 KStG — pre-2024 GmbH Mindest-KöSt €1 750/yr (5 % × ¼ × €35 000 statutory minimum capital)"}

   ;; GmbH Mindest-KöSt — from 2024-01-01 €500/yr (Startup-Förderungsgesetz
   ;; reduced statutory minimum capital from €35 000 to €10 000).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.KStG.§24.mindest-koest-gmbh-amount"]
    :kontor.parameter-value/effective-from #inst "2024-01-01"
    :kontor.parameter-value/decimal-value  500M
    :kontor.parameter-value/citation       "§24 Abs 4 Z 1 KStG (GesRÄG 2023 / Startup-Förderungsgesetz, BGBl I 2023/179) — GmbH Mindest-KöSt €500/yr from 2024-01-01 (5 % × ¼ × €10 000 reduced minimum capital). FlexCo treated identically per BGBl I 2023/179."}

   ;; AG Mindest-KöSt — stable €3 500/yr.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.KStG.§24.mindest-koest-ag-amount"]
    :kontor.parameter-value/effective-from #inst "1990-01-01"
    :kontor.parameter-value/decimal-value  3500M
    :kontor.parameter-value/citation       "§24 Abs 4 Z 1 KStG — AG Mindest-KöSt €3 500/yr (5 % × ¼ × €70 000 statutory minimum capital), stable"}])

;; ============================================================================
;; Provisions — AT KöSt statute as :provision data
;; ============================================================================

(def provisions
  "AT KöSt statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:koest` in v1)
   and use vector fact-keys `[:inputs ...]` for consumer-supplied
   amounts — each provision is gated on the presence of its driver
   so an absent fact silently no-ops.

   Consequences are `:tax-context-fact` reads (the
   investment-income-provider's lanes the consumer harvests) — rates
   and amounts come from `:parameter` data, NOT inlined here."
  [;; ----------------------------------------------------------------
   ;; §10 KStG — Beteiligungsertragsbefreiung (deduction lane)
   ;; ----------------------------------------------------------------
   ;; Qualifying dividends (Schachtelbeteiligung ≥ 10 %, 12-month hold)
   ;; are tax-free per §10 Abs 1-3 KStG. The shipped
   ;; `investment-income-provider` already emits the exempted amount as
   ;; `:cit-base-deductions`; the consumer harvests it and passes via
   ;; `:inputs :cgt-cit-base-deductions`. We fold via the substrate.
   {:kontor.provision/code           "AT-KStG-§10-cit-base-deductions"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§10 KStG — Beteiligungsertragsbefreiung (lane from investment-income provider)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/kstg/paragraf/10"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :koest]
                                              [:gt [:inputs :cgt-cit-base-deductions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :at-§10-exempt-dividends
                                              :label       "§10 KStG — Beteiligungsertragsbefreiung"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-cit-base-deductions]})}

   ;; ----------------------------------------------------------------
   ;; §10 KStG — Tax-effective option (addition lane)
   ;; ----------------------------------------------------------------
   ;; When the consumer elects the §10 Abs 3 KStG tax-effective option
   ;; (Option zur Steuerwirksamkeit) the otherwise-exempt holding
   ;; becomes taxable for the elected window. The
   ;; investment-income-provider emits `:cit-base-additions` when
   ;; `:elective-regime :at-§10-tax-effective-option` is in force; the
   ;; consumer harvests and passes via `:inputs :cgt-cit-base-additions`.
   {:kontor.provision/code           "AT-KStG-§10-cit-base-additions"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "§10 KStG — Option zur Steuerwirksamkeit (cit-base-additions lane)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/kstg/paragraf/10"
    :kontor.provision/effective-from #inst "2011-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :koest]
                                              [:gt [:inputs :cgt-cit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :at-§10-elective-additions
                                              :label       "§10 KStG — Tax-effective option additions"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-cit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; §8 Abs 4 KStG — Verlustvortrag (consumer-pre-computed)
   ;; ----------------------------------------------------------------
   ;; Optional surface for the consumer-pre-computed Verlustvortrag.
   ;; The §2 Abs 2b EStG 75 %-cap-above-€1M calculation lives outside
   ;; the substrate (inter-period carry frontier); the
   ;; consumer supplies the permitted offset amount. Surfacing it via
   ;; a provision (rather than folding silently into book-profit)
   ;; improves the audit trail at zero substrate cost.
   {:kontor.provision/code           "AT-KStG-§8-Abs-4-verlustvortrag"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§8 Abs 4 KStG — Verlustvortrag (pre-computed by consumer per §2 Abs 2b EStG cap)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/kstg/paragraf/8"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :koest]
                                              [:gt [:inputs :at-verlustvortrag-applied] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :at-§8-verlustvortrag
                                              :label       "§8 Abs 4 KStG — Verlustvortrag"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :at-verlustvortrag-applied]})}

   ;; ----------------------------------------------------------------
   ;; §12 KStG — Nicht abzugsfähige Aufwendungen (consumer-pre-computed)
   ;; ----------------------------------------------------------------
   ;; Mirror of DE §10 KStG: the consumer surfaces the pre-computed
   ;; total of non-deductible expenses (Aufsichtsratsvergütungen ½,
   ;; KSt + Soli/Verwaltungskosten, etc. per §12 Abs 1-2 KStG). Same
   ;; consumer-pre-computes-net posture; provision provides the
   ;; provenance.
   {:kontor.provision/code           "AT-KStG-§12-nicht-abzugsfaehig"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "§12 KStG — Nicht abzugsfähige Aufwendungen (consumer pre-computed)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/kstg/paragraf/12"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :koest]
                                              [:gt [:inputs :at-§12-non-deductibles] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :at-§12-non-deductibles
                                              :label       "§12 KStG — nicht abzugsfähige Aufwendungen"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :at-§12-non-deductibles]})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install AT CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs, so re-running is
   a no-op on unchanged rows. The §22 KStG rate parameter
   (`AT.KStG.cit-rate`) is NOT installed here — it ships with
   `kontor.l10n-at.cgt-statute`; this file references it by code at
   evaluation time."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
