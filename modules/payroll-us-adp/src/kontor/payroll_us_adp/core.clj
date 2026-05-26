(ns kontor.payroll-us-adp.core
  "kontor-payroll-us-adp — ADP General Ledger Interface adapter
   (Stage R C3, ADR-077).

   Companion module on top of `kontor-hr` (Stage R substrate, ADR-075)
   that wraps ADP's GLI CSV export, classifies wage-type rows against
   a consumer-supplied mapping, and produces balanced GL postings on
   the parallel book + tax ledgers.

   ## Install order

     1. `kontor.core/install-schema!`            — kernel
     2. `kontor.hr.core/install!`                — HR substrate
     3. `kontor.payroll-us-adp.core/install!`    — this; installs the
                                                   `:state` analytic
                                                   plan + all 56 US
                                                   states / territories

   ## What this module ships

     - `kontor.payroll-us-adp.compute`         — AdpGliComputeProvider
                                                 (parses the 10-column
                                                 GLI CSV, handles the
                                                 balancing-row trap)
     - `kontor.payroll-us-adp.posting-builder` — UsPayrollPostingBuilder
                                                 (multi-state alloc via
                                                 :analytic-account/state,
                                                 parallel-ledger split)
     - `kontor.payroll-us-adp.accrual`         — ASC 710 PTO + 401(k)
                                                 match accrual
                                                 (book / tax ledger
                                                 split per IRC §461(h)
                                                 + §404(a)(6))
     - `kontor.payroll-us-adp.wage-types`      — consumer-extensible
                                                 wage-type catalog
                                                 (loads EDN; reference
                                                 fixture under
                                                 resources/)
     - `kontor.payroll-us-adp.w2-recon`        — year-to-date per-
                                                 employee report
                                                 (Box 1 / 3 / 5 / 12
                                                 reconciliation)

   ## License posture (ADR-077 — same posture as ADR-005 / ADR-071 / ADR-075)

   - NEVER lifts ADP code (we work from public spec only).
   - NEVER bundles ADP / Gusto / Paychex / Rippling API credentials.
   - NEVER bundles customer CoAs or wage-type rate tables.
   - The reference wage-type-map shipped under `resources/` is
     illustrative + based on public ADP description vocabulary;
     consumers copy + edit it.

   See doc/decisions.md ADR-077 + doc/research/83-us-adp-gli-research-before.md
   for the design rationale."
  (:require [datahike.api :as d]
            [kontor.payroll-us-adp.accrual :as accrual]
            [kontor.payroll-us-adp.compute :as compute]
            [kontor.payroll-us-adp.posting-builder :as pb]
            [kontor.payroll-us-adp.wage-types :as wage-types]))

;; ============================================================================
;; The :state analytic plan + per-state analytic accounts (note 83 §4)
;; ============================================================================
;; Per note 83 §4 — multi-state allocation uses :analytic-account, NOT
;; :kontor.posting/entity. A US LLC with 15 remote-employee states is ONE
;; legal entity (one Form 1120). Per-state lives on ADR-022 analytic
;; distributions; we install the plan + 50 states + DC + 5 territories
;; here so consumers don't need to.
;;
;; ISO-3166-2:US codes for the 50 states + DC + 5 territories.

