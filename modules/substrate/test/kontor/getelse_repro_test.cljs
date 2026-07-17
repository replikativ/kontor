(ns kontor.getelse-repro-test
  "Minimal repro of the cljs datahike get-else divergence (memory
   cljs-datahike-get-else-valid-time). Mirrors kontor's bitemporal read:
   `[(get-else $ ?tx :db.valid/from ?ti) ?vf]` — value if present, else the
   default source ?ti. On the JVM this returns the default; in cljs it
   diverges. Print the results so the exact behavior is unambiguous."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [datahike.query :as dq]))

(def schema
  [{:db/ident :name :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :ref  :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}])

(deftest get-else-default-source
  (async done
    (go
      (let [cfg {:store {:backend :memory :id (random-uuid)}
                 :schema-flexibility :write :keep-history? true}]
        (<! (d/create-database cfg))
        (let [conn (d/connect cfg)]
          (<! (d/transact! conn schema))
          ;; e1 -> e0; NEITHER carries :db.valid/from, so get-else must fall
          ;; back to the default source ?ti (the ref-datom's :db/txInstant).
          (let [r (<! (d/transact! conn [{:db/id -1 :name "root"}
                                         {:db/id -2 :name "child" :ref -1}]))]
            (println "REPRO transact =>" (if (instance? js/Error r) (str "ERROR: " (.-message r)) "ok"))
            (println "REPRO names =>" (pr-str (d/q '[:find [?n ...] :where [?c :name ?n]] @conn)))
            (println "REPRO datom-count =>" (count (d/datoms @conn :eavt))))
          (let [db @conn
                ;; (a) does the 4-elem pattern bind the datom's tx to ?tx?
                tx-bound (d/q '[:find ?tx . :in $ :where [?c :name "child"] [?c :ref _ ?tx]] db)
                ;; (b) does ANY tx have :db/txInstant? (unbound ?tx)
                any-ti (d/q '[:find ?ti . :in $ :where [?any :db/txInstant ?ti]] db)
                ;; (c) join: txInstant of that specific ?tx
                ti (d/q '[:find ?ti . :in $ :where [?c :name "child"] [?c :ref _ ?tx] [?tx :db/txInstant ?ti]] db)
                ;; get-else with a VAR default (?ti) — the kontor pattern.
                vf-var (d/q '[:find ?vf . :in $ :where
                              [?c :name "child"] [?c :ref _ ?tx] [?tx :db/txInstant ?ti]
                              [(get-else $ ?tx :db.valid/from ?ti) ?vf]] db)
                ;; get-else with a LITERAL default.
                vf-lit (d/q '[:find ?vf . :in $ :where
                              [?c :name "child"] [?c :ref _ ?tx]
                              [(get-else $ ?tx :db.valid/from :FALLBACK) ?vf]] db)]
            (println "REPRO (a) ?tx bound        =>" (pr-str tx-bound))
            (println "REPRO (b) any txInstant     =>" (str (type any-ti)) (pr-str any-ti))
            (println "REPRO (c) joined txInstant  =>" (str (type ti)) (pr-str ti))
            (binding [dq/*disable-planner* true]
              (println "REPRO planner-OFF (a) ?tx  =>" (pr-str (d/q '[:find ?tx . :in $ :where [?c :name "child"] [?c :ref _ ?tx]] db)))
              (println "REPRO planner-OFF (b) ti   =>" (pr-str (d/q '[:find ?ti . :in $ :where [?any :db/txInstant ?ti]] db)))
              (println "REPRO planner-OFF (c) join =>" (pr-str (d/q '[:find ?ti . :in $ :where [?c :name "child"] [?c :ref _ ?tx] [?tx :db/txInstant ?ti]] db))))
            (println "REPRO get-else var-default =>" (str (type vf-var)) (pr-str vf-var))
            (println "REPRO get-else lit-default =>" (str (type vf-lit)) (pr-str vf-lit))
            (is (some? ti) "txInstant present")
            (is (= ti vf-var) "get-else falls back to the ?ti default source (the kontor pattern)")
            (is (= :FALLBACK vf-lit) "get-else falls back to a literal default")))
        (<! (d/delete-database cfg))
        (done)))))
