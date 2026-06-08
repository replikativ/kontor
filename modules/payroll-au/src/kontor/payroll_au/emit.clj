(ns kontor.payroll-au.emit
  "AU payroll emit-provider.

   Three responsibilities:

   1. `AuStpEmitProvider` — `PayrollEmitProvider` impl. Returns
      `:audit-doc` rows for the STP Phase 2 pay-event per pay-period.
      The actual SBR2 (ebMS3 / AS4) transmission to the ATO is the
      consumer's engine's job; kontor records the structured payload
      so the audit chain has a row and the consumer can route it to
      its SBR adapter / clearing-house.

   2. `build-stp-pay-event-audit-doc-tx-data` — ADR-068 builder for
      a single STP pay-event audit-doc with full payload metadata.

   3. `terminate-employment-tx-data` — termination audit-doc helper
      (mirrors CA's pattern). AU termination paperwork (Employment
      Separation Certificate when an employee claims Centrelink, plus
      the STP final-event flag) is engine-driven; kontor records the
      terminating event for the audit chain.

   Reference: ADR-080, ATO Software Developers BIG STP Phase 2."
  (:require [kontor.payroll-au.stp :as stp]
            [kontor.payroll-au.wage-types :as wt]
            [kontor.provider.payroll-provider :as pp])
  (:import [java.util Date]))

;; ============================================================================
;; build-stp-pay-event-audit-doc-tx-data — ADR-068 builder
;; ============================================================================

(defn build-stp-pay-event-audit-doc-tx-data
  "Build an `:audit-doc` tx-data fragment recording an STP Phase 2
   pay-event submission. The consumer transacts this alongside the
   actual SBR upload (which happens outside kontor).

   Required:
     :payload     result of `kontor.payroll-au.stp/pay-event`

   Optional:
     :code        consumer-supplied; defaults from ABN + pay-date
     :storage-uri where the consumer stored the serialized XML
     :language    :en (default) — AU is single-locale
     :update?     true when this is the audit-doc for an update event
     :final?      true when this is the final-event of the year"
  [{:keys [payload code storage-uri language update? final?]
    :or {language :en}}]
  (when-not payload (throw (ex-info ":payload required" {})))
  (let [doc-code (or code
                     (format "STP-%s-%s"
                             (:stp.event/abn payload)
                             (:stp.event/pay-date payload)))
        desc (stp/pay-event->summary-string payload)
        kind (cond
               (or update? (:stp.event/update-event? payload))
               :stp-update-event
               (or final? (:stp.event/final-event? payload))
               :stp-final-event
               :else
               :stp-pay-event)]
    [(cond->
      {:kontor.audit-doc/code doc-code
       :kontor.audit-doc/type kind
       :kontor.audit-doc/title (format "STP Phase 2 pay-event — ABN %s, paid %s"
                                (:stp.event/abn payload)
                                (:stp.event/pay-date payload))
       :kontor.audit-doc/description desc
       :kontor.audit-doc/uploaded-at (Date.)
       :kontor.audit-doc/category :payroll-filing
       :kontor.audit-doc/language language}
       storage-uri (assoc :kontor.audit-doc/storage-uri storage-uri))]))

;; ============================================================================
;; AuStpEmitProvider — PayrollEmitProvider protocol impl
;; ============================================================================

(defrecord AuStpEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts _ctx]
    ;; The substrate's contract is "round-trip the event payload as
    ;; an :audit-doc". For AU we emit ONE audit-doc per pay-event;
    ;; the SuperStream contribution-message is a separate audit-doc
    ;; the consumer transacts independently when super is remitted
    ;; (typically monthly or quarterly, not per pay-period).
    ;;
    ;; The substrate-provided ctx carries :pay-period-eid + :entity-eid
    ;; but the linkage to those entities flows via
    ;; :kontor.payroll-run/emit-docs (set by run-payroll!
    ;;), so the provider does not need to embed them directly
    ;; on the audit-doc.
    ;;
    ;; The opts carry the per-employer / per-event metadata kontor
    ;; can't see without inspecting :employment + :entity rows in the
    ;; substrate. Required opts:
    ;;   :abn                  — employer ABN
    ;;   :pay-period-start     — Date
    ;;   :pay-period-end       — Date
    ;;   :pay-date             — Date (when payment hits employees)
    ;;   :payees-info          — {employment-eid → payee info map}
    ;;
    ;; Optional opts:
    ;;   :extras-map           — wage-types catalog extension
    ;;   :submission-id        — consumer-deterministic ID
    ;;   :bms-id               — vendor-issued BMS identifier
    ;;   :branch-code          — defaults "001"
    ;;   :final-event?         — defaults false
    ;;   :language             — defaults :en
    (let [{:keys [abn pay-period-start pay-period-end pay-date
                  payees-info extras-map submission-id bms-id
                  branch-code final-event? language]
           :or {branch-code "001"
                final-event? false
                language :en}} opts]
      (when-not abn (throw (ex-info "AuStpEmitProvider needs :abn in opts" {})))
      (when-not pay-period-start
        (throw (ex-info "AuStpEmitProvider needs :pay-period-start in opts" {})))
      (when-not pay-period-end
        (throw (ex-info "AuStpEmitProvider needs :pay-period-end in opts" {})))
      (when-not pay-date
        (throw (ex-info "AuStpEmitProvider needs :pay-date in opts" {})))
      (when-not payees-info
        (throw (ex-info "AuStpEmitProvider needs :payees-info in opts" {})))
      (let [income-type-fn (fn [k] (wt/stp2-income-type k extras-map))
            payees (stp/facts->payees {:facts payroll-facts
                                       :payees-info payees-info
                                       :income-type-fn income-type-fn})
            event (stp/pay-event
                   {:abn abn
                    :pay-period-start pay-period-start
                    :pay-period-end pay-period-end
                    :submission-date (Date.)
                    :pay-date pay-date
                    :payees payees
                    :bms-id bms-id
                    :submission-id submission-id
                    :branch-code branch-code
                    :final-event? final-event?})
            audit-tx (build-stp-pay-event-audit-doc-tx-data
                      {:payload event
                       :language language
                       :final? final-event?})]
        audit-tx))))

