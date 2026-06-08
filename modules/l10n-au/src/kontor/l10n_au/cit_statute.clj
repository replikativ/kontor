(ns kontor.l10n-au.cit-statute
  "AU corporate income tax — Australian company tax (ITRA 1986 §23 +
   §23AA / ITAA 1997) — encoded as `kontor.tax.statute` data per
   ADR-101. Migrates the record-shape `au-company-tax-provider` (in
   `period_tax_provider.clj`) to statute-as-data — slice
. Mirrors `kontor.l10n-fr.cit-statute` (the
   closest single-component CIT comparator); the BRE rate swap rides
   `:op :schedule-override` (same pattern as FR PME).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the ITRA 1986 §23
     standard 30 % rate and §23AA BRE rate ALREADY LIVE in
     `kontor.l10n-au.investment-income-statute`
     (`AU.InvIncome.corporate-rate.large` /
     `AU.InvIncome.corporate-rate.base-rate-entity`); this file
     references them by code and ADDS the missing 2017-07-01 / 2020-07-01
     backfill rows to meet Q5.4 5y bitemporal depth. NEW parameters:
     two BRE-eligibility documentation rows
     (`AU.CIT.bre-aggregated-turnover-threshold` +
     `AU.CIT.bre-passive-income-fraction`).

   - **Provisions** (per-jurisdiction rules) — five provisions:
       - AU-ITRA-1986-§23AA-bre-schedule — `:schedule-override`
         swapping the flat 30 % default for the 25 % BRE rate when
         `:tax-unit :base-rate-entity?` is true (consumer-adjudicated
         eligibility flag per LCR 2019/5).
       - AU-cgt-cit-base-additions — `:base-add` lane reading the
         `cgt-provider`'s `:cit-base-additions` (post-cascade
         assessable net capital gain; companies do NOT get the Div 115
         discount).
       - AU-investment-cit-base-additions — `:base-add` lane reading
         the `investment-income-provider`'s `:cit-base-additions`
         (franking gross-up + unfranked + foreign + interest).
       - AU-franking-credit-cit-credit — `:non-refundable-credit`
         (excess flows to recipient's franking account, out-of-substrate
8).
       - AU-fito-cit-credit — `:non-refundable-credit` for the foreign
         income tax offset.

   - **Scoping** — all provisions are scoped to the single `:au-cit`
     component via `[:eq :component :au-cit]`, future-proofing for any
     later second component.

   ## Out of scope for v1 ( slice)

   - **Tax Consolidation Regime (ITAA 1997 Pt 3-90 Div 700)** —
     elective single-entity treatment for a head company + 100 %-owned
     subsidiaries. The N-component multi-jurisdiction substrate
     primitive is shipped (ADR-107 CA T1) but the AU-specific ACA /
     push-down / available-fraction modelling is deferred to a future
     `kontor-group-consolidation` companion (
     §8.7).
   - **R&D Tax Incentive (ITAA 1997 §355)** — refundable for SMEs,
     non-refundable for larger. Consumer pre-computes via `:inputs
     :credits`; v2 may lift as a `:provision`.
   - **Loss Carry Back Tax Offset (LCBTO)** — refundable; sunset
     FY 2022-23. Deferred (out-of-substrate inter-period carry).
   - **Carry-forward losses (ITAA 1997 §36-15)** — consumer
     pre-computes the deductible loss and folds via `:inputs
     :book-profit` (inter-period carry).
   - **Major Bank Levy** — applies to four banks only; out of scope.

   ## Audit-doc seam (TODO)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   ~50 LOC kernel sweep tracked as a follow-up, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`;
   the posting wire-up lands in a kernel sweep.

   ## Citations

   `legislation.gov.au` for the consolidated statute text (same
   convention the shipped `cgt-statute` / `investment-income-statute`
   modules use); `ato.gov.au` for the administrative position. Each
   parameter value / provision carries its own citation."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================
;;
;; NOTE: `AU.InvIncome.corporate-rate.large` (30 %) and
;; `AU.InvIncome.corporate-rate.base-rate-entity` (25 %) ALREADY live
;; in `kontor.l10n-au.investment-income-statute`. This file does NOT
;; redefine them — it references them by code at evaluation time. We
;; DO extend the BRE rate history backwards (27.5 % at 2017-07-01;
;; 26 % at 2020-07-01) since `:db.unique/identity` on `:kontor.parameter/code`
;; makes value-row additions safe regardless of which statute file
;; owns them.

