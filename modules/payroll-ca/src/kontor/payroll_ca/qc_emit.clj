(ns kontor.payroll-ca.qc-emit
  "QC payroll emit-provider — extends `kontor.payroll-ca.emit` for
   Revenu Québec.

   Two responsibilities (ADR-087 §1 + §2):

   1. **`QcPayrollEmitProvider`** — `PayrollEmitProvider` impl that
      complements `CaPayrollEmitProvider`. Emits per-pay-period
      `:kontor.audit-doc/category :payroll-filing :kontor.audit-doc/language :fr`
      rows when any payroll-fact carries QC component-kinds.
      The CRA-side `CaPayrollEmitProvider` still emits its own EN row;
      QC employees produce a parallel FR row for the audit chain.

   2. **`build-rl1-submission-tx-data`** — companion of
      `kontor.payroll-ca.rl1/payroll-facts->rl1-slip` +
      `kontor.payroll-ca.rl1-summary/build-summary` +
      `kontor.payroll-ca.rl1-summary/submission`. Records what was
      emitted to Revenu Québec with `:kontor.audit-doc/language :fr`.

   ## QC detection (with the emitter installed)

   When the QC emitter is wired through `run-payroll!`, the existing
   `kontor.payroll-ca.emit/warn-if-qc-detected!` warning is suppressed
   (the emitter handles it). The wrapper here is what `run-payroll!`
   sees: pass `:qc-emit-provider` alongside `:emit-provider`, and
   `CaPayrollEmitProvider` swaps its passthrough warning for a no-op.

   Reference: note 84 §8 + ADR-087."
  (:require [kontor.payroll-ca.emit :as emit]
            [kontor.payroll-ca.rl1 :as rl1]
            [kontor.payroll-ca.rl1-summary :as rl1-sum]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; QcPayrollEmitProvider — per-pay-period FR audit-doc for QC employees
;; ============================================================================

(defrecord QcPayrollEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ payroll-facts {:keys [pay-period-eid entity-eid]}]
    (let [qc-set (emit/qc-employees-in-facts payroll-facts)]
      (when (seq qc-set)
        [{:kontor.audit-doc/code (str "QC-PAYROLL-EVENT-" entity-eid
                               "-" pay-period-eid)
          :kontor.audit-doc/type :payroll-run-summary
          :kontor.audit-doc/title
          (format (str "QC payroll run (%d QC employments) for "
                       "pay-period %d, entity %d")
                  (count qc-set) pay-period-eid entity-eid)
          :kontor.audit-doc/description
          (str "QC employments: " qc-set ". TPZ-1015 totals computed "
               "via kontor.payroll-ca.tpz1015/tpz1015-period-due; RL-1 "
               "year-end submission via "
               "kontor.payroll-ca.rl1-summary/submission. See ADR-087.")
          :kontor.audit-doc/category :payroll-filing
          :kontor.audit-doc/language (or (:language opts) :fr)
          :kontor.audit-doc/uploaded-at (java.util.Date.)}]))))

;; ============================================================================
;; Year-end RL-1 submission emit
;; ============================================================================

(defn build-rl1-submission!
  "Build a full year RL-1 + RL-1-Summary submission element. Pure
   function returning an XML element + the audit-doc tx-data; consumer
   transacts the audit-doc alongside the actual upload.

   Required opts:
     :db                 (snapshot for QC filtering — d/db conn)
     :facts              full year of PayrollFacts (filtered by employer
                         + tax-year; can include non-QC facts — only QC
                         ones contribute to RL-1)
     :employer-neq       10-digit NEQ
     :employer-name      string or vector of up to 3 lines
     :tax-year           integer
     :transmitter        {:transmetteur/np-number :transmetteur/neq
                          :transmetteur/name :transmetteur/contact}
     :persons-by-emp     fn (employment-eid → person map)
     :fss-contribution   Money — employer FSS contribution
                         (consumer-supplied; rate depends on bracket).

   Optional:
     :employer-id-number  Quebec Identification Number (\"NPxxxxxx\");
                          consumer-supplied.
     :employer-address    CanadaAddressType-equivalent map.
     :report-type         :original (default) | :amended | :cancelled.
     :employer-qpp / :employer-qpip  Money — engine-computed totals
                          (consumer-supplied; substrate does NOT bundle
                          rate tables).
     :slip-reference-numbers  map employment-eid → string (Revenu
                          Québec assigns ranges to certified software).
     :summary-reference-number   string — Sommaire1 slip number.
     :contact             contact for the Sommaire1 element.
     :rl1-extras-map      consumer extension to `rl1/box-mapping`.

   Returns `{:submission <xml-element> :slips <vector>
            :summary <map> :audit-doc-tx-data <vector>}`."
  [{:keys [db facts employer-neq employer-id-number employer-name
           employer-address tax-year transmitter persons-by-emp
           report-type employer-qpp employer-qpip fss-contribution
           slip-reference-numbers summary-reference-number contact
           rl1-extras-map]
    :or {report-type :original}
    :as opts}]
  (when-not facts (throw (ex-info ":facts required" {})))
  (when-not employer-neq (throw (ex-info ":employer-neq required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (when-not employer-name (throw (ex-info ":employer-name required" {})))
  (when-not persons-by-emp
    (throw (ex-info ":persons-by-emp fn required" {})))
  (when-not transmitter (throw (ex-info ":transmitter required" {})))
  (let [grouped (rl1/group-facts-for-slips db facts)
        slip-maps (mapv
                   (fn [[emp-eid emp-facts]]
                     (rl1/payroll-facts->rl1-slip
                      (cond->
                       {:facts emp-facts
                        :employer-neq employer-neq
                        :employer-id-number employer-id-number
                        :person (persons-by-emp emp-eid)
                        :report-type report-type
                        :rl1-extras-map rl1-extras-map}
                        slip-reference-numbers
                        (assoc :reference-number
                               (get slip-reference-numbers emp-eid)))))
                   grouped)
        slip-elements (mapv rl1/slip->element slip-maps)
        summary (rl1-sum/build-summary
                 {:slips slip-maps
                  :employer-neq employer-neq
                  :employer-id-number employer-id-number
                  :employer-name employer-name
                  :employer-address employer-address
                  :tax-year tax-year
                  :report-type report-type
                  :fss-contribution fss-contribution
                  :employer-qpp employer-qpp
                  :employer-qpip employer-qpip
                  :contact contact
                  :reference-number summary-reference-number})
        submission-el (rl1-sum/submission
                       {:transmitter transmitter
                        :slips slip-elements
                        :summary summary})
        audit-doc (rl1/rl1-audit-doc-tx-data
                   {:employer-neq employer-neq
                    :tax-year tax-year
                    :slip-count (count slip-maps)
                    :report-type report-type
                    :language (or (:language opts) :fr)})]
    {:submission submission-el
     :slips slip-maps
     :summary summary
     :audit-doc-tx-data audit-doc}))
