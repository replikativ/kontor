(ns kontor.hr.payroll
  "kontor-hr payroll orchestrator. Composes the three PayrollProvider
   protocols (kontor.provider.payroll-provider) into one atomic
   `kontor.workflow.process/run-process`-driven transaction:

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
   :kontor.compensation-component/amount and net must equal gross minus Σ
   absolute-value negative deductions. The check throws ex-info on
   first mismatch (no silent passing).

   Per ADR-005 / ADR-071 / ADR-075: the substrate ships the
   orchestration shape; it does NOT bundle per-vendor rate tables,
   per-jurisdiction CoA, or vendor API credentials."
  (:require [datahike.api :as d]
            [kontor.actor.ref :as actor-ref]
            [kontor.gate :as gate]
            [kontor.provider.payroll-provider :as pp]
            [kontor.posting :as posting]
            [kontor.workflow.process :as process]
            [kontor.workflow.status-machine :as sm])
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
  "Pure tx-data builder for the :payroll-run row.

   `:actor` — whoever ran the payroll — is REQUIRED (ADR-153). Until then
   this builder had no actor slot AT ALL, so no payroll run in kontor ever
   carried `:kontor.audit/create-uid`, while
   `kontor.hr.schema/approval-policy-seeds` puts `:no-self-approval` on the
   `:computed → :approved` edge and its own docstring calls that \"the
   load-bearing edge\". Once ADR-150 made the rule fail CLOSED on a nil
   creator, every payroll approval in kontor-hr became unreachable. The
   suite stayed green because nothing anywhere transitioned a run to
   `:approved` — see [[approve-run-tx-data]], which is the other half of
   this fix.

   The value goes through `kontor.actor/->ref`: pure, cljs-safe, and
   fail-closed by construction — `:kontor.actor/uid` is
   `:db.unique/identity`, so an unregistered actor makes datahike refuse
   the write rather than mint a phantom the SoD comparison could never
   match."
  [_db {:keys [code pay-period provider-id facts actor tempid]
        :or {tempid "payroll-run-1"}}]
  (when-not code        (throw (ex-info ":code required" {})))
  (when-not pay-period  (throw (ex-info ":pay-period required" {})))
  (when-not provider-id (throw (ex-info ":provider-id required" {})))
  (when-not actor
    (throw (ex-info
            (str ":actor required (ADR-153) — the actor that ran the payroll is "
                 "stamped as :kontor.audit/create-uid, which the seeded "
                 ":no-self-approval policy on the :computed → :approved edge "
                 "compares the approving actor against. Without it this run can "
                 "never be approved, and therefore never posted.")
            {:type :kontor.payroll-run/actor-required :code code})))
  (let [gross-total (->> facts (map :gross) (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))
        net-total   (->> facts (map :net)   (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M))]
    [{:db/id tempid
      :kontor.payroll-run/code code
      :kontor.payroll-run/pay-period pay-period
      :kontor.payroll-run/provider-id provider-id
      :kontor.payroll-run/state :computed
      :kontor.payroll-run/control-total-gross gross-total
      :kontor.payroll-run/control-total-net net-total
      :kontor.audit/create-uid (actor-ref/->ref actor)}]))

;; ============================================================================
;; approve-run! — the edge the policy was seeded for
;; ============================================================================

(defn approve-run-tx-data
  "Pure tx-data driving a `:payroll-run` `:computed → :approved` through
   the ADR-034 status machine, so the ADR-038 `:no-self-approval` policy
   `kontor.hr.schema` seeds on that edge actually fires (ADR-153).

   This function did not exist before ADR-153, and its absence is why the
   ADR-150 regression shipped green: the seeded policy was unreachable
   dead data, so no test could observe that it refused every approval.
   `run-payroll!` also wrote `:kontor.payroll-run/state :computed` as a
   plain attribute rather than through the status machine, which is why
   `:from` is read from the entity here rather than assumed.

   Required opts: `:run` (eid), `:actor` (the APPROVING actor — must
   differ from the one that ran it, which is the whole point).
   Optional: `:reason` / `:reason-note` / `:supporting-doc` /
   `:changed-at`."
  [db {:keys [run actor reason reason-note supporting-doc changed-at]}]
  (when-not run   (throw (ex-info ":run required" {})))
  (when-not actor (throw (ex-info ":actor required — an approval nobody signed is not an approval" {})))
  (sm/record-status-change-tx-data
   db
   (cond-> {:entity run
            :entity-type :payroll-run
            :facet :kontor.payroll-run/state
            :to :approved
            :changed-by-uid (actor-ref/->ref actor)
            :changed-at (or changed-at (java.util.Date.))
            :reason (or reason :payroll-approved)}
     reason-note    (assoc :reason-note reason-note)
     supporting-doc (assoc :supporting-doc supporting-doc))))

