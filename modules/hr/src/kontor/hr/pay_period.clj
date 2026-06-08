(ns kontor.hr.pay-period
  "kontor-hr :pay-period transactors. A :pay-period is per-entity;
   DE-monthly + US-biweekly coexist within a multi-entity group.

   The :kontor.pay-period/fiscal-period link points at the kernel :period
   (ADR-014) so the period-lock middleware refuses payroll runs into
   a locked fiscal period."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

(defn create-pay-period-tx-data
  "Pure tx-data builder.

   Required: :code, :entity, :start-date, :end-date, :frequency,
             :fiscal-period
   Optional: :tempid (default \"pay-period-1\")"
  [_db {:keys [code entity start-date end-date frequency fiscal-period
               tempid]
        :or {tempid "pay-period-1"}}]
  (doseq [[k v] {:code code :entity entity :start-date start-date
                 :end-date end-date :frequency frequency
                 :fiscal-period fiscal-period}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  [{:db/id tempid
    :kontor.pay-period/code code
    :kontor.pay-period/entity entity
    :kontor.pay-period/start-date start-date
    :kontor.pay-period/end-date end-date
    :kontor.pay-period/frequency frequency
    :kontor.pay-period/fiscal-period fiscal-period
    :kontor.pay-period/state :open}])

(defn create-pay-period!
  [conn opts]
  (let [tx (create-pay-period-tx-data (d/db conn) opts)]
    (validation/transact-with-validation conn tx)))
