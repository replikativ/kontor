(ns kontor.payroll-au.core
  "kontor-payroll-au — AU-STP-Phase-2 payroll adapter (Stage R C6,
   ADR-080).

   This namespace is the consumer-facing entry point. Composes:
     - the existing `kontor.l10n-au` base chart + ABN/ACN identifiers +
       GST + BAS substrate,
     - the kontor-hr substrate (`kontor.hr.payroll/run-payroll!`,
       `kontor.payroll-provider/Payroll{Compute,Posting,Emit}Provider`),
     - the C6-shipped pieces: wage-types catalog, payroll-extension
       chart, Xero/MYOB CSV compute providers, posting builder,
       STP Phase 2 emit-provider, SuperStream helper, ROE-equivalent
       termination audit-doc helper.

   ## Install order

     1. `kontor.core/install-schema!`                  — kernel
     2. `kontor.hr.core/install!`                      — HR substrate
     3. `kontor.l10n-au.chart/install!`                — AU base chart (AUD)
     4. `kontor.payroll-au.core/install!`              — payroll extension +
                                                         `:state` analytic
                                                         plan (8 jurisdictions)

   ## Quickstart

   ```
   (require '[kontor.core :as kontor]
            '[kontor.hr.core :as hr]
            '[kontor.l10n-au.chart :as au-chart]
            '[kontor.payroll-au.core :as au-payroll])

   (def conn (kontor/create-test-db))
   (hr/install! conn)
   (au-chart/install! conn)              ; AU base chart (AUD commodity)
   (au-payroll/install! conn)            ; AU payroll chart + :state plan
   ```

   ## What this module ships

     - `kontor.payroll-au.wage-types`     — substrate-canonical AU
                                            wage-type catalog (with STP
                                            Phase 2 income-type slots) +
                                            `extras-map` extension seam.
     - `kontor.payroll-au.compute`        — XeroGlComputeProvider,
                                            MyobGlComputeProvider,
                                            ReckonOneComputeProvider
                                            (skeleton).
     - `kontor.payroll-au.posting-builder` — AuPayrollPostingBuilder
                                             (per-state allocation via
                                             :analytic-distribution).
     - `kontor.payroll-au.stp`            — STP Phase 2 event structure
                                            helpers + TFN validator.
     - `kontor.payroll-au.super`          — SuperStream contribution-
                                            message builder + audit-doc
                                            tx-data.
     - `kontor.payroll-au.emit`           — AuStpEmitProvider + the
                                            termination tx-data helper.
     - `kontor.payroll-au.chart`          — starter chart loader +
                                            account-tag installer.

   ## License posture (ADR-001 + ADR-005 + ADR-071 + ADR-080)

   - ATO Software Developers BIG (STP Phase 2 + SuperStream AFF) is
     a public specification published at softwaredevelopers.ato.gov.au.
     We work from spec only; no vendor code lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`.
   - SG rate (11.5 %, rising to 12 %), state payroll-tax rates,
     worker-comp premium rates: facts, not bundled.

   See also: doc/decisions.md ADR-080."
  (:require [datahike.api :as d]
            [kontor.payroll-au.chart :as chart]
            [kontor.payroll-au.compute :as compute]
            [kontor.payroll-au.emit :as emit]
            [kontor.payroll-au.posting-builder :as pb]
            [kontor.payroll-au.wage-types :as wage-types]))

;; ============================================================================
;; The :state analytic plan + per-state analytic accounts
;; ============================================================================
;; Mirror of `kontor.payroll-us-adp.core/install-state-analytic-plan!`.
;; An Australian Pty Ltd employing remote workers in multiple states
;; is ONE legal entity (one ABN, one Form 1120-equivalent). Per-state
;; lives on ADR-022 analytic distributions. The plan + 8 jurisdictions
;; install here so consumers don't need to.
;;
;; ISO-3166-2:AU subdivision codes:
;;   AU-ACT  Australian Capital Territory
;;   AU-NSW  New South Wales
;;   AU-NT   Northern Territory
;;   AU-QLD  Queensland
;;   AU-SA   South Australia
;;   AU-TAS  Tasmania
;;   AU-VIC  Victoria
;;   AU-WA   Western Australia

(def au-states
  "ISO-3166-2:AU — 6 states + 2 territories. The :state analytic plan
   installs one :analytic-account per entry. Codes use the bare 2/3-
   letter form (matching the US ADP pattern of `state:CA`); the full
   ISO `AU-NSW` form is the human reference."
  [["ACT" "Australian Capital Territory"]
   ["NSW" "New South Wales"]
   ["NT"  "Northern Territory"]
   ["QLD" "Queensland"]
   ["SA"  "South Australia"]
   ["TAS" "Tasmania"]
   ["VIC" "Victoria"]
   ["WA"  "Western Australia"]])

(defn install-state-analytic-plan!
  "Install the `:state` analytic plan + per-state `:analytic-account`
   rows. Idempotent: re-running with the same data is a no-op via
   `:db.unique/identity` on `:kontor.analytic-plan/code` +
   `:kontor.analytic-account/path`.

   The `:state` plan applies to consumer-marked wage / payroll-tax /
   benefit accounts via `:kontor.account/required-analytic-plans` (per
   ADR-022). The accounts themselves are NOT marked here; that's the
   consumer's chart install. We DO ship the plan + states so consumers
   don't need to."
  [conn]
  (let [plan-tempid "au-state-plan"
        plan-tx [{:db/id plan-tempid
                  :kontor.analytic-plan/code "state"
                  :kontor.analytic-plan/name "AU state of employment"
                  :kontor.analytic-plan/applicability :optional
                  :kontor.analytic-plan/active true}]
        account-tx (mapv (fn [[code label]]
                           {:kontor.analytic-account/path (str "state:" code)
                            :kontor.analytic-account/code code
                            :kontor.analytic-account/name label
                            :kontor.analytic-account/plan plan-tempid
                            :kontor.analytic-account/active true})
                         au-states)]
    (d/transact conn (vec (concat plan-tx account-tx)))))

(defn install!
  "Idempotent install of the payroll-au extension. Layers on top of
   `kontor.core/install-schema!` + `kontor.hr.core/install!` +
   `kontor.l10n-au.chart/install!`. Installs:

     - the payroll-au :account-tag entities + starter chart
       (unless `:tags-only? true` is passed),
     - the `:state` analytic plan + 8 jurisdictions.

   Pass `{:tags-only? true}` to skip the starter chart and only
   register the `:account-tag` entities (useful when the consumer has
   their own chart and only needs the tag vocabulary)."
  ([conn] (install! conn {}))
  ([conn {:keys [tags-only?]}]
   (if tags-only?
     (chart/install-tags! conn)
     (chart/install! conn))
   (let [db (d/db conn)
         already? (boolean (d/q '[:find ?e .
                                  :where [?e :kontor.analytic-plan/code "state"]]
                                db))]
     (when-not already?
       (install-state-analytic-plan! conn)))))

;; ============================================================================
;; Convenience constructors for the provider trio
;; ============================================================================

(defn make-xero-gl-compute-provider
  "Construct an `XeroGlComputeProvider`. Per ADR-080 the provider has
   no embedded config beyond defaults; consumers thread credentials /
   CSV source / employee-id-mapping through `:variable-inputs` at
   `run-payroll!` time."
  ([] (make-xero-gl-compute-provider {}))
  ([opts] (compute/->XeroGlComputeProvider opts)))

(defn make-myob-gl-compute-provider
  "Construct a `MyobGlComputeProvider`."
  ([] (make-myob-gl-compute-provider {}))
  ([opts] (compute/->MyobGlComputeProvider opts)))

(defn make-au-payroll-posting-builder
  "Construct an `AuPayrollPostingBuilder`. The only thing this carries
   is the `:commodity` (AUD typically) so `build-postings` can stamp
   it on every leg without the consumer threading it through
   `:variable-inputs`."
  [{:keys [commodity] :as opts}]
  (when-not commodity
    (throw (ex-info "make-au-payroll-posting-builder needs :commodity (AUD ref)"
                    {})))
  (pb/->AuPayrollPostingBuilder opts))

(defn make-au-stp-emit-provider
  "Construct an `AuStpEmitProvider`. Per-event metadata (ABN,
   pay-period bounds, pay-date, payees-info) is threaded via the
   provider's `:opts` and consumed at `emit-payroll-events` time."
  [opts]
  (emit/->AuStpEmitProvider opts))

;; Re-exports for one-import convenience.
(def standard-component-kinds wage-types/standard-component-kinds)
(def stp-pay-event-audit-doc-tx-data emit/build-stp-pay-event-audit-doc-tx-data)