(defn approve-run!
  "Approve a computed payroll run. See [[approve-run-tx-data]]."
  [conn opts]
  (gate/transact-with-validation
   conn (approve-run-tx-data (d/db conn) opts)))

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
     - composes via kontor.workflow.process/run-process so the kernel gate
       stack fires once.

   Required opts:
     :pay-period      — eid or :kontor.pay-period/code
     :entity          — eid of :entity (ADR-031)
     :employments     — vector of :employment eids to include
     :compute-provider — satisfies PayrollComputeProvider
     :posting-builder  — satisfies PayrollPostingBuilder
     :accounts         — map keyed by component-kind → :account ref
                         (consumer-supplied CoA)
     :run-code         — string for :kontor.payroll-run/code
     :tx-code          — string for the :kontor.transaction/code
     :actor            — the actor running the payroll (ADR-153).
                         Stamped as :kontor.audit/create-uid on the
                         :payroll-run and threaded onto the GL entry, so
                         the seeded :no-self-approval policy on
                         :computed → :approved has something to compare a
                         later approver against. See `approve-run!`.

   Optional opts:
     :emit-provider     — satisfies PayrollEmitProvider
                          (default: LocalfileEmitProvider — no emit)
     :variable-inputs   — map employment-eid → engine-specific overrides
                          (opaque per ADR-075; each adapter's expected
                          shape is in its own docstring + the protocol
                          docstring lists each shipped adapter's keys)
     :ledger            — target :ledger (ADR-021); default kernel
                          main. For single-ledger consumers.
     :ledgers-map       — map ledger-keyword → :ledger eid for the
                          parallel-ledger / book-vs-tax split per
                          ADR-021. Threaded to build-postings.
                          Required by US ASC 710 PTO + 401(k)
                          match accruals to land on :us-gaap book
                          ledger only (the IRC §404(a)(6) timing
                          difference); the DE Urlaubsrückstellung also
                          uses it for HGB-vs-Steuerbilanz routing.
                          When both :ledger and :ledgers-map are set,
                          the posting-builder honors :ledgers-map.
     :state-allocations — map employment-eid → vec of {:state STR,
                          :fraction BIGDEC} (typical case: 60% NY /
                          40% NJ for a single employee). Threaded to
                          build-postings; the US adapter consumes it
                          to route per-state analytic-distributions
                          per ADR-077..
     :fx-provider       — FxRateProvider (ADR-072) for currency
                          translation when employee currency ≠ entity
                          functional currency
     :journal           — ref to :journal for the GL entry
     :commodity         — fallback :commodity for the :transaction
     :vt-from / :vt-to  — bitemporal bounds applied to the run

   Returns the tx-report from transact-with-validation."
  [conn {:keys [pay-period entity employments compute-provider
                posting-builder emit-provider accounts run-code tx-code
                variable-inputs ledger ledgers-map state-allocations
                fx-provider journal commodity actor vt-from vt-to]
         :or {emit-provider (pp/->LocalfileEmitProvider {})}}]
  (when-not pay-period       (throw (ex-info ":pay-period required" {})))
  (when-not entity           (throw (ex-info ":entity required" {})))
  (when (empty? employments) (throw (ex-info ":employments must be non-empty" {})))
  (when-not compute-provider (throw (ex-info ":compute-provider required" {})))
  (when-not posting-builder  (throw (ex-info ":posting-builder required" {})))
  (when-not run-code         (throw (ex-info ":run-code required" {})))
  (when-not tx-code          (throw (ex-info ":tx-code required" {})))
  (when-not actor            (throw (ex-info ":actor required (ADR-153) — see create-payroll-run-tx-data" {})))
  (let [pp-step
        (fn [db _ctx]
          (let [pp-eid (if (number? pay-period)
                         pay-period
                         (d/q '[:find ?e . :in $ ?c :where [?e :kontor.pay-period/code ?c]]
                              db pay-period))
                facts (->> (pp/compute-payroll compute-provider
                                               {:pay-period-eid pp-eid
                                                :entity-eid entity
                                                :employment-eids employments
                                                :variable-inputs variable-inputs})
                           (mapv check-facts))
                postings (pp/build-postings
                          posting-builder facts
                          ;; note 197: thread the db so a posting builder that
                          ;; ships a default account-code map (e.g. DE SKR04)
                          ;; can resolve its codes → eids; without it the
                          ;; shipped default map was unreachable through the
                          ;; orchestrator and a consumer had to hand-build
                          ;; :accounts even when the module ships one.
                          (cond-> {:accounts accounts
                                   :db db
                                   :ledger ledger
                                   :fx-provider fx-provider}
                            ledgers-map        (assoc :ledgers-map ledgers-map)
                            state-allocations  (assoc :state-allocations
                                                      state-allocations)))
                tx-input (cond-> {:tx-tempid "payroll-tx-1"
                                  :transaction
                                  (cond-> {:kontor.transaction/external-id tx-code
                                           :kontor.transaction/effective-date
                                           (or vt-from (java.util.Date.))
                                           :kontor.transaction/narration
                                           (str "Payroll run " run-code)
                                           :kontor.transaction/state :draft
                                           ;; ADR-153 — the GL entry carries the
                                           ;; same attribution as the run.
                                           :kontor.audit/create-uid
                                           (actor-ref/->ref actor)}
                                    journal (assoc :kontor.transaction/journal journal))
                                  :postings postings})
                tx-frag (posting/build-transaction tx-input)
                ;; fix — give every emit-doc a tempid so the
                ;; payroll-run row can reference them via
                ;; :kontor.payroll-run/emit-docs. The substrate guarantees:
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
                              :actor actor
                              :tempid "payroll-run-1"})
                run-frag (if (seq emit-tempids)
                           ;; The single-row map produced by
                           ;; create-payroll-run-tx-data — augment with
                           ;; :kontor.payroll-run/emit-docs (cardinality/many).
                           (mapv (fn [row]
                                   (if (and (map? row)
                                            (= "payroll-run-1" (:db/id row)))
                                     (assoc row :kontor.payroll-run/emit-docs emit-tempids)
                                     row))
                                 run-frag)
                           run-frag)]
            {:tx-data (vec (concat tx-frag
                                   emit-docs
                                   run-frag
                                   ;; link the run to the transaction
                                   [{:db/id "payroll-run-1"
                                     :kontor.payroll-run/payroll-transaction "payroll-tx-1"}]))
             :ctx {:facts facts :run-tempid "payroll-run-1"
                   :emit-tempids emit-tempids}}))]
    (process/run-process
     conn (cond-> {:steps [pp-step]}
            vt-from (assoc :vt-from vt-from)
            vt-to   (assoc :vt-to vt-to)))))
