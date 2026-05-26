(ns kontor.payroll-provider
  "The PayrollProvider protocol trio — the kernel's seam for payroll
   compute, posting, and jurisdictional event-bus emission. ADR-075
   (Stage R substrate); mirrors the three-protocol shape established
   by ADR-071 (`TaxRateProvider` / `TaxFacts` / `TaxPostingBuilder`)
   plus a fourth `PayrollEmitProvider` for jurisdictions with
   mandatory event-bus reporting (DE LODAS Lohnimport, UK FPS,
   AU STP Phase 2, BR eSocial).

   ## The contract

   1. **`PayrollComputeProvider`** — gross-to-net engine. Pure
      function from a payroll context to a vector of `PayrollFacts`
      maps. Kontor does NOT re-implement jurisdictional payroll math
      (DE EStG + SGB, US FICA + multi-state, UK PAYE — each runs to
      hundreds of pages of regulator publications); a provider impl
      WRAPS the engine that does (DATEV LODAS, ADP, Gusto, Wagepoint).
   2. **`PayrollFacts`** — pure data shape passed between the three
      protocols. Per-employee + per-component (`:base-wage`,
      `:bonus`, `:employer-si`, `:withholding-tax`, …) with gross,
      net, and a `:jurisdiction-specific-codes` opaque slot so a
      country adapter can carry whatever it needs without polluting
      the substrate vocabulary.
   3. **`PayrollPostingBuilder`** — materializes GL postings from
      `PayrollFacts`. Per-country chart-of-accounts mapping
      (DE SKR04 wage accounts, US QBO-shaped wage accounts). The
      builder consumes a consumer-supplied `:accounts` map
      (component-kind → :account ref); kontor does NOT bundle a
      catalog.
   4. **`PayrollEmitProvider`** — jurisdictional event-bus emissions.
      Returns a vector of `:audit-doc` entities (one per required
      emission). Transmission is consumer-held credential / endpoint
      URL (mirrors `:sent-by-consumer?` per ADR-017). The default
      `LocalfileEmitProvider` writes events as `:audit-doc` rows with
      `:kontor.audit-doc/category :tax-filing` for manual upload — adequate
      for US (no clearance regime).

   ## Composition

   `kontor.hr.payroll/run-payroll!` composes the three providers
   through `kontor.process/run-process` (ADR-067):

     1. `compute-payroll` → vector of `PayrollFacts`
     2. `build-postings`   → vector of posting maps (ADR-068 builders)
     3. `emit-payroll-events` → vector of `:audit-doc` rows

   All transacted atomically with the legal-hold + period-lock +
   status-machine gate stack honored (kernel's existing discipline).

   ## Component-kind enum (open-set)

   The substrate ships a recommended set; consumers extend.
   `:base-wage` (the default), `:bonus`, `:overtime`,
   `:imputed-income` (geldwerter Vorteil), `:employer-si` (DE AG-
   Anteil SV / US employer FICA), `:employee-si` (DE AN-Anteil SV
   / US employee FICA), `:employer-pension` (DE betriebliche
   Altersvorsorge / US 401(k) match), `:employee-pension`,
   `:withholding-tax` (DE Lohnsteuer / US federal income tax),
   `:garnishment`, `:voluntary-deduction`, `:equity-vest`.

   The substrate does NOT enforce specific kinds; the per-country
   adapter decides which kinds its `compute-payroll` produces.

   ## Posture

   Same as ADR-005 / ADR-071 / ADR-072:
     - Never bundle vendor API keys.
     - Never bundle wage-type catalogs / SKR04 line tables / W-2 box
       maps proprietary to a payroll vendor.
     - The consumer holds the engine credential; kontor consumes the
       engine's output.

   See also: [[doc/research/79-hr-payroll-stage-r-plan]] §4.

   ## Per-adapter conventions (note 86 P2-86-2 — the canonical key
   matrix for the three shipped adapters; future adapters reuse).

   ### `:variable-inputs` keys consumed by `compute-payroll`

     | Adapter                           | Required keys                                                               | Optional keys |
     |-----------------------------------|-----------------------------------------------------------------------------|--------------- |
     | `:datev-lodas`                    | one of `:buchungsbeleg-content` (String EXTF CSV) OR `:facts` (pre-parsed)  | `:pay-period-date` |
     | `:adp-gli`                        | `:adp-gli-csv-source`, `:wage-type-map`, `:employee->employment`            | — |
     | `:ceridian-dayforce`              | `:csv-source`, `:column-mapping`, `:pay-element-codes`, `:external-id->eid` | `:extras-map` |
     | `:adp-canada`                     | `:csv-source`, `:pay-element-codes`, `:external-id->eid`                    | `:headerless?` |
     | `:wagepoint-api` (skeleton)       | (partner-program-gated; OAuth credential + HTTP client)                     | — |

   New adapters SHOULD pick `:csv-source` as the canonical CSV-source
   key (matches CA Ceridian + ADP-CA); the US adapter's
   `:adp-gli-csv-source` is the legacy outlier from independent
   worktree work (P2-86-4 — defer rename to C5 prep).

   ### Per-country accrual primitives — three correct patterns per
   country accounting context (note 86 §2.3, P2-86-3):

     | Adapter | Accrual surface                                                            | Standard                | Composition |
     |---------|----------------------------------------------------------------------------|-------------------------|--------------|
     | DE      | `urlaubsrueckstellung-amount` + `-tx-data` in `posting-builder` ns         | HGB §249                | Out-of-band — consumer composes outside `run-payroll!`; `:framework :hgb-handelsbilanz | :de-steuerbilanz` knob |
     | US      | `asc-710-pto-accrual-tx-data` + `er-401k-match-accrual-tx-data` (sep. ns)  | ASC 710 + IRC §404(a)(6) | Out-of-band — `!` wrappers route through `transact-with-validation`; book-only via `:ledgers #{:us-gaap}` |
     | CA      | `:vacation-pay-accrual` component-kind                                     | ESA (per-province rate) | In-band — engine emits the accrual component; posting-builder routes automatically |

   The divergence is appropriate (per-country accounting standards
   genuinely differ) but consumers wanting both should expect three
   different APIs. The 'engine-emits-accrual ⇒ in-band; consumer-
   computes-accrual ⇒ out-of-band' rule is the unifying principle.")

;; ============================================================================
;; PayrollFacts — the data shape between the three protocols
;; ============================================================================
;; A `PayrollFacts` map per employee per pay-period:
;;
;;   {:employment      <ref or eid of :employment>
;;    :pay-period      <ref or eid of :pay-period>
;;    :commodity       <ref or eid of :commodity — pay currency>
;;    :gross           <BigDecimal — total gross before deductions>
;;    :net             <BigDecimal — what employee receives>
;;    :components      [{:kind  :base-wage | :bonus | … (open-set)
;;                        :amount   <BigDecimal — signed: + = earned;
;;                                   - = withheld/deducted>
;;                        :employer-side?  <bool — true for AG-SV,
;;                                          401(k) match, etc.>
;;                        :jurisdiction-codes <opaque map>}]
;;    :jurisdiction-specific-codes <opaque map per provider-id>}
;;
;; The sum invariant the substrate enforces (in
;; kontor.hr.payroll/check-facts):
;;   gross = Σ (positive employee-side :amount)
;;   net   = gross + Σ (negative employee-side :amount)
;;
;; Employer-side components don't affect employee gross / net but
;; produce their own posting legs.

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol PayrollComputeProvider
  "Gross-to-net engine wrapper. Pure: rate-lookup + math, NO transact."
  (provider-id [this]
    "Keyword identifying this provider — :datev-lodas | :adp-gli |
     :gusto-api | :wagepoint-api | :ceridian-dayforce | :hmrc-rti |
     :ato-stp | :static-table (default for unit tests) — used in
     audit logs + per-provider routing.")

  (compute-payroll [this {:as ctx
                          :keys [pay-period-eid entity-eid
                                 employment-eids variable-inputs
                                 as-of]}]
    "PURE function: rate-lookup + math, no transact. Returns a vector
     of PayrollFacts maps (one per employment in `:employment-eids`).

     Required ctx keys:
       :pay-period-eid    — eid of the :pay-period this run covers
       :entity-eid        — eid of the :entity (employer per ADR-031)
       :employment-eids   — vector of :employment eids to compute

     Optional ctx keys:
       :variable-inputs   — map keyed by employment-eid carrying pay-
                            period-specific overrides (overtime hours,
                            one-time bonus, RSU vest events, retro
                            adjustments). Consumer assembles from
                            :analytic-line (timesheets), :equity-event,
                            etc.
       :as-of             — java.util.Date; defaults to (Date.). Used
                            for bitemporal effective-dating of inputs
                            (the :compensation effective at as-of).

     Throws ex-info on missing data; does NOT silently zero.
     Returns []  if `:employment-eids` is empty."))

(defprotocol PayrollPostingBuilder
  "Materializes GL postings from PayrollFacts. Per-country CoA map
   is consumer-supplied — kontor never bundles a chart."
  (build-postings [this payroll-facts
                   {:keys [accounts ledger fx-provider]}]
    "Returns a vector of posting maps shaped per the ADR-068 *-tx-data
     builder convention. Each posting map is a flat map of
     :kontor.posting/* attrs, ready for kontor.posting/build-transaction-tx-data.

     `:accounts` is a per-component-kind → :account-ref map. The
     consumer supplies it from the company's chart of accounts.
     Missing kinds default to `:accounts/wages-default` if present;
     otherwise throw.

     `:ledger` is the target :ledger eid (ADR-021 — multi-ledger
     book/tax-basis split). Per the German HGB §249 case, payroll
     postings typically land on both the IFRS book ledger AND the
     tax ledger; the orchestrator may invoke this builder twice
     with different :ledger values.

     `:fx-provider` per ADR-072, used when an employee is paid in a
     commodity other than the entity's functional currency."))

(defprotocol PayrollEmitProvider
  "Jurisdictional event-bus emissions — DE LODAS Lohnimport,
   UK FPS, AU STP Phase 2, BR eSocial. The substrate's contract is
   'round-trip the event payload as an :audit-doc'; transmission is
   consumer-held."
  (emit-payroll-events [this payroll-facts
                        {:keys [pay-period-eid entity-eid]}]
    "Returns a vector of `:audit-doc` tx-data maps (one per required
     emission). Each is shaped per ADR-038 + carries
     `:kontor.audit-doc/category :tax-filing`. The orchestrator (`run-payroll!`)
     transacts these alongside the postings; the consumer's own
     workflow uploads them to the regulator via the engine.

     Default impl (`LocalfileEmitProvider`) wraps every fact as a
     single audit-doc keyed by pay-period; per-jurisdiction impls
     produce structured payloads (LODAS Lohnimport file, FPS XML, …).

     Returns [] when the jurisdiction has no required emission
     (e.g. US: no clearance regime, payroll-side filings via
     ADP/Gusto direct to IRS / state)."))

;; ============================================================================
;; Default impls (no-op stubs that prove the protocol is satisfiable)
;; ============================================================================

(defrecord StaticTableComputeProvider [opts]
  PayrollComputeProvider
  (provider-id [_] :static-table)
  (compute-payroll [_ {:keys [employment-eids]}]
    ;; Stub: returns an empty fact per employment. The kontor-hr
    ;; payroll-run test uses a hand-written provider; real
    ;; jurisdictions ship their own impls.
    (mapv (fn [eid]
            {:employment eid
             :gross 0M :net 0M :components []
             :jurisdiction-specific-codes {}})
          (or employment-eids []))))

(defrecord LocalfileEmitProvider [opts]
  PayrollEmitProvider
  (emit-payroll-events [_ _facts _ctx]
    ;; Default: no emission. US uses this directly.
    []))
