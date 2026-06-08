(ns kontor.hr.core
  "kontor-hr — HR + payroll substrate (Stage R substrate, ADR-075).

   The public surface delegates to per-namespace transactors:
     - kontor.hr.person        — :person create / mark-deceased / purge
     - kontor.hr.employment    — :employment hire / terminate / re-hire
     - kontor.hr.compensation  — :compensation set / supersede /
                                  employment-current-wage
     - kontor.hr.department    — :department create / re-parent
     - kontor.hr.pay-period    — :pay-period create / advance state
     - kontor.hr.payroll       — run-payroll! orchestrator using
                                  kontor.workflow.process + the PayrollProvider
                                  trio from kontor.provider.payroll-provider
     - kontor.hr.dsar          — collect-for-person walk (consumer
                                  drives this from a :dsar-request's
                                  partner → :kontor.partner/person link)

   Per ADR-002 the companion cohabits with the kernel + other
   companions in one DB. Per ADR-075 the only kernel additions are
   :kontor.audit-doc/category + :kontor.retention-policy/category; everything else
   lives here.

   Install order: kontor.core/install-schema! first (kernel),
   kontor.hr.core/install! second (this; reads :partner / :entity /
   :audit-doc / :period / :status-transition / :approval-policy from
   the kernel)."
  (:require [datahike.api :as d]
            [kontor.compliance.dsar :as dsar]
            [kontor.hr.schema :as schema]))

(defn install!
  "Idempotent install — kernel attrs must be present (run
   kontor.core/install-schema! first). After this call, the
   :person / :employment / :compensation / etc. entities are usable
   and their status-machine facets are registered.

: registers an extension collector with `kontor.compliance.dsar` so
   the kernel-canonical `kontor.compliance.dsar/collect` walk reaches the HR
   side. The :kontor.partner/person link is partner→person (not
   person→partner like the rest of the partner-attrs registry), so
   it can't ride the standard partner-attrs mechanism; instead, HR
   registers a collector fn that, given a partner eid, pulls the
   linked :person (if any) and walks its employments + compensations
   using `kontor.hr.dsar/collect-for-person`. The kernel walker
   merges the result under :extensions :hr."
  [conn]
  (schema/install! conn)
  (dsar/register-extension-collector!
   :hr
   (fn [db partner-eid opts]
     (when-let [person-eid (d/q '[:find ?p .
                                  :in $ ?pa
                                  :where [?pa :kontor.partner/person ?p]]
                                db partner-eid)]
       ((requiring-resolve 'kontor.hr.dsar/collect-for-person)
        db person-eid
        (select-keys opts [:as-of-tx])))))
  conn)

;; ============================================================================
;; Convenience resolvers
;; ============================================================================

(defn person-by-external-id
  "Resolve a :person eid by :kontor.person/external-id."
  [db external-id]
  (d/q '[:find ?e . :in $ ?x :where [?e :kontor.person/external-id ?x]] db external-id))

(defn employment-by-code
  "Resolve an :employment eid by :kontor.employment/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.employment/code ?c]] db code))

(defn pay-period-by-code
  "Resolve a :pay-period eid by :kontor.pay-period/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.pay-period/code ?c]] db code))
