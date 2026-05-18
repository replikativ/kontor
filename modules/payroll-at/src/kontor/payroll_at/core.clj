(ns kontor.payroll-at.core
  "Top-level public surface for kontor-payroll-at (ADR-072).

   The orchestrator that composes [compute → post-gl-tx → accruals →
   emit-mbgm → maybe-emit-l16] as a `kontor.process` step-list (ADR-067)
   for a single payroll period.

   The orchestration is intentionally thin — each step is a leaf
   `*-tx-data` builder (ADR-068); this file just wires them. Consumers
   that need a different sequence (e.g. defer the mBGM emit until
   manual review) can call the leaves directly."
  (:require [datahike.api :as d]
            [kontor.payroll-at.accrual :as accrual]
            [kontor.payroll-at.compute :as compute]
            [kontor.payroll-at.emit :as emit]
            [kontor.payroll-at.mbgm :as mbgm]
            [kontor.payroll-at.posting-builder :as pb]))

;; ============================================================================
;; Re-exports for ergonomics
;; ============================================================================

(def parse compute/parse)
(def validate-result compute/validate-result)

(def build-tx-data pb/build-tx-data)
(def post! pb/post!)

(def accrue-urlaubsrueckstellung-tx-data accrual/accrue-urlaubsrueckstellung-tx-data)
(def accrue-urlaubsrueckstellung!        accrual/accrue-urlaubsrueckstellung!)
(def accrue-sonderzahlung-tx-data        accrual/accrue-sonderzahlung-tx-data)
(def accrue-sonderzahlung!               accrual/accrue-sonderzahlung!)

(def emit-mbgm!  mbgm/emit-mbgm!)
(def emit-l16!   emit/emit-l16!)

(def make-at-emit-provider emit/make-at-emit-provider)

;; ============================================================================
;; run-payroll-period! — the convenience orchestrator
;; ============================================================================

(defn run-payroll-period!
  "Run a single AT payroll period end-to-end:
     1. Parse the engine export (BMD or RZL).
     2. Validate (defensive).
     3. Post the period GL transaction.
     4. Optionally accrue Urlaubsrückstellung.
     5. Optionally accrue Sonderzahlung (12-month rollover).
     6. Emit + record the mBGM filing audit-doc.

   Required opts:
     :engine                  :bmd | :rzl
     :source                  anything io/reader accepts
     :journal                 ref (lookup-ref [:journal/code \"PAYROLL\"])
     :commodity               ref (lookup-ref [:commodity/symbol \"EUR\"])
     :effective-date          period-end #inst
     :dienstgeber-beitragskonto  string
     :storage-uri             where the consumer files the mBGM XML

   Optional opts:
     :employer-name
     :account-map / :payable-map (override default RLG-1 routing)
     :urlaubs                 {:amount <bigdec>}                — accrue
     :sonder                  {:amount <bigdec>}                — accrue

   Returns
     {:payroll-result <result>
      :gl-tx-report   <r>
      :urlaubs-tx-report <r-or-nil>
      :sonder-tx-report  <r-or-nil>
      :mbgm           {:tx-report :bytes :hash :code :title}}"
  [conn {:keys [engine source
                journal commodity effective-date
                dienstgeber-beitragskonto storage-uri
                employer-name account-map payable-map
                urlaubs sonder]
         :as opts}]
  (when-not engine (throw (ex-info ":engine required" {})))
  (when-not source (throw (ex-info ":source required" {})))
  (when-not journal (throw (ex-info ":journal required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not effective-date (throw (ex-info ":effective-date required" {})))
  (let [payroll-result (compute/parse engine source)
        _ (let [{:keys [ok? anomalies]} (compute/validate-result payroll-result)]
            (when-not ok?
              (throw (ex-info "AT-payroll engine export has integrity issues"
                              {:anomalies anomalies}))))
        gl-report (post! conn
                         {:payroll-result payroll-result
                          :journal journal
                          :commodity commodity
                          :effective-date effective-date
                          :account-map account-map
                          :payable-map payable-map})
        urlaubs-report
        (when urlaubs
          (accrue-urlaubsrueckstellung!
           conn {:amount (:amount urlaubs)
                 :journal journal
                 :commodity commodity
                 :effective-date effective-date}))
        sonder-report
        (when sonder
          (accrue-sonderzahlung!
           conn {:amount (:amount sonder)
                 :journal journal
                 :commodity commodity
                 :effective-date effective-date}))
        mbgm (emit-mbgm! conn
                         {:payroll-result payroll-result
                          :dienstgeber-beitragskonto dienstgeber-beitragskonto
                          :storage-uri storage-uri
                          :employer-name employer-name})]
    {:payroll-result payroll-result
     :gl-tx-report   gl-report
     :urlaubs-tx-report urlaubs-report
     :sonder-tx-report  sonder-report
     :mbgm           mbgm}))
