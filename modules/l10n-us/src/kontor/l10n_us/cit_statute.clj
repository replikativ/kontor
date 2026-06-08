(ns kontor.l10n-us.cit-statute
  "US federal corporate income tax — Form 1120 / IRC §11 — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `us-corporate-income-tax-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-fr.cit-statute` (the closest single-component flat-rate
   CIT comparator). Federal-only — state CIT is OUT of substrate per
   ADR-005 / ADR-010 /(consumers integrate Avalara /
   Vertex for sub-federal income taxes; the CA federal+provincial
   pattern (ADR-107) is the structural template if/when state CIT is
   re-opened).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the §11 flat 21 %
     post-TCJA rate, plus three optional stubs for §172 NOL 80 %-cap
     and the deferred CAMT placeholder.

   - **Provisions** (per-jurisdiction rules) — one REQUIRED base-side
     fold + three optional v1 stubs:
       - US-IRC-§11-cit-base-additions — reads the lane the shipped
         `cgt-provider` emits as `:jurisdiction-specific-codes
         {:cit-base-additions [<corp-net + §1245/§1250 recapture>]}`
         (`cgt_provider.clj:401`). The consumer harvests + supplies via
         `:inputs :cgt-cit-base-additions`.
       - US-IRC-§172-nol-deduction (optional) — surfaces consumer-pre-
         computed §172 NOL deduction (subject to the §172(a)(2)(B)
         80 %-of-taxable-income cap, computed by the consumer outside
         the substrate). Mirrors DE §10d / AT §8 Abs 4 optional stubs.
       - US-IRC-§163j-interest-cap (optional) — surfaces consumer-pre-
         computed §163(j) disallowed business-interest add-back.
       - US-IRC-§250-fdii-gilti-deduction (optional) — surfaces consumer-
         pre-computed §250 FDII / GILTI deduction.

   - **Scoping** — all provisions are scoped to the single CIT
     component via `[:eq :component :cit]`, matching the FR `:is`-
     component discipline (even single-component providers gate to
     future-proof).

   ## Out of scope for v1 ( slice)

   - **State CIT (the 50-state patchwork).** Q5.3 = federal-only.
     A future ADR-style extension uses the CA federal+provincial
     pattern (ADR-107) — N-component fan-out via `:tax-unit
     :state-allocation`.
   - **CAMT (IRC §55-§59 post-IRA-2022 Corporate AMT).** Applies only
     to corporations with avg AFSI ≥ $1 B over the prior 3 years —
     virtually no kontor-flavor SMB consumer trips the bar. Substrate
     would express via `kontor.tax.statute/compose-greater-of` (same
     shape as AT Mindest-KöSt — research.1.2). v1 ships
     OPTIONAL placeholder parameter rows; no provision; deferred to
     v1.x.
   - **§250 FDII / GILTI deduction, §163(j) business-interest cap,
     §59A BEAT.** Large-multinational machinery the consumer pre-
     computes outside the substrate; OPTIONAL stub provisions surface
     the consumer-pre-computed amount for audit-trace at zero substrate
     cost.

   ## Audit-doc seam (TODO)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   ~50 LOC kernel sweep tracked as a follow-up, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`;
   the posting wire-up lands in a kernel sweep.

   ## Citations

   `law.cornell.edu/uscode/text/26` for the IRC (the stable, public-
   domain Cornell mirror — same convention the shipped
   `cgt-statute` / `investment-income-statute` modules use)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "US CIT parameter definitions — one row per `:kontor.parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`."
  [{:kontor.parameter/code         "US.CIT.§11.rate"
    :kontor.parameter/label        "IRC §11 — flat federal corporate income tax rate (post-TCJA)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/11"}

   ;; OPTIONAL stub — §172(a)(2)(B) 80 %-of-taxable-income NOL cap.
   ;; v1 surfaces the consumer-pre-computed NOL amount via the optional
   ;; provision; the 80 % cap is honoured by the CONSUMER (the
   ;; substrate does not enforce it). Parameter shipped so a future
   ;; v1.x extension can read it.
   {:kontor.parameter/code         "US.CIT.§172.nol-cap-fraction"
    :kontor.parameter/label        "IRC §172(a)(2)(B) — 80 %-of-taxable-income cap on NOL deduction (post-TCJA)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/172"}

   ;; OPTIONAL stub — CAMT 15 % rate (deferred to v1.x).
   {:kontor.parameter/code         "US.CIT.§55.camt-rate"
    :kontor.parameter/label        "IRC §55 — Corporate AMT rate (15 % on AFSI for applicable corporations, IRA 2022; DEFERRED v1.x)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/55"}

   ;; OPTIONAL stub — CAMT $1 B avg-AFSI threshold (deferred to v1.x).
   {:kontor.parameter/code         "US.CIT.§55.camt-afsi-threshold"
    :kontor.parameter/label        "IRC §55(b)(2) — $1 B avg-AFSI threshold for CAMT applicability (DEFERRED v1.x)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/55"}])

