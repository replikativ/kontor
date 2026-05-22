(ns kontor.period-tax-provider
  "`PeriodTaxProvider` — ADR-099, research note 102. The period-tax
   sibling of ADR-071's `TaxRateProvider`.

   `TaxRateProvider` handles *transaction-incident* taxes (VAT, sales
   tax) — one `TaxFacts` per invoice line. `PeriodTaxProvider` handles
   *period/entity-incident* taxes — personal and corporate income tax,
   capital gains, property / wealth tax, employer payroll tax — which
   attach to an ENTITY over a PERIOD and are computed from an
   AGGREGATE through a SCHEDULE.

   Both are the same general form `(scope, base-selector, schedule) →
   liability → posting`; they fill the slots with categorically
   different contents, so they are siblings, not parent/child, and
   share no protocol operation. See note 102 §0.

   ## The pieces

   - `PeriodTaxProvider/period-tax-facts` — the *irregular* half: a
     jurisdiction's T1 / Einkommensteuer / 1120 logic, returning a
     `TaxReturnFacts` (or nil). Body unconstrained; only the output
     type is fixed.
   - `TaxReturnFacts` — the inter-protocol data contract (mirrors
     `TaxFacts`): a component vector over the closed `period-tax-kinds`
     enum.
   - the base-selector — `kontor.report/marginalize` (ADR-096) IS the
     period tax's base; a flow base is a windowed σ_E, a wealth-tax
     stock base a cumulative roll-up. note 102 §3b.
   - the schedule — `kontor.tax-schedule/apply-schedule`.
   - `kontor.tax-return-posting-builder/TaxReturnPostingBuilder` — the
     *regular* half: `TaxReturnFacts` → GL provision + payment
     transactions.

   This namespace ships the protocol + `TaxReturnFacts` + its helpers;
   per-jurisdiction providers (CA T1, etc.) are companions, migrated
   consumer-demand-driven exactly as the transaction providers were
   (note 100)."
  (:require [kontor.money :as money]))

;; ============================================================================
;; The closed period-tax :kind enum (note 102 §2.1)
;; ============================================================================

(def period-tax-kinds
  "The closed set of period-tax `:kind` values a `TaxReturnFacts`
   component may carry. A genuine 9th mechanism is an ADR-gated enum
   extension, never a quiet per-jurisdiction flag (note 101's
   discipline). `:minimum-tax` and `:branch-or-presumptive-tax` are
   in the set deliberately — they name note 102 §7's stressed cases
   so the stress is ADR-trackable."
  #{:personal-income-tax        ; CA T1, DE Einkommensteuer, US 1040
    :corporate-income-tax       ; CA T2, DE Körperschaftsteuer, US 1120
    :capital-gains-tax          ; CA S3, US Sch-D — note 102 §7, the hybrid
    :property-tax               ; real-property / land tax
    :wealth-tax                 ; net-worth tax — base is a balance snapshot
    :payroll-tax-employer       ; standalone employer payroll levy (MX ISN,
                                ;   AU state payroll tax) — NOT SI contributions
    :minimum-tax                ; AMT / corporate minimum — a schedule
                                ;   composition (note 102 §7 / §9-A)
    :branch-or-presumptive-tax}) ; presumptive / imputed-income regimes —
                                ;   base not in the books (note 102 §7)

;; ============================================================================
;; TaxReturnFacts — the inter-protocol data contract (note 102 §2)
;; ============================================================================

