(ns kontor.l10n-in.cit-statute
  "IN corporate income tax — Regular CIT + MAT (§115JB) encoded as
   `kontor.statute` data per ADR-101 / ADR-101 Addendum 1; research
   notes 122 + 163.

   First end-to-end consumer of `kontor.statute/compose-greater-of`
   on a real statute — the addendum names IN MAT as the canonical
   reference case for the helper.

   The encoding splits cleanly along the substrate seams:

   - **Parameters** (date-keyed value history) — every statutory rate
     plus the standard-regime and foreign-co surcharge bracket scales:
       IN.CIT.standard.small-turnover-rate (25 %),
       IN.CIT.standard.large-turnover-rate (30 %),
       IN.CIT.115BAA.rate                  (22 % flat),
       IN.CIT.115BAB.rate                  (15 % flat),
       IN.CIT.standard.surcharge-brackets  (0/7/12 ₹1cr/₹10cr),
       IN.CIT.concessional.surcharge-rate  (10 % flat),
       IN.CIT.foreign.surcharge-brackets   (0/2/5 ₹1cr/₹10cr),
       IN.cess.rate                        (4 % HEC — SHARED with IN PIT),
       IN.CIT.MAT.rate                     (15 % through FY 2025-26, 14 % thereafter),
       IN.CIT.foreign.rate                 (40 % pre-FA-2024, 35 % from FA 2024).

   - **Provisions** (per-jurisdiction rules) — three `:schedule-override`
     paths (standard small/large, §115BAA, §115BAB, foreign-co), three
     surcharge provisions (banded-standard, flat-concessional, banded-
     foreign), one shared 4 % cess (fires for `:regular` AND `:mat`),
     two MAT provisions (§115JB rate-override + MAT surcharge using the
     standard bracket scale via the same compute-fn).

   - **Scoping** — provisions are scoped to one component (`:regular` or
     `:mat`) via `[:eq :component <kw>]`. §115JB(5A) non-applicability
     for §115BAA / §115BAB / foreign-co is expressed by **condition
     gating** on the MAT provision (it requires
     `[:eq [:tax-unit :regime] :in-cit-standard]` AND
     `[:not [:eq [:tax-unit :foreign-co?] true]]`), not by branching
     in the provider. When the consumer picks a concessional regime the
     MAT provision's condition does not match — no MAT component is
     built — and `compose-greater-of` is a no-op.

   - **DDT abolition (informational).** §115-O (Dividend Distribution
     Tax, ~15 % + surcharge + cess on the distributing company) was
     ABOLISHED with effect from 2020-04-01 by Finance Act 2020 §40.
     India returned to the classical system: dividends are taxed in
     the recipient's hands under §194 (resident) / §195 (non-resident)
     TDS. v1 does NOT encode DDT; this docstring exists for future
     code archaeologists.

   Citations point at `indiacode.nic.in` (GoI National Portal) and
   `incometaxindia.gov.in` (CBDT) — both public-domain. Commercial
   commentary (PwC India Tax Summaries, ClearTax, Tax2win, Motilal
   Oswal) was used only as cross-check for the worked-example
   numbers; no text lifted. Research note 163."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "IN CIT parameter definitions — one row per `:kontor.parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`;
   bracket scales in `parameter-brackets`."
  [;; ----- Standard regime base rates -----
   {:kontor.parameter/code         "IN.CIT.standard.small-turnover-rate"
    :kontor.parameter/label        "Standard regime — base rate, PY 2023-24 turnover ≤ ₹400 cr"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15259"}

   {:kontor.parameter/code         "IN.CIT.standard.large-turnover-rate"
    :kontor.parameter/label        "Standard regime — base rate, PY 2023-24 turnover > ₹400 cr"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15259"}

   ;; ----- Concessional regime rates -----
   {:kontor.parameter/code         "IN.CIT.115BAA.rate"
    :kontor.parameter/label        "§115BAA flat concessional rate — irrevocable election (Form 10-IC)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm"}

   {:kontor.parameter/code         "IN.CIT.115BAB.rate"
    :kontor.parameter/label        "§115BAB flat concessional rate — new manufacturing co. (commence ≤ 2024-03-31)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088567.htm"}

   ;; ----- Surcharge bracket scales -----
   {:kontor.parameter/code         "IN.CIT.standard.surcharge-brackets"
    :kontor.parameter/label        "Standard-regime surcharge — banded 0/7/12 % at ₹1cr/₹10cr (with marginal relief)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Documents/Left%20Menu/TAX%20RATES-domestic.htm"}

   {:kontor.parameter/code         "IN.CIT.concessional.surcharge-rate"
    :kontor.parameter/label        "§115BAA / §115BAB flat 10 % surcharge"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm"}

   {:kontor.parameter/code         "IN.CIT.foreign.surcharge-brackets"
    :kontor.parameter/label        "Foreign-co surcharge — banded 0/2/5 % at ₹1cr/₹10cr (no marginal relief)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15247"}

   ;; ----- Cess (shared between IN PIT and IN CIT — note 163 §1.3 / §2.1) -----
   {:kontor.parameter/code         "IN.cess.rate"
    :kontor.parameter/label        "Health & Education Cess — 4 % on (tax + surcharge), all income-tax heads"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm"}

   ;; ----- MAT (§115JB) -----
   {:kontor.parameter/code         "IN.CIT.MAT.rate"
    :kontor.parameter/label        "§115JB Minimum Alternate Tax rate (15 % FY 2019-20 .. FY 2025-26; 14 % from FY 2026-27)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm"}

   ;; ----- Foreign-co rate -----
   {:kontor.parameter/code         "IN.CIT.foreign.rate"
    :kontor.parameter/label        "Foreign-co CIT rate — 40 % pre-FA-2024, 35 % from FA-2024 (per ITA §90 / §115A)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15247"}])

(def parameter-values
  "IN CIT parameter values with their statutory effective windows.
   Every row carries a `:citation` back to the Finance Act section that
   set or amended the value — bitemporal swaps (40 % → 35 % foreign-co,
   15 % → 14 % MAT) live here as date-keyed pairs, not in compute-fns."
  [;; Standard 25 % — Finance (No. 2) Act 2019 raised the turnover
   ;; threshold from ₹250 cr to ₹400 cr.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.standard.small-turnover-rate"]
    :kontor.parameter-value/effective-from #inst "2019-04-01"
    :kontor.parameter-value/decimal-value  0.25M
    :kontor.parameter-value/citation       "Finance (No. 2) Act 2019 §2 + First Schedule Part III A"}

   ;; Standard 30 % — the long-standing default rate for "any other
   ;; domestic company" (turnover > ₹400 cr).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.standard.large-turnover-rate"]
    :kontor.parameter-value/effective-from #inst "2019-04-01"
    :kontor.parameter-value/decimal-value  0.30M
    :kontor.parameter-value/citation       "Finance Act First Schedule Part III A — long-standing default 30 %"}

   ;; §115BAA — effective AY 2020-21 (= FY 2019-20).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.115BAA.rate"]
    :kontor.parameter-value/effective-from #inst "2019-04-01"
    :kontor.parameter-value/decimal-value  0.22M
    :kontor.parameter-value/citation       "Taxation Laws (Amendment) Act 2019 (Act 46/2019) §4 — inserted §115BAA"}

   ;; §115BAB — same Act 46/2019, applies to new manufacturing cos.
   ;; incorporated ≥ 2019-10-01 and commencing manufacture ≤ 2024-03-31.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.115BAB.rate"]
    :kontor.parameter-value/effective-from #inst "2019-10-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "Taxation Laws (Amendment) Act 2019 (Act 46/2019) §5 — inserted §115BAB"}

   ;; Concessional flat 10 % surcharge — §115BAA / §115BAB regimes.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.concessional.surcharge-rate"]
    :kontor.parameter-value/effective-from #inst "2019-04-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "Finance Act 2020 §2 + First Schedule Part III A proviso (§115BAA / §115BAB cases)"}

   ;; Shared 4 % HEC cess — Finance Act 2018 replaced the prior
   ;; Education + SHEC stack with a single Health & Education Cess.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.cess.rate"]
    :kontor.parameter-value/effective-from #inst "2018-04-01"
    :kontor.parameter-value/decimal-value  0.04M
    :kontor.parameter-value/citation       "Finance Act 2018 §158 — replaced Education Cess + SHEC with HEC 4 %"}

   ;; MAT 15 % through FY 2025-26; 14 % from FY 2026-27 (Union Budget
   ;; 2025). Encoded as two date-keyed rows; `:effective-until` on the
   ;; first matches `:effective-from` on the second (the substrate
   ;; treats the boundary as half-open [from, until) per
   ;; `kontor.statute/parameter-value-at`).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "IN.CIT.MAT.rate"]
    :kontor.parameter-value/effective-from  #inst "2019-04-01"
    :kontor.parameter-value/effective-until #inst "2026-04-01"
    :kontor.parameter-value/decimal-value   0.15M
    :kontor.parameter-value/citation        "Finance (No. 2) Act 2019 §50 — MAT 18.5 % → 15 % from FY 2019-20"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.MAT.rate"]
    :kontor.parameter-value/effective-from #inst "2026-04-01"
    :kontor.parameter-value/decimal-value  0.14M
    :kontor.parameter-value/citation       "Union Budget 2025 — MAT 15 % → 14 % from FY 2026-27"}

   ;; Foreign-co rate — 40 % long-standing, reduced to 35 % by Finance
   ;; (No. 2) Act 2024 effective FY 2024-25 (AY 2025-26). The pre-cutover
   ;; row carries `:effective-until` so the bitemporal swap works at the
   ;; AY boundary (`:as-of #inst "2024-03-31"` ⇒ 40 %, `#inst
   ;; "2024-04-01"` ⇒ 35 %).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "IN.CIT.foreign.rate"]
    :kontor.parameter-value/effective-from  #inst "1989-04-01"
    :kontor.parameter-value/effective-until #inst "2024-04-01"
    :kontor.parameter-value/decimal-value   0.40M
    :kontor.parameter-value/citation        "Long-standing 40 % foreign-co rate (pre-FA-2024)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.CIT.foreign.rate"]
    :kontor.parameter-value/effective-from #inst "2024-04-01"
    :kontor.parameter-value/decimal-value  0.35M
    :kontor.parameter-value/citation       "Finance (No. 2) Act 2024 §2 — foreign-co 40 % → 35 % from FY 2024-25"}])

