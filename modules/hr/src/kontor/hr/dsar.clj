(ns kontor.hr.dsar
  "kontor-hr DSAR collector — extends kontor.dsar/collect with the
   :kontor.partner/person → :person → :employment → :compensation walk.

   The kernel's `kontor.dsar/collect` is partner-keyed (it walks
   `partner-attrs-registry` and `tx-attrs-registry` from a `:partner`
   subject). When the subject is a `:kontor.partner/kind :employee` partner,
   the consumer typically wants ALSO the HR-side facts: the :person
   linked via :kontor.partner/person, that person's :employment rows, the
   :compensation envelopes and components under each.

   `collect-for-person` walks that secondary tree given a :person eid;
   `collect-employee` is the convenience that takes a :partner eid,
   resolves :kontor.partner/person, and merges the kernel walk + the HR walk
   into one DSAR bundle."
  (:require [datahike.api :as d]
            [kontor.dsar :as dsar]))

(defn collect-for-person
  "Return everything kontor-hr holds about a :person eid.

   Returns:
     {:person          <pulled :person entity>
      :employments     [<pulled :employment with department + manager>]
      :compensations   [<pulled :compensation with components>]
      :pay-runs        [<pulled :payroll-run rows whose facts touched
                        any of this person's employments>]
      :contract-docs   [<pulled :audit-doc rows referenced by
                        :kontor.employment/contract-doc>]}

   Bitemporal-aware via `:as-of-tx` opt (`d/as-of` snapshot)."
  ([db person-eid] (collect-for-person db person-eid {}))
  ([db person-eid {:keys [as-of-tx]}]
   (let [db (if as-of-tx (d/as-of db as-of-tx) db)
         person (d/pull db '[*] person-eid)
         emps (->> (d/q '[:find [?e ...]
                          :in $ ?p
                          :where [?e :kontor.employment/person ?p]]
                        db person-eid)
                   (map #(d/pull db
                                 '[* {:kontor.employment/department [*]
                                      :kontor.employment/manager [:db/id :kontor.employment/code
                                                           :kontor.employment/job-title]
                                      :kontor.employment/contract-doc [*]}]
                                 %))
                   vec)
         emp-eids (mapv :db/id emps)
         comps (when (seq emp-eids)
                 (->> (d/q '[:find [?c ...]
                             :in $ [?e ...]
                             :where [?c :kontor.compensation/employment ?e]]
                           db emp-eids)
                      (map #(d/pull db
                                    '[* {:kontor.compensation/employment
                                         [:db/id :kontor.employment/code]
                                         :kontor.compensation/commodity
                                         [:db/id :kontor.commodity/symbol]}]
                                    %))
                      (map (fn [c]
                             (assoc c
                                    :kontor.compensation/components
                                    (->> (d/q '[:find [?cc ...]
                                                :in $ ?c
                                                :where [?cc :kontor.compensation-component/compensation ?c]]
                                              db (:db/id c))
                                         (map #(d/pull db '[*] %))
                                         vec))))
                      vec))
         contract-doc-eids (->> emps
                                (keep (comp :db/id :kontor.employment/contract-doc))
                                distinct
                                vec)]
     {:person        person
      :employments   emps
      :compensations (or comps [])
      :contract-docs (mapv #(d/pull db '[*] %) contract-doc-eids)})))

(defn collect-employee
  "DSAR convenience: walk both the kernel side (partner-driven) and
   the HR side (person-driven) starting from a :partner eid that has
   :kontor.partner/kind :employee + :kontor.partner/person link.

   Returns the kernel `collect` map MERGED with `{:hr <person-walk>}`.

   Same `:as-of-tx`, `:include-merged?` opts as kontor.dsar/collect."
  ([db partner-eid] (collect-employee db partner-eid {}))
  ([db partner-eid {:keys [as-of-tx] :as opts}]
   (let [kernel-bundle (dsar/collect db partner-eid opts)
         person-eid (d/q '[:find ?p .
                           :in $ ?pa
                           :where [?pa :kontor.partner/person ?p]]
                         db partner-eid)]
     (cond-> kernel-bundle
       person-eid (assoc :hr (collect-for-person db person-eid
                                                 (select-keys opts [:as-of-tx])))))))
