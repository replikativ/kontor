(ns kontor.hr.payroll
  "kontor-hr payroll orchestrator. Composes the three PayrollProvider
   protocols (kontor.payroll-provider) into one atomic
   `kontor.process/run-process`-driven transaction:

     1. PayrollComputeProvider/compute-payroll  → vector of PayrollFacts
     2. PayrollPostingBuilder/build-postings    → vector of posting maps
        wrapped into one :transaction via
        kontor.posting/build-transaction-tx-data
     3. PayrollEmitProvider/emit-payroll-events → vector of :audit-doc rows
     4. Create the :payroll-run row + state-transition seeds + linkage

   The kernel's gate stack (legal-hold + period-lock + status-machine
   + datalog invariants) fires inside transact-with-validation, the
   default commit fn used by run-process.

   The substrate's check on payroll-facts (sum invariant): for each
   PayrollFact, gross must equal Σ positive employee-side
   :compensation-component/amount and net must equal gross minus Σ
   absolute-value negative deductions. The check throws ex-info on
   first mismatch (no silent passing).

   Per ADR-005 / ADR-071 / ADR-075: the substrate ships the
   orchestration shape; it does NOT bundle per-vendor rate tables,
   per-jurisdiction CoA, or vendor API credentials."
  (:require [datahike.api :as d]
            [kontor.payroll-provider :as pp]
            [kontor.posting :as posting]
            [kontor.process :as process])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; check-facts — the sum invariant
;; ============================================================================