(def parameter-values
  "US CIT scalar parameter values with their statutory effective
   windows. The §11 flat 21 % is stable post-TCJA (Pub L 115-97
   §13001, effective 2018-01-01); v1 ships ONE row covering the Q5.4
   5y window (2020-2025 is entirely inside the post-TCJA regime). The
   pre-TCJA 4-bracket schedule (15/25/34/35 %) is deferred to v1.x —
   any consumer reconstructing 2017-or-earlier owns the parameter
   override. The §172 80 %-cap row is parameter-stable since TCJA. The
   CAMT placeholder rows are shipped but NO provision consumes them in
   v1."
  [;; §11 flat 21 % post-TCJA (Pub L 115-97 §13001 effective 2018-01-01).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.CIT.§11.rate"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.21M
    :kontor.parameter-value/citation       "IRC §11(b) (TCJA §13001, Pub L 115-97) — flat 21 % federal corporate income tax from 2018-01-01"}

   ;; §172 80 % cap on NOL deduction (TCJA §13201).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.CIT.§172.nol-cap-fraction"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.80M
    :kontor.parameter-value/citation       "IRC §172(a)(2)(B) (TCJA §13201, Pub L 115-97) — 80 %-of-taxable-income cap from 2018-01-01"}

   ;; CAMT placeholder values — substrate stub for the deferred v1.x
   ;; extension. NO provision consumes these in v1.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.CIT.§55.camt-rate"]
    :kontor.parameter-value/effective-from #inst "2023-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "IRC §55(b)(1) (IRA 2022 §10101, Pub L 117-169) — 15 % CAMT rate; PLACEHOLDER, deferred v1.x"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.CIT.§55.camt-afsi-threshold"]
    :kontor.parameter-value/effective-from #inst "2023-01-01"
    :kontor.parameter-value/decimal-value  1000000000M
    :kontor.parameter-value/citation       "IRC §55(b)(2) (IRA 2022 §10101, Pub L 117-169) — $1 B avg-AFSI applicability threshold; PLACEHOLDER, deferred v1.x"}])

;; ============================================================================
;; Provisions — US CIT statute as :provision data
;; ============================================================================

(def provisions
  "US CIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:cit` in v1)
   and use vector fact-keys `[:inputs ...]` for consumer-supplied
   amounts — each provision is gated on the presence of its driver so
   an absent fact silently no-ops.

   The §11 rate is the schedule, NOT a provision — the provider reads
   `US.CIT.§11.rate` directly via `parameter-value-at`. Provisions
   here are the base-side adjustments that ride on top.

   Consequences are `:tax-context-fact` reads — rates and amounts come
   from `:parameter` data, NOT inlined here."
  [;; ----------------------------------------------------------------
   ;; §11 base addition — CGT corp-net lane (REQUIRED)
   ;; ----------------------------------------------------------------
   ;; The shipped `cgt-provider` emits
   ;; `:jurisdiction-specific-codes {:cit-base-additions [<corp-net + §1245/§1250 recapture>]}`
   ;; for corporate disposals (`cgt_provider.clj:401` + `:349` for the
   ;; corporate recapture variant). Corporations have NO preferential
   ;; capital-gains rate under US law — net capital gain folds into the
   ;; §11 21 % flat flow. Consumer harvests the lane and passes the
   ;; total scalar via `:inputs :cgt-cit-base-additions`.
   {:kontor.provision/code           "US-IRC-§11-cit-base-additions"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "IRC §11 + §1245/§1250 — corp net capital gain + ordinary recapture (cgt-provider lane)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/11"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :cit]
                                              [:gt [:inputs :cgt-cit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :us-§1245-§1250-recapture
                                              :label       "§1245 / §1250 / §1212 corp net capital — consumer pre-netted (cgt-provider lane)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-cit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; §172 NOL deduction (OPTIONAL v1 stub)
   ;; ----------------------------------------------------------------
   ;; Consumer pre-computes the permitted NOL offset (subject to the
   ;; §172(a)(2)(B) 80 %-of-taxable-income cap; computed outside the
   ;; substrate frontier 2 — inter-period carry). Surfacing
   ;; it via a provision (rather than folding silently into book-profit)
   ;; improves the audit trail at zero substrate cost. Mirrors AT §8
   ;; Abs 4 Verlustvortrag and DE §10d optional stubs.
   {:kontor.provision/code           "US-IRC-§172-nol-deduction"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "IRC §172 — NOL deduction (consumer pre-computed per §172(a)(2)(B) 80 % cap)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/172"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :cit]
                                              [:gt [:inputs :nol-applied] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :us-§172-nol
                                              :label       "§172 NOL deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :nol-applied]})}

   ;; ----------------------------------------------------------------
   ;; §163(j) business-interest disallowance (OPTIONAL v1 stub)
   ;; ----------------------------------------------------------------
   ;; Large-multinational machinery — consumer pre-computes the
   ;; disallowed amount (30 % of ATI cap, with carryforward) outside
   ;; the substrate and folds the add-back here for audit-trace.
   {:kontor.provision/code           "US-IRC-§163j-interest-cap"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "IRC §163(j) — disallowed business-interest add-back (consumer pre-computed)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/163#j"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :cit]
                                              [:gt [:inputs :§163j-disallowed-interest] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :us-§163j-disallowed-interest
                                              :label       "§163(j) disallowed business interest"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :§163j-disallowed-interest]})}

   ;; ----------------------------------------------------------------
   ;; §250 FDII / GILTI deduction (OPTIONAL v1 stub)
   ;; ----------------------------------------------------------------
   ;; Large-multinational deduction — consumer pre-computes per the
   ;; statutory percentages (37.5 % for FDII, 50 % for GILTI; both fall
   ;; in 2026 absent extension); the provision surfaces the result.
   {:kontor.provision/code           "US-IRC-§250-fdii-gilti-deduction"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "IRC §250 — FDII / GILTI deduction (consumer pre-computed)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/250"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :cit]
                                              [:gt [:inputs :§250-deduction] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :us-§250-fdii-gilti
                                              :label       "§250 FDII / GILTI deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :§250-deduction]})}])

;; ============================================================================
;; Install! — transact parameters + values + provisions into a connection
;; ============================================================================

(defn install!
  "Install US CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs, so re-running is
   a no-op on unchanged rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))
