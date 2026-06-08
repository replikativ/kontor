(ns kontor.payroll-ca.emit
  "CA payroll emit-provider. Two responsibilities:

   1. `CaPayrollEmitProvider` — `PayrollEmitProvider` impl. Returns
      `:audit-doc` rows for each payroll run with the right
      category/language slots. kontor does NOT emit PD7A
      (CRA-to-employer correspondence) —
      kontor does NOT emit ROE (Service Canada, via the engine) — see
.

   2. `terminate-employment-tx-data` — ADR-068 builder that produces
      the `:termination-event` `:audit-doc` carrying the data the
      engine needs (insurable earnings rolling window, separation
      payments, Block 16 reason). Following.3.

   3. `build-t4-audit-doc-tx-data` — companion of
      `kontor.payroll-ca.t4-builder/build-t4-return-submission`,
      records what was emitted with the right `:kontor.audit-doc/language`
      slot.

   4. QC warning helper — when a payroll run includes a QC employee,
      log a warning that RL-1 emission deferred to C4.1.

   Reference:, §8, §9."
  (:require [kontor.provider.payroll-provider :as pp]))

;; ============================================================================
;; QC passthrough warning
;; ============================================================================

(defn qc-employees-in-facts
  "Return the set of employment eids whose pay-period facts include
   QC-only component-kinds (
   when the employee is QC; absence => no QC). Useful for the
   passthrough warning."
  [facts]
  (->> facts
       (filter (fn [{:keys [components jurisdiction-specific-codes]}]
                 (or (some #{:employee-qpp :employee-qpp2 :employee-qpip
                             :employee-qc-itx}
                           (map :kind components))
                     (some? (:qpip-insurable-earnings
                             jurisdiction-specific-codes)))))
       (mapv :employment)
       set))

(defn warn-if-qc-detected!
  "If any fact carries QC component-kinds AND no QC emitter is
   installed, log a warning3. Returns the set of QC
   employments detected (possibly empty).

   Per ADR-087 (C4.1 RL-1 emission ships), the warning is suppressed
   when `:qc-emit-installed?` is truthy — the consumer is expected to
   wire `kontor.payroll-ca.qc-emit/QcPayrollEmitProvider` alongside the
   federal `CaPayrollEmitProvider` for QC employees, and the year-end
   RL-1 submission is built via
   `kontor.payroll-ca.qc-emit/build-rl1-submission!`."
  ([facts] (warn-if-qc-detected! facts {:qc-emit-installed? false}))
  ([facts {:keys [qc-emit-installed?]}]
   (let [qc (qc-employees-in-facts facts)]
     (when (and (seq qc) (not qc-emit-installed?))
       (binding [*out* *err*]
         (println
          (format
           (str "[kontor.payroll-ca.emit] WARN: QC employments detected "
                "(%s) but no QC emitter installed. Wire "
                "kontor.payroll-ca.qc-emit/QcPayrollEmitProvider for the "
                "RL-1 + TPZ-1015 audit-chain coverage (ADR-087). T4 "
                "boxes 17/17A/55/56 populate independently via the "
                "federal emitter.")
           qc))))
     qc)))

;; ============================================================================
;; CaPayrollEmitProvider — PayrollEmitProvider impl
;; ============================================================================

(defrecord CaPayrollEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    ;; Per.4: no PD7A emission (no employer-filed form).
    ;; Per(Service Canada via engine).
    ;; What we emit per pay-period is an audit-doc summary so the
    ;; audit chain has a row for the run + QC warning if applicable.
    ;; Per ADR-087, when :qc-emit-installed? is set in opts the warning
    ;; is suppressed (the QcPayrollEmitProvider handles the QC side).
    (warn-if-qc-detected! payroll-facts
                          {:qc-emit-installed?
                           (boolean (:qc-emit-installed? opts))})
    (let [language (or (:language opts) :en)
          per-fact-count (count payroll-facts)]
      ;; :kontor.audit-doc/category :payroll-filing
      ;; (canonical vocabulary; this is the periodic payroll-engine
      ;; summary audit-doc and aligns with the DE LODAS Importdatei +
      ;; CA PD7A audit-doc both classed :payroll-filing).
      [{:kontor.audit-doc/code (str "PAYROLL-EVENT-" entity-eid "-" pay-period-eid)
        :kontor.audit-doc/type :payroll-run-summary
        :kontor.audit-doc/title (format "Payroll run (%d facts) for pay-period %d, entity %d"
                                 per-fact-count pay-period-eid entity-eid)
        :kontor.audit-doc/category :payroll-filing
        :kontor.audit-doc/language language
        :kontor.audit-doc/uploaded-at (java.util.Date.)}])))

;; ============================================================================
;; terminate-employment-tx-data — ROE-data emit3
;; ============================================================================

(def termination-reason-codes
  "Mapping kontor termination-reason keyword → ROE Block 16 letter
2. Open-set on the kontor side; the engine maps
   to the actual ROE."
  {:shortage-of-work "A"
   :strike-or-lockout "B"
   :return-to-school  "C"
   :illness           "D"
   :quit              "E"
   :pregnancy         "F"
   :paternity         "F"
   :parental-leave    "G"
   :work-sharing      "H"
   :apprentice        "J"
   :other             "K"
   :compassionate-care "L"
   :dismissal         "M"
   :leave-of-absence  "N"
   :retirement        "P"
   :death             "Q"})

