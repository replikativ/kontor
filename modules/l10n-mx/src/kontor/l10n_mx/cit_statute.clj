(ns kontor.l10n-mx.cit-statute
  "MX corporate income tax — ISR personas morales — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `mx-isr-corporate-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-fr.cit-statute` (the closest single-component CIT
   comparator); MX has NO minimum-tax (IETU abolished 2014), NO
   surtax, NO schedule-override — the cleanest single-component-flat
   shape in.

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the LISR art. 9 flat
     30 % rate ALREADY LIVES in `kontor.l10n-mx.cgt-statute`
     (`MX.CGT.art-9.pm-rate`, sourced by the corporate CGT fold); this
     file does NOT re-define it. Three optional v1 parameters are
     shipped as stubs (PTU multiplier, art. 32 vehicle deduction cap,
     art. 57 loss-carry years) so future provisions can reference
     without a schema migration.

   - **Provisions** (per-jurisdiction rules) — two required + one
     optional adjustment path:
       - MX-LISR-art-9-fr-I-PTU — reads `:inputs :ptu-deductible`
         (the consumer-pre-computed PTU magnitude from prior-year
         utilidad fiscal); folds as `:base-deduct`.
       - MX-LISR-art-22-cgt-cit-base-additions — reads the lane the
         shipped `cgt-provider/corp-net-component` emits as
         `:cit-base-additions [net-capital]` (cgt_provider.clj
         line 524); folds as `:base-add`.
       - MX-LISR-arts-28-32-non-deductible (optional) — surfaces the
         consumer-pre-computed §28 / §32 non-deductible expenses for
         audit-trail provenance (same posture as DE §10 KStG add-back
         in.1).

   - **Scoping** — all provisions are scoped to the single
     `:isr-pm` component via `[:eq :component :isr-pm]`, mirroring
     FR's per-component gating discipline (future-proofs).

   ## Out of scope for v1 ( slice)

   - **LISR arts. 59-71 — Régimen Opcional para Grupos de Sociedades
     (RIGS)**. Group-tax consolidation across a holding + ≥80 %
     subsidiaries; substrate primitive (N-component) exists but
     MX-specific 30 %-deferral mechanic deferred to a future
     `kontor-group-consolidation` companion ( per note
     187 §8.4).
   - **LISR art. 142 — pre-2014 Consolidación fiscal**. Abolished;
     pre-2014 reconstruction is consumer responsibility.
   - **REPSE (2021)** — qualitative outsourcing test the substrate
     cannot adjudicate from the GL; consumer pre-computes the
     non-deductible portion via the optional provision below or
     supplies a net `:inputs :book-profit`.
   - **§8 Abs 4 KStG-equivalent Mantelkauf** — n/a (MX has no
     direct counterpart; loss carry-forward is art. 57-60).
   - **IETU** — abolished 2014; no `compose-greater-of` needed.

   ## Audit-doc seam (TODO —)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   small kernel sweep tracked separately, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`;
   the posting wire-up lands in a kernel sweep.

   ## Citations

   `mexico.justia.com` for the consolidated statute text (same
   convention the shipped `cgt-statute` / `investment-income-statute`
   modules use). Parameter values carry their own citations."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================
;;
;; NOTE: `MX.CGT.art-9.pm-rate` ALREADY lives in
;; `kontor.l10n-mx.cgt-statute` with one :parameter-value row (30 %
;; from 2014-01-01). This file does NOT re-define it; the install
;; order is documented in `kontor.l10n-mx.preset/install-all!` (CGT
;; statute runs before CIT in MX — opposite of the recipe's general
;; "CIT first" guidance — because the rate parameter shipped on the
;; CGT side first1.1).