(defn check-facts
  "Validate a single PayrollFacts map's sum invariant. Throws on
   mismatch; returns the fact unchanged. Tolerates a 1-cent rounding
   slack on the net check (engines may round differently than
   accumulated sums).

   employer-side components do NOT participate in gross/net (they
   produce their own posting legs but aren't paid to / withheld from
   the employee)."
  [{:keys [employment gross net components] :as fact}]
  (when-not (and gross net components)
    (throw (ex-info "PayrollFacts missing required fields"
                    {:employment employment
                     :missing (remove fact #{:gross :net :components})})))
  (let [pos-sum (->> components
                     (remove :employer-side?)
                     (map :amount)
                     (filter #(pos? (compare ^BigDecimal % 0M)))
                     (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))
        neg-sum (->> components
                     (remove :employer-side?)
                     (map :amount)
                     (filter #(neg? (compare ^BigDecimal % 0M)))
                     (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))
        derived-net (.add ^BigDecimal pos-sum ^BigDecimal neg-sum)
        slack 0.01M]
    (when (pos? (compare (.abs (.subtract ^BigDecimal pos-sum ^BigDecimal gross))
                         slack))
      (throw (ex-info "PayrollFacts: gross != Σ positive employee-side components"
                      {:employment employment :gross gross :sum pos-sum})))
    (when (pos? (compare (.abs (.subtract ^BigDecimal derived-net ^BigDecimal net))
                         slack))
      (throw (ex-info "PayrollFacts: net != gross + Σ deductions"
                      {:employment employment :net net :derived derived-net}))))
  fact)

;; ============================================================================
;; create-payroll-run-tx-data
;; ============================================================================

(defn create-payroll-run-tx-data
  "Pure tx-data builder for the :payroll-run row + state-transition."
  [_db {:keys [code pay-period provider-id facts tempid]
        :or {tempid "payroll-run-1"}}]
  (when-not code        (throw (ex-info ":code required" {})))
  (when-not pay-period  (throw (ex-info ":pay-period required" {})))
  (when-not provider-id (throw (ex-info ":provider-id required" {})))
  (let [gross-total (->> facts (map :gross) (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))
        net-total   (->> facts (map :net)   (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))]
    [{:db/id tempid
      :payroll-run/code code
      :payroll-run/pay-period pay-period
      :payroll-run/provider-id provider-id
      :payroll-run/state :computed
      :payroll-run/control-total-gross gross-total
      :payroll-run/control-total-net net-total}]))

;; ============================================================================
;; run-payroll!
;; ============================================================================

(defn run-payroll!
  "Run a payroll for a (:pay-period, :entity) pair using the supplied
   provider trio. Atomically:
     - computes PayrollFacts (compute-provider)
     - validates each fact's sum invariant
     - builds GL postings (posting-builder) and wraps them in one
       :transaction via kontor.posting/build-transaction-tx-data
     - emits jurisdictional event :audit-docs (emit-provider; default
       LocalfileEmitProvider returns [])
     - creates the :payroll-run row with control totals
     - composes via kontor.process/run-process so the kernel gate
       stack fires once.

   Required opts:
     :pay-period      — eid or :pay-period/code
     :entity          — eid of :entity (ADR-031)
     :employments     — vector of :employment eids to include
     :compute-provider — satisfies PayrollComputeProvider
     :posting-builder  — satisfies PayrollPostingBuilder
     :accounts         — map keyed by component-kind → :account ref
                         (consumer-supplied CoA)
     :run-code         — string for :payroll-run/code
     :tx-code          — string for the :transaction/code

   Optional opts:
     :emit-provider    — satisfies PayrollEmitProvider
                         (default: LocalfileEmitProvider — no emit)
     :variable-inputs  — map employment-eid → engine-specific overrides
     :ledger           — target :ledger (ADR-021); default kernel main
     :fx-provider      — FxRateProvider (ADR-072) for currency
                         translation when employee currency ≠ entity
                         functional currency
     :journal          — ref to :journal for the GL entry
     :commodity        — fallback :commodity for the :transaction
     :vt-from / :vt-to — bitemporal bounds applied to the run

   Returns the tx-report from transact-with-validation."
  [conn {:keys [pay-period entity employments compute-provider
                posting-builder emit-provider accounts run-code tx-code
                variable-inputs ledger fx-provider journal commodity
                vt-from vt-to]
         :or {emit-provider (pp/->LocalfileEmitProvider {})}}]
  (when-not pay-period       (throw (ex-info ":pay-period required" {})))
  (when-not entity           (throw (ex-info ":entity required" {})))
  (when (empty? employments) (throw (ex-info ":employments must be non-empty" {})))
  (when-not compute-provider (throw (ex-info ":compute-provider required" {})))
  (when-not posting-builder  (throw (ex-info ":posting-builder required" {})))
  (when-not run-code         (throw (ex-info ":run-code required" {})))
  (when-not tx-code          (throw (ex-info ":tx-code required" {})))
  (let [pp-step
        (fn [db _ctx]
          (let [pp-eid (if (number? pay-period)
                         pay-period
                         (d/q '[:find ?e . :in $ ?c :where [?e :pay-period/code ?c]]
                              db pay-period))
                facts (->> (pp/compute-payroll compute-provider
                                               {:pay-period-eid pp-eid
                                                :entity-eid entity
                                                :employment-eids employments
                                                :variable-inputs variable-inputs})
                           (mapv check-facts))
                postings (pp/build-postings posting-builder facts
                                            {:accounts accounts
                                             :ledger ledger
                                             :fx-provider fx-provider})
                tx-input (cond-> {:tx-tempid "payroll-tx-1"
                                  :transaction
                                  (cond-> {:transaction/external-id tx-code
                                           :transaction/effective-date
                                           (or vt-from (java.util.Date.))
                                           :transaction/narration
                                           (str "Payroll run " run-code)
                                           :transaction/state :draft}
                                    journal (assoc :transaction/journal journal))
                                  :postings postings})
                tx-frag (posting/build-transaction tx-input)
                ;; P0-86-1 fix — give every emit-doc a tempid so the
                ;; payroll-run row can reference them via
                ;; :payroll-run/emit-docs. The substrate guarantees:
                ;; if the emit-provider produced N audit-docs for this
                ;; pay-period, all N are reachable from the run row.
                ;; Providers that pre-assign :db/id keep it; those that
                ;; don't get "payroll-emit-<i>" assigned here.
                emit-docs-raw (pp/emit-payroll-events emit-provider facts
                                                      {:pay-period-eid pp-eid
                                                       :entity-eid entity})
                emit-docs (mapv (fn [i doc]
                                  (if (:db/id doc)
                                    doc
                                    (assoc doc :db/id (str "payroll-emit-" i))))
                                (range)
                                emit-docs-raw)
                emit-tempids (mapv :db/id emit-docs)
                run-frag (create-payroll-run-tx-data
                          db {:code run-code
                              :pay-period pp-eid
                              :provider-id (pp/provider-id compute-provider)
                              :facts facts
                              :tempid "payroll-run-1"})
                run-frag (if (seq emit-tempids)
                           ;; The single-row map produced by
                           ;; create-payroll-run-tx-data — augment with
                           ;; :payroll-run/emit-docs (cardinality/many).
                           (mapv (fn [row]
                                   (if (and (map? row)
                                            (= "payroll-run-1" (:db/id row)))
                                     (assoc row :payroll-run/emit-docs emit-tempids)
                                     row))
                                 run-frag)
                           run-frag)]
            {:tx-data (vec (concat tx-frag
                                   emit-docs
                                   run-frag
                                   ;; link the run to the transaction
                                   [{:db/id "payroll-run-1"
                                     :payroll-run/payroll-transaction "payroll-tx-1"}]))
             :ctx {:facts facts :run-tempid "payroll-run-1"
                   :emit-tempids emit-tempids}}))]
    (process/run-process
     conn (cond-> {:steps [pp-step]}
            vt-from (assoc :vt-from vt-from)
            vt-to   (assoc :vt-to vt-to)))))