(def parameter-brackets
  "Surcharge bracket scales. Two parents × 3 bands each = 6 rows. Both
   are bigdecimal-typed so the substrate's bracket folder consumes them
   without coercion."
  [;; ----- Standard-regime surcharge — 0 / 7 / 12 % banded at ₹1cr / ₹10cr -----
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.standard.surcharge-brackets"]
    :kontor.parameter-bracket/index          0
    :kontor.parameter-bracket/rate           0M
    :kontor.parameter-bracket/upper          10000000M           ; ₹1 cr = ₹1,00,00,000
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.standard.surcharge-brackets"]
    :kontor.parameter-bracket/index          1
    :kontor.parameter-bracket/rate           0.07M
    :kontor.parameter-bracket/upper          100000000M          ; ₹10 cr = ₹10,00,00,000
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.standard.surcharge-brackets"]
    :kontor.parameter-bracket/index          2
    :kontor.parameter-bracket/rate           0.12M
    ;; open top band — schema allows :upper to be absent
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}

   ;; ----- Foreign-co surcharge — 0 / 2 / 5 % banded at ₹1cr / ₹10cr -----
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :kontor.parameter-bracket/index          0
    :kontor.parameter-bracket/rate           0M
    :kontor.parameter-bracket/upper          10000000M
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :kontor.parameter-bracket/index          1
    :kontor.parameter-bracket/rate           0.02M
    :kontor.parameter-bracket/upper          100000000M
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :kontor.parameter-bracket/index          2
    :kontor.parameter-bracket/rate           0.05M
    :kontor.parameter-bracket/effective-from #inst "2018-04-01"}])

;; ============================================================================
;; Regimes — :in-cit-standard / :in-cit-115BAA / :in-cit-115BAB
;; ============================================================================
;;
;; Election irrevocability (§115BAA cannot be revoked once elected) is
;; consumer-side — it belongs in the ADR-034 status-machine on the
;; entity's regime attr, NOT in the provider. The provider trusts
;; `:tax-unit :regime` and computes accordingly. See research note 163
;; §5.2 P1-B.

(def regimes
  [{:kontor.regime/code        :in-cit-standard
    :kontor.regime/label       "Standard regime — 25 %/30 % + banded 7 %/12 % surcharge; MAT (§115JB) applies"
    :kontor.regime/jurisdiction :in}

   {:kontor.regime/code        :in-cit-115BAA
    :kontor.regime/label       "§115BAA concessional — flat 22 % + flat 10 % surcharge; MAT-exempt per §115JB(5A); irrevocable"
    :kontor.regime/jurisdiction :in}

   {:kontor.regime/code        :in-cit-115BAB
    :kontor.regime/label       "§115BAB concessional — new-manufacturing flat 15 % + flat 10 % surcharge; MAT-exempt per §115JB(5A)"
    :kontor.regime/jurisdiction :in}])

;; ============================================================================
;; Provisions — IN CIT statute as :provision data
;; ============================================================================

(def provisions
  "IN CIT statutory provisions encoded for the `kontor.statute`
   evaluator. Conditions reference `:component` (set by the provider on
   each per-component pass — `:regular` or `:mat`) and consumer-supplied
   facts under `[:tax-unit ...]`.

   Consequences are `:schedule-override`s (standard / concessional /
   foreign / MAT rate) and `:surtax` compute-fns (surcharge bands +
   cess). All rates live in `:parameter` data — provisions reference
   them by code so a Finance Act rate-only amendment is a one-row
   migration."
  [;; --------------------------------------------------------------------
   ;; STANDARD REGIME — small-turnover (25 %) and large-turnover (30 %)
   ;; selected by :tax-unit :turnover-band (consumer pre-computes from
   ;; PY 2023-24 turnover — note 163 §5.2 P1-A pre-`:tax-unit` fact, no
   ;; two-pass query needed).
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-CIT-Standard-25"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Standard regime base — 25 % (PY 2023-24 turnover ≤ ₹400 cr)"
    :kontor.provision/citation        "Finance (No. 2) Act 2019 §2 + First Schedule Part III A"
    :kontor.provision/effective-from  #inst "2019-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:eq [:tax-unit :turnover-band] :small]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-cit-standard-small
                                        :label     "Standard regime, 25 % (small turnover)"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.standard.small-turnover-rate"}})}

   {:kontor.provision/code            "IN-CIT-Standard-30"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Standard regime base — 30 % (PY 2023-24 turnover > ₹400 cr)"
    :kontor.provision/citation        "Finance Act First Schedule Part III A — default 30 % rate"
    :kontor.provision/effective-from  #inst "2019-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:eq [:tax-unit :turnover-band] :large]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-cit-standard-large
                                        :label     "Standard regime, 30 % (large turnover)"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.standard.large-turnover-rate"}})}

   ;; --------------------------------------------------------------------
   ;; §115BAA — flat 22 % (irrevocable; MAT-exempt per §115JB(5A))
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-CIT-115BAA-22"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "§115BAA concessional flat 22 % — domestic-co election (irrevocable, Form 10-IC)"
    :kontor.provision/citation        "Income-tax Act §115BAA (Act 46/2019)"
    :kontor.provision/effective-from  #inst "2019-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-115BAA]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-cit-115BAA
                                        :label     "§115BAA flat 22 %"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.115BAA.rate"}})}

   ;; --------------------------------------------------------------------
   ;; §115BAB — flat 15 % (new-manufacturing; sunset 2024-03-31)
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-CIT-115BAB-15"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "§115BAB concessional flat 15 % — new manufacturing co. (commencement ≤ 2024-03-31)"
    :kontor.provision/citation        "Income-tax Act §115BAB (Act 46/2019); commencement sunset 2024-03-31 per Finance Act 2024"
    :kontor.provision/effective-from  #inst "2019-10-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-115BAB]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-cit-115BAB
                                        :label     "§115BAB flat 15 %"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.115BAB.rate"}})}

   ;; --------------------------------------------------------------------
   ;; FOREIGN COMPANY — 35 % flat (FY 2024-25+); 40 % pre-FA-2024 via
   ;; the parameter-value history. The provision is regime-agnostic
   ;; (foreign cos. don't elect §115BAA/§115BAB) — `[:tax-unit
   ;; :foreign-co?]` gates instead.
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-CIT-Foreign-Co"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Foreign-co CIT — 35 % (post-FA-2024) / 40 % (pre-FA-2024) per :parameter history"
    :kontor.provision/citation        "Income-tax Act §90 / §115A; Finance (No. 2) Act 2024 §2"
    :kontor.provision/effective-from  #inst "1989-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :foreign-co?] true]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-cit-foreign
                                        :label     "Foreign-co flat rate (40 %/35 %)"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.foreign.rate"}})}

   ;; --------------------------------------------------------------------
   ;; SURCHARGES — three provisions, one per regime path
   ;; --------------------------------------------------------------------
   ;; Standard regime: banded 0/7/12 with statutory marginal relief.
   {:kontor.provision/code            "IN-CIT-Surcharge-Standard"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "Standard-regime surcharge — banded 0/7/12 % (with marginal relief)"
    :kontor.provision/citation        "Finance Act 2025 First Schedule Part III A (Surcharge proviso)"
    :kontor.provision/effective-from  #inst "2018-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :in-surcharge-standard
                                        :label       "CIT surcharge — standard regime (banded, marginal relief)"
                                        :amount-from :compute-fn
                                        :fn          :in-cit-surcharge-standard})}

   ;; §115BAA / §115BAB: flat 10 %.
   {:kontor.provision/code            "IN-CIT-Surcharge-Concessional"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "§115BAA / §115BAB flat 10 % surcharge"
    :kontor.provision/citation        "Finance Act 2020 §2 + Act 46/2019"
    :kontor.provision/effective-from  #inst "2019-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:or
                                         [:eq [:tax-unit :regime] :in-cit-115BAA]
                                         [:eq [:tax-unit :regime] :in-cit-115BAB]]])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :in-surcharge-concessional
                                        :label       "Flat 10 % surcharge (§115BAA / §115BAB)"
                                        :amount-from :compute-fn
                                        :fn          :in-cit-surcharge-concessional})}

   ;; Foreign-co: banded 0/2/5, no marginal relief.
   {:kontor.provision/code            "IN-CIT-Surcharge-Foreign"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "Foreign-co surcharge — banded 0/2/5 % (no marginal relief)"
    :kontor.provision/citation        "Finance Act 2025 First Schedule Part III E"
    :kontor.provision/effective-from  #inst "2018-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :foreign-co?] true]])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :in-surcharge-foreign
                                        :label       "CIT surcharge — foreign co. (banded)"
                                        :amount-from :compute-fn
                                        :fn          :in-cit-surcharge-foreign})}

   ;; --------------------------------------------------------------------
   ;; HEC CESS — fires for both :regular and :mat components, all regimes
   ;; --------------------------------------------------------------------
   ;; Priority 500 ⇒ AFTER all surcharges (priority 100) in the
   ;; adjustment-layer fold, so `:running` at cess time is
   ;; `(gross + surcharge)` — exactly what the statute prescribes.
   {:kontor.provision/code            "IN-FinAct-Cess"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "Health & Education Cess — 4 % of (tax + surcharge); fires for :regular and :mat"
    :kontor.provision/citation        "Finance Act 2018 §158; Finance Act 2025 §2(12)"
    :kontor.provision/effective-from  #inst "2018-04-01"
    :kontor.provision/priority        500
    :kontor.provision/condition       (pr-str [:or
                                        [:eq :component :regular]
                                        [:eq :component :mat]])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :in-hec-cess
                                        :label       "Health & Education Cess (4 %)"
                                        :amount-from :compute-fn
                                        :fn          :in-cit-cess})}

   ;; --------------------------------------------------------------------
   ;; MAT (§115JB) — fires only when :regime is :in-cit-standard AND
   ;; :foreign-co? is not true. §115JB(5A) non-applicability for
   ;; §115BAA / §115BAB / foreign-co handled by condition gating.
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-MAT-115JB"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "§115JB Minimum Alternate Tax — 15 % on book profit (14 % from FY 2026-27)"
    :kontor.provision/citation        "Income-tax Act §115JB; Finance (No. 2) Act 2019 §50; Union Budget 2025"
    :kontor.provision/effective-from  #inst "2019-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :mat]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :kontor.provision/consequence     (pr-str {:op        :schedule-override
                                        :code      :in-mat-flat
                                        :label     "§115JB MAT flat rate"
                                        :schedule  {:schedule/type :flat
                                                    :rate-from     :parameter
                                                    :parameter     "IN.CIT.MAT.rate"}})}

   ;; MAT surcharge — same 0/7/12 standard bands as the regular regime
   ;; (the statute references Part III A for the MAT row). Re-uses the
   ;; :in-cit-surcharge-standard compute-fn.
   {:kontor.provision/code            "IN-MAT-Surcharge"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "MAT surcharge — same 0/7/12 standard bands (with marginal relief)"
    :kontor.provision/citation        "Finance Act 2025 First Schedule Part III A (MAT row)"
    :kontor.provision/effective-from  #inst "2018-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:eq :component :mat])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :in-mat-surcharge
                                        :label       "MAT surcharge (banded, marginal relief)"
                                        :amount-from :compute-fn
                                        :fn          :in-cit-surcharge-standard})}])

;; ============================================================================
;; install! — transact parameters + brackets + regimes + provisions into a conn
;; ============================================================================

(defn install!
  "Install the IN CIT statute (parameters + parameter-values +
   parameter-brackets + regimes + provisions) into `conn`. Idempotent —
   `:kontor.parameter/code`, `:kontor.regime/code`, and `:kontor.provision/code` are unique
   identity attrs, so re-running the install is a no-op on unchanged
   rows. (`:parameter-value` and `:parameter-bracket` lack a natural
   identity attr; the FR CIT statute has a small dedup helper for the
   bracket case — re-running install would duplicate bracket rows.
   v1 follows the JP / DE convention of trusting one-shot install per
   conn — the test fixture creates a fresh DB per test.)"
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn parameter-brackets)
  (d/transact conn regimes)
  (d/transact conn provisions))