(def parameters
  "MX CIT parameter definitions — one row per `:kontor.parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`. The
   30 % rate (`MX.CGT.art-9.pm-rate`) is NOT here — it ships from
   `kontor.l10n-mx.cgt-statute` and CIT references it by code at
   evaluation time.

   v1 ships three OPTIONAL parameter stubs so future provisions can
   reference them without a schema migration."
  [{:kontor.parameter/code         "MX.CIT.art-9-fr-I.ptu-multiplier"
    :kontor.parameter/label        "LISR art. 9 fr. I — PTU prior-year multiplier (10 %)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/"}

   {:kontor.parameter/code         "MX.CIT.art-32.deduction-cap-vehicle"
    :kontor.parameter/label        "LISR art. 32 — non-deductibility cap on corporate vehicles (MX$ 175 000)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-ii/"}

   {:kontor.parameter/code         "MX.CIT.arts-57-60.loss-carry-years"
    :kontor.parameter/label        "LISR arts. 57-60 — pérdida fiscal carry-forward window (10 years post-2014)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :years
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-v/"}])

(def parameter-values
  "MX CIT scalar parameter values. All three v1 stub parameters are
   stable post-2014 LISR reform — one row each documenting the
   current value for reference. None are read by v1 provisions
   (consumer pre-computes the magnitudes outside the substrate); the
   rows exist so future provisions can reference them without a
   schema migration."
  [{:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.CIT.art-9-fr-I.ptu-multiplier"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "LISR art. 9 fr. I (2014 LISR reform, DOF 11-Dic-2013) — 10 % of prior-year utilidad fiscal deductible as PTU pagada"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.CIT.art-32.deduction-cap-vehicle"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  175000M
    :kontor.parameter-value/citation       "LISR art. 32 — non-deductibility cap on corporate vehicles (MX$ 175 000 of acquisition cost)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.CIT.arts-57-60.loss-carry-years"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  10M
    :kontor.parameter-value/citation       "LISR art. 57 (2014 LISR reform) — pérdida fiscal carry-forward window extended from 5 to 10 years"}])

;; ============================================================================
;; Provisions — MX ISR personas morales statute as :provision data
;; ============================================================================

(def provisions
  "MX ISR personas morales statutory provisions encoded for the
   `kontor.tax.statute` evaluator. Conditions reference `:component`
   (always `:isr-pm` in v1) and gate on the presence of driver facts
   so an absent fact silently no-ops. Consequences are
   `:tax-context-fact` reads (consumer-supplied amounts) — rates and
   amounts come from `:parameter` data, NOT inlined here.

   v1 ships 2 required + 1 optional provisions. No surtax (MX has
   none on CIT), no schedule-override (flat 30 % is the only
   schedule), no `compose-greater-of` (IETU abolished 2014)."
  [;; ----------------------------------------------------------------
   ;; LISR art. 9 fr. I — PTU pagada (deducible)
   ;; ----------------------------------------------------------------
   ;; The 10 % mandatory employee profit-sharing paid in year N is
   ;; deductible from year N's utilidad fiscal (a 2014-reform
   ;; restructuring of the prior PTU non-deductibility regime). The
   ;; consumer enforces the link to prior-year utilidad fiscal
   ;; outside the substrate and supplies the magnitude via
   ;; `:inputs :ptu-deductible`.
   {:kontor.provision/code           "MX-LISR-art-9-fr-I-PTU"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "LISR art. 9 fr. I — PTU pagada (deducible en utilidad fiscal)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pm]
                                              [:gt [:inputs :ptu-deductible] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :mx-ptu-deduction
                                              :label       "LISR art. 9 fr. I — PTU pagada (deducible en utilidad fiscal)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :ptu-deductible]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 22 / cgt-provider corporate fold — net capital gains
   ;; ----------------------------------------------------------------
   ;; Reads the lane `kontor.l10n-mx.cgt-provider/corp-net-component`
   ;; emits as `:cit-base-additions [net-capital]` (cgt_provider.clj
   ;; line 524). Consumer harvests the value and passes via
   ;; `:inputs :cgt-cit-base-additions`.
   {:kontor.provision/code           "MX-LISR-art-22-cgt-cit-base-additions"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "LISR art. 22 / cgt-provider corporate fold — net capital gains"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pm]
                                              [:gt [:inputs :cgt-cit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :mx-cgt-cit-base-additions
                                              :label       "LISR art. 22 / cgt-provider corporate fold — net capital gains"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-cit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; LISR arts. 28 / 32 — gastos no deducibles (consumer-pre-computed)
   ;; ----------------------------------------------------------------
   ;; Optional surface for the consumer-pre-computed non-deductible
   ;; add-back (REPSE outsourcing, vehicle cap excess,
   ;; representation-expense excess, etc.). Same posture as DE §10
   ;; KStG add-back and AT §12 KStG.
   ;; Surfacing it as a provision (rather than silently folding into
   ;; book-profit) improves the audit trail at zero substrate cost.
   {:kontor.provision/code           "MX-LISR-arts-28-32-non-deductible"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "LISR arts. 28 / 32 — gastos no deducibles (consumer pre-computed)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-ii/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pm]
                                              [:gt [:inputs :mx-non-deductible-add] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :mx-arts-28-32-non-deductible
                                              :label       "LISR arts. 28 / 32 — gastos no deducibles"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :mx-non-deductible-add]})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install MX CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs, so re-running
   is a no-op on unchanged rows. The 30 % rate parameter
   (`MX.CGT.art-9.pm-rate`) is NOT installed here — it ships with
   `kontor.l10n-mx.cgt-statute`; this file references it by code at
   evaluation time."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