(defn terminate-employment-tx-data
  "Pure ADR-068 tx-data builder for an employment termination event.
   Per.3, kontor:

   - status-machine transitions :kontor.employment/state → :terminated
   - sets :kontor.employment/end-date to last-day-worked
   - sets :kontor.employment/termination-reason (open-set keyword)
   - emits a :termination-event :audit-doc carrying the data the ROE
     engine needs (Block 15 insurable-earnings rolling window,
     Block 16 reason, Block 17 separation payments)
   - does NOT generate a ROE (.BLK XML); the consumer's engine
     submits the ROE to Service Canada via ROE Web.

   Required opts:
     :employment-eid       eid of the :employment to terminate
     :last-day-worked      java.util.Date — the actual last day worked
                           (Block 11 + drives ROE deadline)
     :termination-reason   keyword — one of `termination-reason-codes`
                           keys, OR an open-set extension for jurisdictions
                           where the consumer needs a custom value

   Optional:
     :final-pay-period-end-date java.util.Date — Block 12 final pay
                           period end
     :rolling-window {:insurable-earnings [{:pay-period :amount} …]
                      :insurable-hours [{:pay-period :amount} …]}
                           — Block 15A/15C data the engine needs
     :separation-payments {:severance Money :vacation-paid-out Money
                           :retiring-allowance Money :other Money}
                           — Block 17 lines
     :code                consumer-supplied audit-doc code; defaults
                          generated from employment + last-day-worked
     :language            :en (default) | :fr"
  [_db {:keys [employment-eid last-day-worked termination-reason
               final-pay-period-end-date rolling-window
               separation-payments code language]
        :or {language :en}}]
  (when-not employment-eid    (throw (ex-info ":employment-eid required" {})))
  (when-not last-day-worked   (throw (ex-info ":last-day-worked required" {})))
  (when-not termination-reason (throw (ex-info ":termination-reason required" {})))
  (let [roe-block-16 (get termination-reason-codes termination-reason "K")
        doc-code (or code
                     (format "TERMINATION-%s-%d"
                             (str employment-eid)
                             (.getTime ^java.util.Date last-day-worked)))
        desc (format
              (str "Termination of employment %s on %s; reason %s "
                   "(ROE Block 16: %s). Insurable-earnings rolling "
                   "window: %d periods; separation payments: %s. "
                   "ROE NOT generated here — consumer's engine "
                   "submits via ROE Web.")
              (str employment-eid)
              (str last-day-worked)
              (name termination-reason)
              roe-block-16
              (count (:insurable-earnings rolling-window))
              (or (some-> separation-payments keys vec)
                  "[]"))
        doc-tempid (str "termination-event-doc-" employment-eid)
        ;; Carry the structured Block 15 data on the audit-doc via
        ;; :kontor.audit-doc/description — kontor doesn't have a typed slot
        ;; for it (3 the structured payload is for the
        ;; engine to consume; kontor's audit chain just needs to
        ;; record what was passed).
        audit-doc {:db/id doc-tempid
                   :kontor.audit-doc/code doc-code
                   :kontor.audit-doc/type :termination-event
                   :kontor.audit-doc/title (str "Termination — " (name termination-reason))
                   :kontor.audit-doc/description desc
                   :kontor.audit-doc/uploaded-at (java.util.Date.)
                   :kontor.audit-doc/category :hr-personnel
                   :kontor.audit-doc/language language}
        ;; Status-machine transition is a separate concern from this
        ;; ADR-068 builder; the consumer composes via
        ;; kontor.workflow.process/run-process and includes both the
        ;; :employment update and the audit-doc in one transaction.
        emp-update (cond->
                    {:db/id employment-eid
                     :kontor.employment/state :terminated
                     :kontor.employment/end-date last-day-worked
                     :kontor.employment/termination-reason termination-reason}
                     final-pay-period-end-date
                     (assoc :kontor.employment/final-pay-period-end-date
                            final-pay-period-end-date))]
    [audit-doc emp-update]))

;; ============================================================================
;; build-t4-audit-doc-tx-data — companion of t4-builder/build-t4-return-submission
;; ============================================================================

(defn build-t4-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording a T4 IFT submission
   was generated. The consumer transacts this alongside the actual
   IFT upload (which happens outside kontor — consumer's engine /
   ops uploads the XML to CRA).

   Required:
     :rp-bn15
     :tax-year
     :slip-count    integer
     :language      :en | :fr

   Optional:
     :report-type   :original (default) | :amended
     :ift-uri       where the consumer stored the XML
     :code          consumer-supplied; defaults from RP + year"
  [{:keys [rp-bn15 tax-year slip-count language report-type
           ift-uri code]
    :or {language :en
         report-type :original}}]
  (when-not rp-bn15 (throw (ex-info ":rp-bn15 required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (when-not slip-count (throw (ex-info ":slip-count required" {})))
  (let [doc-code (or code
                     (format "T4-%s-%d-%s" rp-bn15 tax-year
                             (name (or report-type :original))))
        title (format "T4 + T4 Summary IFT submission — RP %s tax-year %d (%d slips, %s)"
                      rp-bn15 tax-year slip-count
                      (case language :fr "FR" "EN"))]
    [(cond->
      {:kontor.audit-doc/code doc-code
       :kontor.audit-doc/type :regulator-clearance
       :kontor.audit-doc/title title
       :kontor.audit-doc/uploaded-at (java.util.Date.)
       :kontor.audit-doc/category :payroll-filing
       :kontor.audit-doc/language language}
       ift-uri (assoc :kontor.audit-doc/storage-uri ift-uri))]))
