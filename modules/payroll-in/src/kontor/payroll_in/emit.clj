(ns kontor.payroll-in.emit
  "IN payroll emit-provider. Two responsibilities:

   1. `InPayrollEmitProvider` — `PayrollEmitProvider` impl. Returns an
      `:audit-doc` row per payroll-run with the right category /
      language slot, plus a separate PT-summary audit-doc when
      employees are spread across multiple PT states.
   2. `terminate-employment-tx-data` — ADR-068 builder that produces
      a `:termination-event` audit-doc carrying the data the
      consumer's engine needs for Form 16 issuance + final
      settlement (gratuity computation, leave encashment payment,
      Form 12B for new employer per Section 192(2)).

   ## What this namespace does NOT do

   - Emit Form 24Q (lives in `tds.clj`) — quarterly, not per-run.
   - Emit ECR (lives in `pf.clj`) — monthly, not per-run.
   - Emit ESIC contributions (lives in `esi.clj`) — monthly.
   - Submit anything to NSDL / EPFO / ESIC portals (consumer holds
     credentials; uploads outside kontor).
   - Generate Form 16 (Sec 192 annual TDS certificate) — covered by
     a separate `form-16.clj` follow-up; out of scope for v1 per the
.3 scope-discipline section.

   ## QC-equivalent passthrough warning (per-state PT detection)

   When a payroll run spans multiple PT-levying states, log an
   informational warning that the consumer's PT-remittance workflow
   needs to file separately per-state (each state has its own PT
   portal + cadence). Mirrors CA's QC passthrough warning
."
  (:require [clojure.string :as str]
            [kontor.payroll-in.wage-types :as wt]
            [kontor.provider.payroll-provider :as pp]))

;; ============================================================================
;; PT (Professional Tax) per-state detection + warning
;; ============================================================================

(defn pt-states-in-facts
  "Return the set of ISO-3166-2 state codes that appear in the run's
   facts AND are in the PT-levying set (per `wt/pt-states`).

   Reads `:jurisdiction-specific-codes :province-of-employment` from
   each fact; defensive against missing values."
  [facts]
  (->> facts
       (keep (fn [{:keys [jurisdiction-specific-codes]}]
               (:province-of-employment jurisdiction-specific-codes)))
       (filter wt/pt-state?)
       set))

(defn warn-if-multi-state-pt!
  "If the run touches multiple PT-levying states, log a stderr warning.
   Returns the PT-state set (possibly empty / singleton). Mirrors
   `kontor.payroll-ca.emit/warn-if-qc-detected!`
   structural alignment."
  [facts]
  (let [pt-states (pt-states-in-facts facts)]
    (when (> (count pt-states) 1)
      (binding [*out* *err*]
        (println
         (format
          (str "[kontor.payroll-in.emit] WARN: Payroll run spans %d "
               "PT-levying states (%s). Professional Tax filing is "
               "PER-STATE — each state has its own portal + cadence + "
               "rate slab table. The consumer's PT remittance workflow "
               "must split per state; kontor's PT-payable posting "
               "carries an :analytic-distribution on the 'in-state' "
               "plan that the remittance helper reads.")
          (count pt-states)
          (str/join ", " (sort pt-states))))))
    pt-states))

;; ============================================================================
;; InPayrollEmitProvider — PayrollEmitProvider impl
;; ============================================================================

