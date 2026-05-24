(ns kontor.l10n-au.cgt-statute
  "AU capital-gains tax — Subdivision 115 discount, Subdivision 152 small-
   business concessions, Division 118-B main residence — encoded as
   `kontor.statute` data per ADR-101. Research note 129.

   AU CGT folds the net capital gain into the holder's assessable income
   (ITAA 1997 s102-5); the rate is then the holder's PIT / CIT rate. The
   AU CGT PROVIDER computes only the net assessable capital gain — the
   gain after Div 115 discount (where applicable), Subdiv 152 cascade
   (where elected), Div 118-B exemptions, and capital-loss netting. The
   PIT / CIT provider sweeps the resulting `:pit-base-additions` /
   `:cit-base-additions`.

   Five jurisdictional ingredients enter the statute, all date-keyed so a
   2027-style reform is a one-row migration:

   - **Discount rates** (`s115-100`) — 50% individuals / trusts, 1/3
     complying super funds, 0% companies. The 50% rate is repealed for
     individuals / trusts from 1 July 2027 (2026-27 budget); v1 gates by
     the cutover parameter.
   - **Holding-period cutoff** (`s115-25`) — 365 days; greater than 12
     months = discount-eligible.
   - **Retirement-cap lifetime** (`s152-305`) — $500 000 per CGT
     concession stakeholder, frozen since 2007-07-01.
   - **Personal-use / collectables thresholds** (`s118-10`) — $10 000 /
     $500 first-element thresholds below which gain is exempt.
   - **Discount sunset** — 1 July 2027 cutover for individuals / trusts
     (the 2026-27 federal-budget reform; note 129 §1.4).

   The Subdivision 152 cascade itself is PROVIDER-INTERNAL LOGIC, not
   ADR-101 provisions (note 129 §5) — the cascade is a taxpayer-elected
   ordered sequence whose semantics don't fit the `:base-transform` /
   `:surtax` / `:credit` vocabulary. Encoding it as provisions would
   force awkward conditionals into a vocabulary not designed for them.
   The cascade lives in `cgt-provider`; this statute carries the rates
   + thresholds it reads.

   TODO (note 129 §1.4): the 30% minimum-effective-rate floor for
   individuals / trusts on or after 2027-07-01 is NOT yet implemented —
   the discount is simply gated off by `:as-of`. A future ADR-101
   parameter `AU.CGT.post-2027.min-effective-rate` (= 0.30M) + a
   `min-effective-rate` provision will land when Treasury publishes the
   indexation factor source.

   Citations point at legislation.gov.au's ITAA 1997 mirror."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "AU CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- Holding-period cutoff -----------------------------------------------
   {:parameter/code         "AU.CGT.holding-period-cutoff-days"
    :parameter/label        "Discount-method holding cutoff — ITAA 1997 s115-25 (> 12 months)"
    :parameter/jurisdiction :au
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}

   ;; --- Discount rates (s115-100) ------------------------------------------
   {:parameter/code         "AU.CGT.discount-rate.individual"
    :parameter/label        "Discount % — individual / trust (Div 115)"
    :parameter/jurisdiction :au
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}
   {:parameter/code         "AU.CGT.discount-rate.super-fund"
    :parameter/label        "Discount % — complying super fund (Div 115)"
    :parameter/jurisdiction :au
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}
   {:parameter/code         "AU.CGT.discount-rate.company"
    :parameter/label        "Discount % — company (none — frozen at 0)"
    :parameter/jurisdiction :au
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}

   ;; --- 1 July 2027 sunset cutover -----------------------------------------
   {:parameter/code         "AU.CGT.discount-sunset-date"
    :parameter/label        "Discount-method sunset for individuals / trusts (2026-27 budget)"
    :parameter/jurisdiction :au
    :parameter/unit         :date
    :parameter/concept-iri  "https://budget.gov.au/content/04-tax-reform.htm"}

   ;; --- Retirement cap (Subdiv 152-D) --------------------------------------
   {:parameter/code         "AU.CGT.§152-D.retirement-cap-lifetime"
    :parameter/label        "Lifetime retirement-exemption cap per CGT concession stakeholder (s152-305)"
    :parameter/jurisdiction :au
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}

   ;; --- Collectables / personal-use thresholds (s118-10) -------------------
   {:parameter/code         "AU.CGT.§118-10.collectable-threshold"
    :parameter/label        "Collectables 1st-element exemption — s118-10(1)"
    :parameter/jurisdiction :au
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}
   {:parameter/code         "AU.CGT.§118-10.personal-use-threshold"
    :parameter/label        "Personal-use assets 1st-element exemption — s118-10(3)"
    :parameter/jurisdiction :au
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legislation.gov.au/C2004A05138/asmade/text"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "AU CGT parameter values. Sources:
     - s115-25 holding cutoff — stable since the Div 115 enactment (TLAA 1999).
     - s115-100 discount rates — stable since 1999-09-21.
     - s152-305 retirement cap — frozen at $500 000 (note 129 §1.7;
       changed from $200 000 to $500 000 effective 2007-07-01 via
       Tax Laws Amendment (Simplified Super) Act 2007).
     - s118-10 thresholds — stable since CGT introduction (1985-09-20).
     - Discount sunset — 1 July 2027 (2026-27 budget announcement)."
  [;; Holding period — > 12 months → discount-eligible. 365 days exclusive cutoff.
   {:parameter-value/parameter      [:parameter/code "AU.CGT.holding-period-cutoff-days"]
    :parameter-value/effective-from #inst "1999-09-21"
    :parameter-value/decimal-value  365M
    :parameter-value/citation       "ITAA 1997 s115-25 — discount requires > 12 months holding"}

   ;; Discount rates.
   {:parameter-value/parameter      [:parameter/code "AU.CGT.discount-rate.individual"]
    :parameter-value/effective-from #inst "1999-09-21"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "ITAA 1997 s115-100(a) — individual / trust 50 %"}
   {:parameter-value/parameter      [:parameter/code "AU.CGT.discount-rate.super-fund"]
    :parameter-value/effective-from #inst "1999-09-21"
    ;; 1/3 = 0.33333... — provider applies as `gain × (1 − rate)` so we ship
    ;; 1/3 directly. BigDecimal-stored as the closest 6dp; multiplication is
    ;; immediate, so we choose 6dp for explicitness in the audit trail.
    :parameter-value/decimal-value  0.333333M
    :parameter-value/citation       "ITAA 1997 s115-100(b) — complying super fund 1/3"}
   {:parameter-value/parameter      [:parameter/code "AU.CGT.discount-rate.company"]
    :parameter-value/effective-from #inst "1999-09-21"
    :parameter-value/decimal-value  0.00M
    :parameter-value/citation       "ITAA 1997 s115-100 — no discount for companies"}

   ;; 1 July 2027 sunset for individuals + trusts. Stored as milliseconds since
   ;; epoch in a BigDecimal slot (the parameter-value carrier is bigdec). The
   ;; provider coerces back to a Date.
   {:parameter-value/parameter      [:parameter/code "AU.CGT.discount-sunset-date"]
    :parameter-value/effective-from #inst "2026-05-13"
    :parameter-value/decimal-value  (bigdec (.getTime #inst "2027-07-01"))
    :parameter-value/citation       "2026-27 Federal Budget — Tax Reform package, announced 2026-05-13"}

   ;; Retirement cap (frozen since 2007).
   {:parameter-value/parameter      [:parameter/code "AU.CGT.§152-D.retirement-cap-lifetime"]
    :parameter-value/effective-from #inst "2007-07-01"
    :parameter-value/decimal-value  500000M
    :parameter-value/citation       "ITAA 1997 s152-305 — $500 000 lifetime cap (Tax Laws Amendment (Simplified Super) Act 2007)"}

   ;; s118-10 thresholds.
   {:parameter-value/parameter      [:parameter/code "AU.CGT.§118-10.collectable-threshold"]
    :parameter-value/effective-from #inst "1985-09-20"
    :parameter-value/decimal-value  500M
    :parameter-value/citation       "ITAA 1997 s118-10(1) — collectables 1st-element $500"}
   {:parameter-value/parameter      [:parameter/code "AU.CGT.§118-10.personal-use-threshold"]
    :parameter-value/effective-from #inst "1985-09-20"
    :parameter-value/decimal-value  10000M
    :parameter-value/citation       "ITAA 1997 s118-10(3) — personal-use assets 1st-element $10 000"}])

;; ============================================================================
;; Provisions — none (v1)
;; ============================================================================

(def provisions
  "AU CGT provisions — empty for v1.

   The discount, Subdivision 152 cascade, and Div 118-B exemptions are
   all PROVIDER-INTERNAL LOGIC per research note 129 §5 — the cascade is
   a taxpayer-elected ordered sequence whose semantics don't fit the
   ADR-101 closed `:op` vocabulary (`:base-add` / `:base-deduct` /
   `:credit` / `:surtax` / `:schedule-override`). Encoding it as
   provisions would force awkward conditionals into a vocabulary not
   designed for them.

   Future work (note 129 §1.4): the 30 % minimum-effective-rate floor
   for individuals / trusts from 1 July 2027 IS naturally a
   `:minimum-tax` / `:surtax` provision and will land here when Treasury
   publishes its indexation-factor source."
  [])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install AU CGT statute (parameters + values) into `conn`. Idempotent —
   `:parameter/code` is a unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))
