(ns kontor.hr.department
  "kontor-hr :department transactors — the recursive per-entity org tree.

   A :department belongs to one :entity (ADR-031) and may have a
   :parent for the tree structure. :kontor.department/manager refs an
   :employment, not a :person — the manager-role is per their
   employment in this entity."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

(defn create-department-tx-data
  "Pure tx-data builder for `create-department!`.

   Required: :code, :name, :entity
   Optional: :parent (ref to :department), :manager (ref to :employment),
             :tempid (default \"department-1\")"
  [_db {:keys [code name entity parent manager tempid]
        :or {tempid "department-1"}}]
  (when-not code   (throw (ex-info ":code required" {})))
  (when-not name   (throw (ex-info ":name required" {})))
  (when-not entity (throw (ex-info ":entity required" {})))
  [(cond-> {:db/id tempid
            :kontor.department/code code
            :kontor.department/name name
            :kontor.department/entity entity}
     parent  (assoc :kontor.department/parent parent)
     manager (assoc :kontor.department/manager manager))])

(defn create-department!
  [conn opts]
  (let [tx (create-department-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))