(def parameters
  "AU CIT parameter definitions — one row per `:kontor.parameter/code`.
   Only the two BRE-eligibility documentation rows are new; the rate
   parameters ship from `kontor.l10n-au.investment-income-statute` and
   CIT references them by code at evaluation time."
  [{:kontor.parameter/code         "AU.CIT.bre-aggregated-turnover-threshold"
    :kontor.parameter/label        "ITAA 1997 Subdiv 328-C / §995-1 — BRE eligibility — aggregated turnover ceiling"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.CIT.bre-passive-income-fraction"
    :kontor.parameter/label        "ITRA 1986 §23AB — BRE eligibility — BREPI ceiling fraction of assessable income"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A03205"}])

(def parameter-values
  "AU CIT scalar parameter values with their statutory effective
   windows. Two backfill rows on the shared
   `AU.InvIncome.corporate-rate.base-rate-entity` parameter (27.5 % at
   2017-07-01, 26 % at 2020-07-01) extend its history to satisfy
   Q5.4 5y bitemporal depth. Plus the two BRE-eligibility documentation
   values (stable since FY 2017-18)."
  [;; --- BRE rate backfill — Treasury Laws Amendment (Enterprise Tax Plan) Act 2017
   ;;     + Treasury Laws Amendment (Lower Taxes for SMB) Act 2018.
   ;;     27.5 % effective 2017-07-01 through 2020-06-30.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.InvIncome.corporate-rate.base-rate-entity"]
    :kontor.parameter-value/effective-from  #inst "2017-07-01"
    :kontor.parameter-value/effective-until #inst "2020-07-01"
    :kontor.parameter-value/decimal-value   0.275M
    :kontor.parameter-value/citation        "ITRA 1986 §23 + §23AA (Treasury Laws Amendment (Enterprise Tax Plan) Act 2017) — BRE rate 27.5 % from 2017-07-01"}

   ;; 26 % effective 2020-07-01 through 2021-06-30.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.InvIncome.corporate-rate.base-rate-entity"]
    :kontor.parameter-value/effective-from  #inst "2020-07-01"
    :kontor.parameter-value/effective-until #inst "2021-07-01"
    :kontor.parameter-value/decimal-value   0.26M
    :kontor.parameter-value/citation        "ITRA 1986 §23 + §23AA (Treasury Laws Amendment (Lower Taxes for SMB) Act 2018 — second step) — BRE rate 26 % for FY 2020-21"}

   ;; --- BRE eligibility — documentation rows (consumer adjudicates).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.CIT.bre-aggregated-turnover-threshold"]
    :kontor.parameter-value/effective-from #inst "2017-07-01"
    :kontor.parameter-value/decimal-value  50000000M
    :kontor.parameter-value/citation       "ITAA 1997 Subdiv 328-C / §995-1 (Treasury Laws Amendment (Enterprise Tax Plan) Act 2017) — A$50M aggregated turnover ceiling for BRE eligibility, stable since FY 2017-18"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.CIT.bre-passive-income-fraction"]
    :kontor.parameter-value/effective-from #inst "2017-07-01"
    :kontor.parameter-value/decimal-value  0.80M
    :kontor.parameter-value/citation       "ITRA 1986 §23AB (Treasury Laws Amendment (Enterprise Tax Plan Base Rate Entities) Act 2018) — 80 % BREPI ceiling, stable since FY 2017-18"}])

;; ============================================================================
;; Provisions — AU CIT statute as :provision data
;; ============================================================================

