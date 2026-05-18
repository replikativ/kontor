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
                                  kontor.process + the PayrollProvider
                                  trio from kontor.payroll-provider
     - kontor.hr.dsar          — collect-for-person walk (consumer
                                  drives this from a :dsar-request's
                                  partner → :partner/person link)

   Per ADR-002 the companion cohabits with the kernel + other
   companions in one DB. Per ADR-075 the only kernel additions are
   :audit-doc/category + :retention-policy/category; everything else
   lives here.

   Install order: kontor.core/install-schema! first (kernel),
   kontor.hr.core/install! second (this; reads :partner / :entity /
   :audit-doc / :period / :status-transition / :approval-policy from
   the kernel)."
  (:require [datahike.api :as d]
            [kontor.dsar :as dsar]
            [kontor.hr.schema :as schema]))

(defn install!
  "Idempotent install — kernel attrs must be present (run
   kontor.core/install-schema! first). After this call, the
   :person / :employment / :compensation / etc. entities are usable
   and their status-machine facets are registered.

   P1-86-5: registers an extension collector with `kontor.dsar` so
   the kernel-canonical `kontor.dsar/collect` walk reaches the HR
   side. The :partner/person link is partner→person (not
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
                                  :where [?pa :partner/person ?p]]
                                db partner-eid)]
       ((requiring-resolve 'kontor.hr.dsar/collect-for-person)
        db person-eid
        (select-keys opts [:as-of-tx]))))))

;; ============================================================================
;; Convenience resolvers
;; ============================================================================

(defn person-by-external-id
  "Resolve a :person eid by :person/external-id."
  [db external-id]
  (d/q '[:find ?e . :in $ ?x :where [?e :person/external-id ?x]] db external-id))

(defn employment-by-code
  "Resolve an :employment eid by :employment/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :employment/code ?c]] db code))

(defn pay-period-by-code
  "Resolve a :pay-period eid by :pay-period/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :pay-period/code ?c]] db code))