(defrecord InPayrollEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    ;; Per.3:
    ;;   - No per-run TDS / PF / ESI emission (those are quarterly /
    ;;     monthly aggregate filings produced by tds.clj / pf.clj /
    ;;     esi.clj — NOT per pay-period).
    ;;   - kontor emits one :audit-doc summarizing the run + a
    ;;     warning audit-doc when the run spans multiple PT states
    ;;     (the consumer's PT-filing workflow needs to split).
    (let [language (or (:language opts) :en-in)
          per-fact-count (count payroll-facts)
          pt-states (warn-if-multi-state-pt! payroll-facts)
          base-doc
          {:kontor.audit-doc/code (str "IN-PAYROLL-EVENT-" entity-eid "-" pay-period-eid)
           :kontor.audit-doc/type :payroll-run-summary
           :kontor.audit-doc/title (format "IN payroll run (%d facts) for pay-period %d, entity %d"
                                    per-fact-count pay-period-eid entity-eid)
           :kontor.audit-doc/description
           (format "Payroll-run audit-doc. PT states touched: %s. TDS / PF / ESI quarterly / monthly emissions are produced by tds.clj / pf.clj / esi.clj — NOT in this per-run emission."
                   (if (seq pt-states) (str/join ", " (sort pt-states)) "none"))
           :kontor.audit-doc/category :payroll-filing
           :kontor.audit-doc/language language
           :kontor.audit-doc/uploaded-at (java.util.Date.)}]
      (cond-> [base-doc]
        (> (count pt-states) 1)
        (conj {:kontor.audit-doc/code
               (str "IN-PT-MULTI-STATE-" entity-eid "-" pay-period-eid)
               :kontor.audit-doc/type :payroll-run-summary
               :kontor.audit-doc/title
               (format "Multi-state PT detection — pay-period %d, entity %d"
                       pay-period-eid entity-eid)
               :kontor.audit-doc/description
               (format "This run spans %d PT-levying states (%s). The consumer's PT-remittance workflow needs to file PER STATE."
                       (count pt-states) (str/join ", " (sort pt-states)))
               :kontor.audit-doc/category :payroll-filing
               :kontor.audit-doc/language language
               :kontor.audit-doc/uploaded-at (java.util.Date.)})))))

;; ============================================================================
;; terminate-employment-tx-data — Form 12B + final settlement helper
;; ============================================================================

(def termination-reason-codes
  "Mapping kontor termination-reason keyword → IN ESI 'reason code'
   convention (when applicable for ESIC last-working-day reporting)
   + a free-form note. Open-set; consumer extends."
  {:resignation       {:esic-code "1" :note "Employee resigned"}
   :dismissal         {:esic-code "2" :note "Dismissal — disciplinary"}
   :retrenchment      {:esic-code "3" :note "Retrenchment under Industrial Disputes Act"}
   :death             {:esic-code "4" :note "Death in service"}
   :retirement        {:esic-code "5" :note "Superannuation / retirement"}
   :end-of-contract   {:esic-code "6" :note "Fixed-term contract expiry"}
   :other             {:esic-code "9" :note "Other"}})

