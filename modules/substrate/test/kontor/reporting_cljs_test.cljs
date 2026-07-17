(ns kontor.reporting-cljs-test
  "Phase-E1a (note 192, rung 3): the core read path — `account-balance` +
   `trial-balance` — is ported to .cljc and its query machinery (d/db, d/as-of,
   the bitemporal get-else datalog, d/pull, money/sum-by-commodity) EXECUTES in
   datahike-cljs. Verified here against a schema'd but empty db: the reads run
   without error and return empty results.

   Full data-bearing reads can't be unit-tested in a locally-built cljs db:
   posting amounts are `:db.type/bigdec`, and datahike-cljs rejects bigdec
   values (see datahike-cljs-bigdec-gap memory) — and this build also rejects
   undeclared attrs even under :schema-flexibility :write, so the schemaless
   workaround doesn't apply either. In production the browser reads a
   konserve-SYNCED replica, whose datoms are written by the sync layer (not
   d/transact), bypassing transact-time validation — so real amounts are
   present and these same fns return them. The JVM suite verifies the actual
   sums. Both unblock fully once datahike-cljs accepts the fress Bigdec for
   :db.type/bigdec."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]))

(def schema
  [{:db/ident :kontor.account/path        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(deftest read-machinery-executes-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash" :kontor.account/type :asset}]))
               (let [eid (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn "Assets:Cash")]
            ;; The full bitemporal read path runs (d/db → d/as-of → get-else
            ;; datalog → d/pull → sum-by-commodity); no postings ⇒ empty map.
                 (is (= {} (balance/account-balance conn eid))
                     "account-balance executes in cljs and returns {} for no postings")
                 (is (map? (trial/trial-balance conn))
                     "trial-balance executes in cljs")))
             (<! (d/delete-database cfg))
             (done)))))
