(ns kontor.payroll-at.core
  "Top-level public surface for kontor-payroll-at (ADR-072).

   The orchestrator that composes [compute → post-gl-tx → accruals →
   emit-mbgm → maybe-emit-l16] as a `kontor.process` step-list (ADR-067)
   for a single payroll period.

   The orchestration is intentionally thin — each step is a leaf
   `*-tx-data` builder (ADR-068); this file just wires them. Consumers
   that need a different sequence (e.g. defer the mBGM emit until
   manual review) can call the leaves directly.

   ## When to use `run-payroll-period!` vs the kernel orchestrator

   The `run-payroll-period!` below is an AT-only convenience — it
   parses → posts → accrues → emits the mBGM in one call but
   bypasses `kontor.hr.payroll/run-payroll!` so it does NOT produce a
   `:payroll-run` row + does NOT thread through the kernel's
   PayrollProvider protocol trio.

   For trans-national workflows + bitemporal correction (ADR-048,
   ADR-067), prefer the kernel orchestrator wired with the bridge
   records in `kontor.payroll-at.adapter`:

     (require '[kontor.payroll-at.adapter :as adapter]
              '[kontor.hr.payroll :as payroll])
     (def providers (adapter/make-at-kontor-providers
                     {:db (d/db conn) :commodity eur
                      :dienstgeber-beitragskonto \"1234567\"
                      :use-default-rlg-1? true}))
     (payroll/run-payroll!
      conn (merge providers
                  {:pay-period pp-eid :entity ent
                   :employments [emp-1 emp-2]
                   :variable-inputs {:csv-source <BMD CSV>
                                     :employment-by-vsnr <map>}
                   :run-code \"...\" :tx-code \"...\"
                   :journal journal :commodity eur
                   :accounts {}}))

   See `kontor.payroll-at.adapter` for the bridge that satisfies the
   kernel PayrollComputeProvider / PayrollPostingBuilder /
   PayrollEmitProvider protocols."
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

   AT-ONLY convenience — does NOT thread through the kernel
   `kontor.hr.payroll/run-payroll!` PayrollProvider trio and does NOT
   produce a `:payroll-run` row. For trans-national workflows + bitemporal
   correction prefer the kernel orchestrator (see ns docstring +
   `kontor.payroll-at.adapter/make-at-kontor-providers`).

   Required opts:
     :engine                  :bmd | :rzl
     :source                  anything io/reader accepts
     :journal                 ref (lookup-ref [:journal/code \"PAYROLL\"])
     :commodity               ref (lookup-ref [:kontor.commodity/symbol \"EUR\"])
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