(def us-states
  "ISO-3166-2:US — 50 states + DC + 5 territories. The :state analytic
   plan installs one :analytic-account per entry."
  [["AL" "Alabama"]
   ["AK" "Alaska"]
   ["AZ" "Arizona"]
   ["AR" "Arkansas"]
   ["CA" "California"]
   ["CO" "Colorado"]
   ["CT" "Connecticut"]
   ["DE" "Delaware"]
   ["FL" "Florida"]
   ["GA" "Georgia"]
   ["HI" "Hawaii"]
   ["ID" "Idaho"]
   ["IL" "Illinois"]
   ["IN" "Indiana"]
   ["IA" "Iowa"]
   ["KS" "Kansas"]
   ["KY" "Kentucky"]
   ["LA" "Louisiana"]
   ["ME" "Maine"]
   ["MD" "Maryland"]
   ["MA" "Massachusetts"]
   ["MI" "Michigan"]
   ["MN" "Minnesota"]
   ["MS" "Mississippi"]
   ["MO" "Missouri"]
   ["MT" "Montana"]
   ["NE" "Nebraska"]
   ["NV" "Nevada"]
   ["NH" "New Hampshire"]
   ["NJ" "New Jersey"]
   ["NM" "New Mexico"]
   ["NY" "New York"]
   ["NC" "North Carolina"]
   ["ND" "North Dakota"]
   ["OH" "Ohio"]
   ["OK" "Oklahoma"]
   ["OR" "Oregon"]
   ["PA" "Pennsylvania"]
   ["RI" "Rhode Island"]
   ["SC" "South Carolina"]
   ["SD" "South Dakota"]
   ["TN" "Tennessee"]
   ["TX" "Texas"]
   ["UT" "Utah"]
   ["VT" "Vermont"]
   ["VA" "Virginia"]
   ["WA" "Washington"]
   ["WV" "West Virginia"]
   ["WI" "Wisconsin"]
   ["WY" "Wyoming"]
   ["DC" "District of Columbia"]
   ["PR" "Puerto Rico"]
   ["VI" "US Virgin Islands"]
   ["GU" "Guam"]
   ["AS" "American Samoa"]
   ["MP" "Northern Mariana Islands"]])

(defn install-state-analytic-plan!
  "Install the :state analytic plan + per-state :analytic-account rows.
   Idempotent: re-running with the same data is a no-op (uses
   :db.unique/identity on :analytic-plan/code + :analytic-account/path).

   The :state plan applies to *consumer-marked* wage / payroll-tax /
   benefit accounts via :kontor.account/required-analytic-plans (per ADR-022).
   We do NOT mark the accounts here — that's the consumer's chart
   install. We DO ship the plan + states so consumers don't need to."
  [conn]
  (let [plan-tempid "state-plan"
        plan-tx [{:db/id plan-tempid
                  :analytic-plan/code "state"
                  :analytic-plan/name "US state of work"
                  :analytic-plan/applicability :optional
                  :analytic-plan/active true}]
        account-tx (mapv (fn [[code label]]
                           {:analytic-account/path (str "state:" code)
                            :analytic-account/code code
                            :analytic-account/name label
                            :analytic-account/plan plan-tempid
                            :analytic-account/active true})
                         us-states)]
    (d/transact conn (vec (concat plan-tx account-tx)))))

(defn install!
  "Install the kontor-payroll-us-adp companion. Currently:
     - Installs the `:state` analytic plan + 56 :analytic-account rows
       (50 states + DC + 5 territories) idempotently.

   Run AFTER `kontor.core/install-schema!` + `kontor.hr.core/install!`."
  [conn]
  (let [db (d/db conn)
        already? (boolean (d/q '[:find ?e .
                                 :where [?e :analytic-plan/code "state"]]
                               db))]
    (when-not already?
      (install-state-analytic-plan! conn))))

;; ============================================================================
;; Convenience constructors for the provider trio
;; ============================================================================

(defn make-adp-gli-compute-provider
  "Construct an `AdpGliComputeProvider`. Per ADR-077 the provider has
   no embedded config — credentials / CSV source / employee-id-mapping
   are all passed at `run-payroll!` time via `:variable-inputs`."
  ([] (make-adp-gli-compute-provider {}))
  ([opts] (compute/->AdpGliComputeProvider opts)))

(defn make-us-payroll-posting-builder
  "Construct a `UsPayrollPostingBuilder`. The only thing this carries
   is the commodity (USD) so `build-postings` can stamp it on every
   leg without the consumer threading it through `:variable-inputs`."
  [{:keys [commodity] :as opts}]
  (when-not commodity
    (throw (ex-info "make-us-payroll-posting-builder needs :commodity (USD ref)"
                    {})))
  (pb/->UsPayrollPostingBuilder opts))

;; Re-exports for one-import convenience.
(def load-reference-wage-type-map wage-types/load-reference)
(def load-wage-type-map-from-resource wage-types/load-from-resource)

(def asc-710-pto-accrual! accrual/asc-710-pto-accrual!)
(def asc-710-pto-accrual-tx-data accrual/asc-710-pto-accrual-tx-data)
(def er-401k-match-accrual! accrual/er-401k-match-accrual!)
(def er-401k-match-accrual-tx-data accrual/er-401k-match-accrual-tx-data)
(def tax-recognize-401k-match! accrual/tax-recognize-401k-match!)
