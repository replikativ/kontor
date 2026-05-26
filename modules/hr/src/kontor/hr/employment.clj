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
   period-lock + state-machine) via transact-with-validation. The
   load-bearing state-changing transitions (`terminate!`,
   `start-leave!`, `return-from-leave!`) route through
   `kontor.status-machine/record-status-change-tx-data` so the
   approval-policy gate (`:requires-supporting-doc` on termination)
   actually fires — note 85 P0-85-1."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.util Date]))

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
;; Routes through kontor.status-machine/record-status-change-tx-data so
;; the ADR-038 approval-policy gate (:requires-supporting-doc on
;; :active|:on-leave → :terminated, seeded in schema.clj) actually
;; fires. Note 85 P0-85-1: the prior flat-facet write bypassed the
;; gate, leaving the approval-policy seed dead.

(defn terminate-tx-data
  "Pure tx-data builder for `terminate!`. Returns tx-data that:
     - drives :employment/state to :terminated via the status machine
       (legality check + history row + facet update)
     - sets :employment/end-date + :employment/termination-reason on
       the same entity in the same transaction

   Required opts:
     :employment       — eid or :employment/code
     :end-date         — instant
     :reason           — keyword (open-set per jurisdiction)
     :supporting-doc   — ref to :audit-doc (the termination letter /
                         wrongful-dismissal-review memo / mutual-
                         agreement record). ADR-038 :requires-
                         supporting-doc fires; the gate REJECTS the
                         tx if absent. Pass an audit-doc eid or a
                         lookup-ref.
     :changed-by-uid   — ref to :create/uid stamping the operator

   Optional:
     :changed-at       — instant; defaults to (Date.)
     :reason-note      — free-text companion to :reason"
  [db {:keys [employment end-date reason supporting-doc
              changed-by-uid changed-at reason-note]}]
  (when-not employment     (throw (ex-info ":employment required" {})))
  (when-not end-date       (throw (ex-info ":end-date required" {})))
  (when-not reason         (throw (ex-info ":reason required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (termination letter) — note 85 P0-85-1 + schema.clj :requires-supporting-doc approval policy"
                                           {:type :hr/termination-supporting-doc-required})))
  (let [eid (if (number? employment)
              employment
              (d/q '[:find ?e . :in $ ?c :where [?e :employment/code ?c]]
                   db employment))]
    (when-not eid (throw (ex-info "unknown :employment" {:employment employment})))
    (let [status-tx (sm/record-status-change-tx-data
                     db (cond-> {:entity      eid
                                 :entity-type :employment
                                 :facet       :employment/state
                                 :to          :terminated
                                 :changed-at  (or changed-at (Date.))
                                 :supporting-doc supporting-doc}
                          changed-by-uid (assoc :changed-by-uid changed-by-uid)
                          reason         (assoc :reason reason)
                          reason-note    (assoc :reason-note reason-note)))]
      (into status-tx
            [{:db/id eid
              :employment/end-date end-date
              :employment/termination-reason reason}]))))

(defn terminate!
  "Transact a termination. The approval-policy gate
   (:requires-supporting-doc on :active|:on-leave → :terminated)
   fires inside transact-with-validation; calls without :supporting-doc
   are rejected before any data is written."
  [conn opts]
  (let [tx (terminate-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))

;; ============================================================================
;; sum-work-time-fraction — P1-86-7 over-allocation helper
;; ============================================================================

(defn sum-work-time-fraction
  "Sum :employment/work-time-fraction across a person's concurrent
   employments at `at-date` (default: now). Returns a BigDecimal.

   The substrate INTENTIONALLY does NOT enforce sum ≤ 1.0 (see schema
   docstring on :employment/work-time-fraction): secondment-with-
   overlap is legitimate. This helper exists so a consumer's HR
   policy can compose an over-allocation guard tailored to its rules
   (e.g. 'sum > 1.5 requires approval', 'apprentice cannot stack
   with another employment')."
  (^java.math.BigDecimal [db person]
   (sum-work-time-fraction db person (Date.)))
  (^java.math.BigDecimal [db person ^Date at-date]
   (let [person-eid (if (number? person)
                      person
                      (d/q '[:find ?p . :in $ ?x :where
                             [?p :kontor.person/external-id ?x]]
                           db person))]
     (or (d/q '[:find (sum ?ft) .
                :with ?e
                :in $ ?p ?at
                :where
                [?e :employment/person ?p]
                [?e :employment/work-time-fraction ?ft]
                [?e :employment/start-date ?start]
                [(<= ?start ?at)]
                [(get-else $ ?e :employment/end-date
                           #inst "9999-12-31") ?end]
                [(< ?at ?end)]]
              db person-eid at-date)
         0M))))