(defrecord TaxReturnFacts
           [entity                ; the assessed entity ref (ADR-031)
            period                ; {:from #inst :to #inst} — assessment window
            jurisdiction          ; {:country :subdivision :authority}
            functional-commodity  ; the commodity liabilities are denominated in
            components])          ; vector of component maps

;; A component map (see note 102 §2):
;;   {:kind         <closed period-tax-kinds enum>
;;    :authority    <kw|nil>  the taxing authority for this component —
;;                            lets one return fan out across governments
;;                            (CA federal :cra + provincial :bc; US 50
;;                            states). nil = the return's top-level
;;                            :jurisdiction
;;    :base         <Money>   the resolved taxable base (base-selector output)
;;    :base-transform <data>  optional kontor.tax-schedule transform from the
;;                            marginalized aggregate to :base — corporate
;;                            add-backs / BR Lucro Presumido (ADR-099
;;                            addendum, note 103 GAP 1)
;;    :schedule     <data>    the kontor.tax-schedule that produced the gross
;;    :gross-liability <Money> base through the schedule, before credits
;;    :credits      [<{:code :label :amount}>]  credits applied
;;    :surtaxes     [<{:code :label :amount}>]  tax-on-tax surcharges
;;                            added AFTER credits (DE Soli, church tax,
;;                            IN/BR cess) — symmetric with :credits;
;;                            :liability = gross − Σcredits + Σsurtaxes
;;    :liability    <Money>   resolved net tax owed — the number provisioned
;;    :prepaid      <Money>   tax already remitted in-period (withholding)
;;    :regime       <kw|nil>  which elective regime applied (note 102 §9-C)
;;    :composed-of  [<kw>]    other component :kinds this one is derived from —
;;                            for surtaxes / minimum taxes (note 102 §9-A/D)
;;    :provenance   {:provider-id :statute :computed-at :form}
;;    :line-items   [<{:line :label :value}>]  the return's form detail
;;    :jurisdiction-specific-codes  <opaque map>}

(defn tax-return-facts
  "Construct a `TaxReturnFacts`."
  [{:keys [entity period jurisdiction functional-commodity components]}]
  (->TaxReturnFacts entity period jurisdiction functional-commodity
                    (vec components)))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol PeriodTaxProvider
  "Resolve the period/entity-incident tax one entity owes for one
   period. Determination only — no chart of accounts; the
   `TaxReturnPostingBuilder` materializes the GL."
  (provider-id [this]
    "A keyword identifying the implementation — :ca-t1, :de-est,
     :static-schedule, … — used in `:provenance` and logs.")
  (period-tax-facts [this context]
    "Given an entity × period `context`, return a `TaxReturnFacts`
     (or `nil` when the entity owes no period tax of this kind).

     Context keys:
       :entity       — the assessed entity ref (ADR-031)
       :period       — the assessment window {:from #inst :to #inst}
       :base-period  — optional — a base window distinct from :period
                       (JP inhabitant tax: this year on last year's
                       income; note 102 §9-E)
       :db           — the db the base is marginalized over
       :as-of-tx / :as-of-valid — optional bitemporal axis overrides
       :tax-unit     — optional household/filing-unit descriptor, for
                       schedules indexed by it (FR quotient familial;
                       note 102 §9-D)
       :inputs       — out-of-books facts the provider needs that are
                       NOT derivable from postings: a presumptive base,
                       a property assessed value, a regime election
                       (note 102 §7). The capital-loss carry-in uses a
                       fixed key — `:inputs {:capital-loss-carryforward
                       {:short <Money> :long <Money>}}` — and the residual
                       is reported back in `:line-items` (ADR-099
                       addendum, note 103 §3a).

     Determination is pure: it reads the db, never writes."))

;; ============================================================================
;; TaxReturnFacts helpers (note 102 §2)
;; ============================================================================

(defn assessed?
  "True when `facts` carries at least one component with a non-zero
   `:liability`."
  [facts]
  (boolean
   (and facts
        (some (fn [c] (and (:liability c)
                           (not (zero? (:amount (:liability c))))))
              (:components facts)))))

(defn- sum-money
  [facts field]
  (let [comm (:functional-commodity facts)]
    (reduce (fn [acc c]
              (if-let [m (get c field)]
                (money/add acc m)
                acc))
            (money/zero comm)
            (:components facts))))

(defn total-liability
  "Σ of every component's `:liability` (Money)."
  [facts]
  (sum-money facts :liability))

(defn total-prepaid
  "Σ of every component's `:prepaid` (Money) — tax already remitted
   in-period (withholding, instalments)."
  [facts]
  (sum-money facts :prepaid))

(defn balance
  "`total-liability − total-prepaid` (Money). Positive ⇒ the entity
   owes; negative ⇒ a refund is due."
  [facts]
  (money/sub (total-liability facts) (total-prepaid facts)))

(defn valid-return-facts?
  "Structural / closed-vocabulary check (validation layer 1, note 102
   §5): `facts` is a `TaxReturnFacts` whose every component carries a
   `:kind` from the closed `period-tax-kinds` set and a `:liability`.
   An unknown `:kind` is the signal a provider has outrun the
   vocabulary — extend the enum by ADR, never special-case."
  [facts]
  (and (instance? TaxReturnFacts facts)
       (every? (fn [c]
                 (and (contains? period-tax-kinds (:kind c))
                      (some? (:liability c))))
               (:components facts))))
