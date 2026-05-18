(ns kontor.hr.employment
  "kontor-hr :employment transactors — the Workday-style relationship.

   One :person may have N concurrent :employment rows (one per
   employing :entity per ADR-031). Re-hire = new :employment row
   at a later :start-date; the prior row stays terminated as audit.

   The `hire!` orchestrator composes (per ADR-067 kontor.process):
     1. create :employment row (or reuse existing in :applicant /
        :offered state)
     2. attach :employment/contract-doc — an :audit-doc with
        :audit-doc/category :hr-personnel (kernel attr per ADR-075)
     3. state transition → :hired (or → :active if start-date already
        passed)

   The transactors honor the kernel gate stack (legal-hold +
   period-lock + state-machine) via transact-with-validation."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; hire
;; ============================================================================

(defn hire-tx-data
  "Pure tx-data builder for `hire!`. Returns a vector of tx-ops
   creating an :employment row (and optionally a contract :audit-doc).

   Required keys:
     :code            — string, unique :employment/code
     :person          — ref or eid of :person
     :entity          — ref or eid of :entity (the employer)
     :start-date      — instant

   Optional keys:
     :job-title              — string (default '')
     :department             — ref to :department
     :manager                — ref to :employment (NOT :person)
     :work-time-fraction     — BigDecimal (0.0–1.0); default 1M
     :work-relationship-kind — keyword (note 81 §9.7; default :standard)
     :exempt-flag            — boolean (US FLSA; default false)
     :fulltime-flag          — boolean (default true if work-time
                               -fraction >= 1.0, else false)
     :contract-doc           — ref to :audit-doc — the signed
                               contract; typical category :hr-personnel
     :initial-state          — :applicant | :offered | :hired |
                               :active (default :hired)
     :tempid                 — for cross-step composition"
  [_db {:keys [code person entity start-date job-title department
               manager work-time-fraction work-relationship-kind
               exempt-flag fulltime-flag contract-doc initial-state
               tempid]
        :or {tempid "employment-1"
             work-time-fraction 1M
             work-relationship-kind :standard
             initial-state :hired
             job-title ""}}]
  (when-not code       (throw (ex-info ":code required" {})))
  (when-not person     (throw (ex-info ":person required" {})))
  (when-not entity     (throw (ex-info ":entity required" {})))
  (when-not start-date (throw (ex-info ":start-date required" {})))
  (let [ft-flag (if (some? fulltime-flag)
                  fulltime-flag
                  (>= (compare work-time-fraction 1M) 0))]
    [(cond-> {:db/id tempid
              :employment/code code
              :employment/person person
              :employment/entity entity
              :employment/start-date start-date
              :employment/job-title job-title
              :employment/work-time-fraction work-time-fraction
              :employment/work-relationship-kind work-relationship-kind
              :employment/exempt-flag (boolean exempt-flag)
              :employment/fulltime-flag ft-flag
              :employment/state initial-state}
       department    (assoc :employment/department department)
       manager       (assoc :employment/manager manager)
       contract-doc  (assoc :employment/contract-doc contract-doc))]))

(defn hire!
  "Transact a new :employment. Routes through transact-with-validation.

   The structural gates fire (status-machine transition for
   :nil → :hired / :active is legal per the seeds; period-lock not
   applicable to :employment; legal-hold scope unchanged)."
  [conn opts]
  (let [tx (hire-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))

;; ============================================================================
;; terminate
;; ============================================================================

(defn terminate-tx-data
  "Pure tx-data builder for `terminate!`. Sets :employment/end-date,
   :employment/state :terminated, and :employment/termination-reason.
   The state transition is approval-policy-gated
   (:requires-supporting-doc — termination letter; see schema seeds)."
  [db {:keys [employment end-date reason]}]
  (when-not employment (throw (ex-info ":employment required" {})))
  (when-not end-date   (throw (ex-info ":end-date required" {})))
  (when-not reason     (throw (ex-info ":reason required" {})))
  (let [eid (if (number? employment)
              employment
              (d/q '[:find ?e . :in $ ?c :where [?e :employment/code ?c]]
                   db employment))]
    (when-not eid (throw (ex-info "unknown :employment" {:employment employment})))
    [{:db/id eid
      :employment/end-date end-date
      :employment/state :terminated
      :employment/termination-reason reason}]))

(defn terminate!
  "Transact a termination."
  [conn opts]
  (let [tx (terminate-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))