(defn terminate-employment-tx-data
  "Pure ADR-068 tx-data builder for an employment termination event.

   Per.3 C9 plan:
     - status-machine transitions :kontor.employment/state → :terminated
     - sets :kontor.employment/end-date to last-day-worked
     - sets :kontor.employment/termination-reason
     - emits a :termination-event :audit-doc carrying the data the
       engine + consumer need for:
         * Form 16 (annual TDS certificate, Sec 192) — issued by
           15-June following the FY-end
         * Form 12B (prior-employer income certificate) — given to
           the employee to submit to the new employer
         * Gratuity payment (Sec 4 of Payment of Gratuity Act 1972 —
           15 days per year of completed service, capped at ₹20 lakhs)
         * Leave encashment payment (Sec 10(10AA) tax-exempt portion)
         * PF final-settlement / transfer-out (Form 19 / Form 10C /
           Form 10D) via EPFO
         * ESIC last-working-day update (so the ESI portal stops
           collecting contributions)
     - does NOT generate Form 16 (consumer's engine does, via
       TRACES download — kontor records the issuance)

   Required opts:
     :employment-eid       eid of the :employment to terminate
     :last-day-worked      java.util.Date — the actual last day worked
     :termination-reason   keyword — one of `termination-reason-codes`
                            keys OR an open-set consumer extension

   Optional:
     :final-pay-period-end-date java.util.Date — drives the
                                final-payroll-run linkage
     :years-of-service          integer — drives the gratuity
                                computation (consumer-supplied; we don't
                                read :kontor.employment/start-date here because
                                the engine handles the cap + the
                                continuous-service rule + the
                                payable-amount under Sec 4(2))
     :gratuity-payable          Money — the engine's computed gratuity
                                amount (consumer's actuary +
                                year-end accrual; substrate just records)
     :leave-encashment-payable  Money — the engine's computed
                                leave-encashment amount
     :final-tds-collected       Money — total TDS withheld in the
                                final pay-period (Form 16 Box 6)
     :form-16-issuance-date     Date — when the consumer / engine
                                will issue Form 16
     :code                      consumer-supplied audit-doc code
                                (default generated from employment +
                                last-day-worked)
     :language                  :en-in (default), :hi-in, :ta-in, etc."
  [_db {:keys [employment-eid last-day-worked termination-reason
               final-pay-period-end-date years-of-service
               gratuity-payable leave-encashment-payable
               final-tds-collected form-16-issuance-date code language]
        :or {language :en-in}}]
  (when-not employment-eid    (throw (ex-info ":employment-eid required" {})))
  (when-not last-day-worked   (throw (ex-info ":last-day-worked required" {})))
  (when-not termination-reason (throw (ex-info ":termination-reason required" {})))
  (let [reason-meta (or (get termination-reason-codes termination-reason)
                        {:esic-code "9" :note "Consumer-extended reason"})
        doc-code (or code
                     (format "TERMINATION-IN-%s-%d"
                             (str employment-eid)
                             (.getTime ^java.util.Date last-day-worked)))
        desc (format
              (str "Termination of employment %s on %s; reason %s "
                   "(ESIC code %s). Years of service: %s. "
                   "Gratuity payable: %s | Leave encashment: %s | "
                   "Final TDS collected: %s | Form 16 due by %s. "
                   "Form 16 + Form 12B issuance handled by consumer's "
                   "engine; PF Form 19 / 10C / 10D via EPFO portal; "
                   "ESIC last-working-day update via ESIC portal.")
              (str employment-eid)
              (str last-day-worked)
              (name termination-reason)
              (:esic-code reason-meta)
              (str (or years-of-service "n/a"))
              (or (:amount gratuity-payable) "n/a")
              (or (:amount leave-encashment-payable) "n/a")
              (or (:amount final-tds-collected) "n/a")
              (str (or form-16-issuance-date "(consumer-supplied)")))
        doc-tempid (str "in-termination-event-doc-" employment-eid)
        audit-doc {:db/id doc-tempid
                   :kontor.audit-doc/code doc-code
                   :kontor.audit-doc/type :termination-event
                   :kontor.audit-doc/title (str "Termination — " (name termination-reason)
                                         " (ESIC " (:esic-code reason-meta) ")")
                   :kontor.audit-doc/description desc
                   :kontor.audit-doc/uploaded-at (java.util.Date.)
                   :kontor.audit-doc/category :hr-personnel
                   :kontor.audit-doc/language language}
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
;; build-form-24q-audit-doc-tx-data — companion of tds.clj
;; ============================================================================

(defn build-form-24q-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording a Form 24Q FVU
   submission was generated. The consumer transacts this alongside
   the actual portal upload.

   Required:
     :tan
     :fy :quarter
     :record-count   integer (from the FVU file-header)
     :language       :en-in (default)

   Optional:
     :statement-type 'O' (default) | 'C' | 'X'
     :file-uri       where the consumer stored the FVU text
     :rrr-number     the Receipt Reference Number (Provisional
                     Receipt Number) after upload — typically set
                     in a SECOND audit-doc tx after the upload
                     completes (Form 24Q upload is asynchronous)
     :code           consumer-supplied; defaults from TAN + FY + Q"
  [{:keys [tan fy quarter record-count language statement-type
           file-uri rrr-number code]
    :or {language :en-in
         statement-type "O"}}]
  (when-not tan      (throw (ex-info ":tan required" {})))
  (when-not fy       (throw (ex-info ":fy required" {})))
  (when-not quarter  (throw (ex-info ":quarter required" {})))
  (let [doc-code (or code
                     (format "FORM-24Q-%s-%d-Q%d-%s"
                             tan fy quarter statement-type))
        title (format "Form 24Q FVU — TAN %s, FY %d-%02d, Q%d (%s, %d records)"
                      tan fy (mod (inc fy) 100) quarter statement-type
                      (or record-count 0))]
    [(cond->
      {:kontor.audit-doc/code doc-code
       :kontor.audit-doc/type :regulator-clearance
       :kontor.audit-doc/title title
       :kontor.audit-doc/uploaded-at (java.util.Date.)
       :kontor.audit-doc/category :payroll-filing
       :kontor.audit-doc/language language}
       file-uri (assoc :kontor.audit-doc/storage-uri file-uri)
       rrr-number (assoc :kontor.audit-doc/description
                         (str "RRR: " rrr-number)))]))
