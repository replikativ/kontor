(ns kontor.bitemporal-entity-cljs-test
  "Phase-B acceptance (note 192): the write-side bitemporal helper
   (`with-vt`/`strip-tx-meta`/`forever`) and the entity-hierarchy queries
   (`by-code`/`family`/`children`/`descendants`/`by-kind`) run in cljs.
   `with-vt` is pure; the entity queries run against a datahike-cljs fixture."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.entity :as entity]))

;; --- bitemporal: pure, no db ------------------------------------------------

(deftest with-vt-stamps-valid-time
  (let [vf #inst "2026-01-01"
        vt #inst "2026-12-31"
        tx [{:db/id -1 :x 1}]]
    (is (= [{:db/id -1 :x 1}
            {:db/id "datomic.tx" :db.valid/from vf}]
           (kbt/with-vt tx vf))
        "2-arity stamps :db.valid/from, leaves :to open")
    (is (= [{:db/id -1 :x 1}
            {:db/id "datomic.tx" :db.valid/from vf :db.valid/to vt}]
           (kbt/with-vt tx vf vt))
        "3-arity stamps both bounds")))

(deftest with-vt-is-idempotent
  (let [vf1 #inst "2026-01-01"
        vf2 #inst "2026-06-01"
        once  (kbt/with-vt [{:db/id -1 :x 1}] vf1)
        twice (kbt/with-vt once vf2)]
    (is (= 2 (count twice)) "re-stamping replaces the prior tx-meta, not appends")
    (is (= vf2 (:db.valid/from (last twice))) "the later vf wins")))

(deftest forever-is-an-instant
  (is (inst? kbt/forever)))

(deftest strip-tx-meta-removes-datomic-tx
  (is (= [{:db/id -1 :x 1}]
         (kbt/strip-tx-meta [{:db/id -1 :x 1}
                             {:db/id "datomic.tx" :db.valid/from #inst "2026-01-01"}]))))

;; --- entity: hierarchy queries against a fixture ----------------------------

(def entity-schema
  [{:db/ident :kontor.entity/code :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.entity/parent-entity :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.entity/kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.entity/active :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])

(deftest entity-hierarchy-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn entity-schema))
          ;; group -> {de, us}; de -> {de-sub}
               (<! (d/transact! conn [{:db/id -1 :kontor.entity/code "group"  :kontor.entity/kind :consolidation :kontor.entity/active true}
                                      {:db/id -2 :kontor.entity/code "de"     :kontor.entity/kind :operating :kontor.entity/active true :kontor.entity/parent-entity -1}
                                      {:db/id -3 :kontor.entity/code "us"     :kontor.entity/kind :operating :kontor.entity/active true :kontor.entity/parent-entity -1}
                                      {:db/id -4 :kontor.entity/code "de-sub" :kontor.entity/kind :operating :kontor.entity/active true :kontor.entity/parent-entity -2}]))
               (let [db    @conn
                     group (entity/by-code db "group")
                     de    (entity/by-code db "de")]
                 (is (some? group) "by-code resolves an entity")
                 (is (= de (entity/parent db (entity/by-code db "de-sub")))
                     "parent walks :parent-entity")
                 (is (= #{"de" "us" "de-sub"}
                        (into #{} (map #(:kontor.entity/code (d/pull db [:kontor.entity/code] %))
                                       (entity/descendants db group))))
                     "descendants is transitive")
                 (is (= 4 (count (entity/family db group)))
                     "family = entity + all descendants")
                 (is (= 3 (count (entity/by-kind db :operating)))
                     "by-kind filters active entities of a kind")))
             (<! (d/delete-database cfg))
             (done)))))