(def provisions
  "AU CIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:au-cit` in
   v1) and use vector fact-keys `[:inputs ...]` / `[:tax-unit ...]`
   for consumer-supplied facts / flags — each provision is gated on
   the presence of its driver so an absent fact silently no-ops.

   Consequences are `:schedule-override` (the BRE swap) or
   `:tax-context-fact` reads (the CGT / investment-income provider's
   lanes the consumer harvests) — rates come from `:parameter` data
   via the rate-from / parameter shape, NOT inlined here."
  [;; ----------------------------------------------------------------
   ;; ITRA 1986 §23AA — Base-rate entity (BRE) schedule swap
   ;; ----------------------------------------------------------------
   ;; When `:tax-unit :base-rate-entity?` is true the substrate swaps
   ;; the default 30 % schedule for the 25 % BRE rate. The eligibility
   ;; test (aggregated turnover < $50 M per Subdiv 328-C + ≤ 80 %
   ;; BREPI per §23AB) lives OUTSIDE the substrate1.2
   ;; — the consumer adjudicates per ATO LCR 2019/5 and signals via
   ;; the flag.
   {:kontor.provision/code           "AU-ITRA-1986-§23AA-bre-schedule"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "ITRA 1986 §23AA — Base-rate entity (25 %)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A03205"
    :kontor.provision/effective-from #inst "2017-07-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-cit]
                                              [:eq [:tax-unit :base-rate-entity?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :au-bre-rate
                                              :label    "ITRA 1986 §23AA — Base-rate entity (25 %)"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from            :parameter
                                                         :parameter            "AU.InvIncome.corporate-rate.base-rate-entity"}})}

   ;; ----------------------------------------------------------------
   ;; CGT-cit-base-additions lane (from cgt-provider)
   ;; ----------------------------------------------------------------
   ;; The shipped `cgt-provider` emits the assessable net capital gain
   ;; via `:jurisdiction-specific-codes {:cit-base-additions [net]}`
   ;; for `:company` holders (no Div 115 discount). The consumer
   ;; harvests the lane and passes via `:inputs
   ;; :au-cgt-cit-base-additions`; the substrate folds it.
   {:kontor.provision/code           "AU-cgt-cit-base-additions"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "CGT cit-base-additions lane (from cgt-provider — post-cascade net gain)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "1985-09-20"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-cit]
                                              [:gt [:inputs :au-cgt-cit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :au-cgt-base-fold
                                              :label       "CGT cit-base-additions lane (from cgt-provider)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-cgt-cit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; Investment-income-cit-base-additions lane (from investment-income-provider)
   ;; ----------------------------------------------------------------
   ;; The shipped `investment-income-provider` emits
   ;; `:jurisdiction-specific-codes {:cit-base-additions [gross-up +
   ;; unfranked + foreign + interest]}` for `:company` holders. ITAA
   ;; 1997 §207-20 — the imputation gross-up adds the franking credit
   ;; amount to the assessable income; the companion credit is the
   ;; `:non-refundable-credit` provision below.
   {:kontor.provision/code           "AU-investment-cit-base-additions"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "ITAA 1997 §207-20 — Investment-income cit-base-additions lane (imputation gross-up + unfranked + foreign + interest)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2002-07-01"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-cit]
                                              [:gt [:inputs :au-investment-cit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :au-investment-base-fold
                                              :label       "Investment-income cit-base-additions lane (from investment-income-provider)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-investment-cit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1997 §207-45 — Franking credit (non-refundable for :company)
   ;; ----------------------------------------------------------------
   ;; The credit lane from investment-income-provider. The
   ;; franking-credit fate for `:company` holders is
   ;; `:non-refundable`; excess flows to the recipient's franking
   ;; account (a separate workflow that is out-of-substrate in v1).
   ;; Provision surfaces it for explicit provenance.
   {:kontor.provision/code           "AU-ITAA-1997-§207-45-franking-credit-cit"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "ITAA 1997 §207-45 — Franking credit (non-refundable for :company)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2002-07-01"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-cit]
                                              [:gt [:inputs :au-franking-credit-cit-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-franking-cit-credit
                                              :label       "ITAA 1997 §207-45 — Franking credit (non-refundable)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-franking-credit-cit-credit]})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1997 §770-10 — Foreign income tax offset (non-refundable)
   ;; ----------------------------------------------------------------
   ;; Reads the FITO lane the investment-income-provider emits as a
   ;; per-event credit. Non-refundable per §770-10.
   {:kontor.provision/code           "AU-ITAA-1997-§770-10-fito-cit"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "ITAA 1997 §770-10 — Foreign income tax offset (FITO, non-refundable)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2008-07-01"
    :kontor.provision/priority       500
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-cit]
                                              [:gt [:inputs :au-fito-cit-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-fito-cit-credit
                                              :label       "ITAA 1997 §770-10 — Foreign income tax offset (FITO)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-fito-cit-credit]})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install AU CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs, so re-running
   is a no-op on unchanged rows. The ITRA 1986 §23 / §23AA rate
   parameters (`AU.InvIncome.corporate-rate.large` and
   `AU.InvIncome.corporate-rate.base-rate-entity`) are NOT installed
   here — they ship with `kontor.l10n-au.investment-income-statute`.
   This file extends the BRE rate's value history (2017-07-01 + 2020-
   07-01 backfill rows) since adding values to an already-shipped
   parameter is safe — value rows are identified by `(parameter, from)`
   triples, not by the parameter alone."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
