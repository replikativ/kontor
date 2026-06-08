(ns kontor.hr.person
  "kontor-hr :person transactors — the human-identity root.

   `:person` is the global, employment-independent identity. A
   `:person` may have N concurrent `:employment` rows (one per
   employing :entity per ADR-031).

   Every transactor follows the ADR-068 builder convention:
   `*-tx-data` is pure (returns vector of tx-ops), `*!` is the
   side-effecting wrapper that routes through
   `kontor.validation/transact-with-validation` so the legal-hold /
   period-lock / status-machine gates fire."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; create-person
;; ============================================================================

(defn create-person-tx-data
  "Pure tx-data builder for `create-person!`. Optional `:tempid`
   (default `\"person-1\"`) for cross-step references.

   Required keys:
     :external-id  — string, unique
     :given-name   — string
     :family-name  — string

   Optional keys:
     :birth-date    — instant (PII; surface :kontor.audit-doc/category
                      :hr-personnel when persisted alongside docs)
     :citizenship   — vector of ISO-3166 alpha-2 strings
     :kind          — :employee (default) | :contingent | :applicant |
                      :retiree | :board-member | :intern"
  [_db {:keys [external-id given-name family-name birth-date
               citizenship kind tempid]
        :or {tempid "person-1"}}]
  (when-not external-id (throw (ex-info ":external-id required" {})))
  (when-not given-name  (throw (ex-info ":given-name required" {})))
  (when-not family-name (throw (ex-info ":family-name required" {})))
  [(cond-> {:db/id tempid
            :kontor.person/external-id external-id
            :kontor.person/given-name given-name
            :kontor.person/family-name family-name
            :kontor.person/state :active}
     birth-date            (assoc :kontor.person/birth-date birth-date)
     (seq citizenship)     (assoc :kontor.person/citizenship (vec citizenship))
     kind                  (assoc :kontor.person/kind kind))])

(defn create-person!
  "Transact a new :person. Routes through transact-with-validation
   (ADR-068)."
  [conn opts]
  (let [tx (create-person-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))