;; ============================================================================
;; terminate-employment-tx-data — ADR-068 termination helper
;; ============================================================================

(def termination-reason-codes
  "Mapping kontor termination-reason keyword → an STP Cessation Type
   code per the ATO Phase 2 BIG (`CessationTypeCode`):
     V  Voluntary cessation
     I  Ill health
     D  Deceased
     R  Redundancy / approved early retirement scheme
     F  Dismissal / End of fixed-term contract
     C  Contract / on hire termination
     T  Transfer (within same ABN)
     O  Other"
  {:voluntary-cessation "V"
   :quit                "V"
   :resignation         "V"
   :ill-health          "I"
   :deceased            "D"
   :death               "D"
   :redundancy          "R"
   :retirement          "R"
   :dismissal           "F"
   :end-of-contract     "F"
   :contract-end        "C"
   :transfer-within-abn "T"
   :other               "O"})

(defn terminate-employment-tx-data
  "Pure ADR-068 tx-data builder for an employment-termination event.
   Mirrors CA's `terminate-employment-tx-data` shape.

   - status-machine transitions `:kontor.employment/state` → `:terminated`
   - sets `:kontor.employment/end-date` to last-day-worked
   - sets `:kontor.employment/termination-reason`
   - emits a `:termination-event` audit-doc carrying the data the
     engine needs to surface to the ATO via the next STP pay-event's
     cessation block + (optionally) to print an Employment
     Separation Certificate for Centrelink.

   Required opts:
     :employment-eid       eid of the :employment to terminate
     :last-day-worked      java.util.Date — Block 11 equivalent
     :termination-reason   keyword — one of `termination-reason-codes`
                           keys OR a consumer-defined extension

   Optional:
     :final-pay-period-end-date java.util.Date — last paid period end
     :rolling-window {:earnings-stp2 [...] :hours-stp2 [...]}
     :separation-payments {:unused-leave Money :etp-type-r Money
                           :etp-type-o Money}
     :code                consumer-supplied audit-doc/code
     :language            :en (default)"
  [_db {:keys [employment-eid last-day-worked termination-reason
               final-pay-period-end-date rolling-window
               separation-payments code language]
        :or {language :en}}]
  (when-not employment-eid    (throw (ex-info ":employment-eid required" {})))
  (when-not last-day-worked   (throw (ex-info ":last-day-worked required" {})))
  (when-not termination-reason (throw (ex-info ":termination-reason required" {})))
  (let [stp-code (get termination-reason-codes termination-reason "O")
        doc-code (or code
                     (format "TERMINATION-%s-%d"
                             (str employment-eid)
                             (.getTime ^Date last-day-worked)))
        desc (format
              (str "Termination of employment %s on %s; reason %s "
                   "(STP CessationTypeCode: %s). Separation payments: %s. "
                   "ATO STP final-pay-event reports the cessation; this "
                   "audit-doc records the engine's input.")
              (str employment-eid)
              (str last-day-worked)
              (name termination-reason)
              stp-code
              (or (some-> separation-payments keys vec)
                  "[]"))
        doc-tempid (str "termination-event-doc-" employment-eid)
        audit-doc (cond->
                   {:db/id doc-tempid
                    :kontor.audit-doc/code doc-code
                    :kontor.audit-doc/type :termination-event
                    :kontor.audit-doc/title (str "Termination — " (name termination-reason))
                    :kontor.audit-doc/description desc
                    :kontor.audit-doc/uploaded-at (Date.)
                    :kontor.audit-doc/category :hr-personnel
                    :kontor.audit-doc/language language}
                    rolling-window
                    (identity)) ; placeholder for future structured fields
        emp-update (cond->
                    {:db/id employment-eid
                     :kontor.employment/state :terminated
                     :kontor.employment/end-date last-day-worked
                     :kontor.employment/termination-reason termination-reason}
                     final-pay-period-end-date
                     (assoc :kontor.employment/final-pay-period-end-date
                            final-pay-period-end-date))]
    [audit-doc emp-update]))
