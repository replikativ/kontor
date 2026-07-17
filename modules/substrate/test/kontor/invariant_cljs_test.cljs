(ns kontor.invariant-cljs-test
  "Phase-A acceptance (note 192): the FULL `kontor.invariant/assert-invariants`
   pipeline — register invariants, apply tx-data to (before, after, empty+seed,
   txs), evaluate the 4-source `(q …)` queries, throw on mismatch — runs in
   datahike-cljs. This exercises `dc/empty-db`, `dc/db-with`, `d/entity`,
   lookup-ref seeding, and the reader-conditional'd load-time forms
   (alter-var-root dropped, planner var → nil, ExceptionInfo catch)."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.invariant :as inv]))

;; The shipped invariant queries (resources/invariants/*.edn), inlined as the
;; stored EDN strings. cljs can't slurp resources; validation.cljc will inline
;; these the same way in Phase D.
(def account-active-edn
  (str "[:find ?matches . :in $before $after $empty+txs $txs :where "
       "[(q [:find ?p :in $after $empty+txs :where "
       "[$empty+txs ?p :kontor.posting/account ?account] "
       "[$after ?account :kontor.account/active false]] $after $empty+txs) ?violators] "
       "[(count ?violators) ?n] [(= 0 ?n) ?matches]]"))

(def commodity-match-edn
  (str "[:find ?matches . :in $before $after $empty+txs $txs :where "
       "[(q [:find ?p :in $after $empty+txs :where "
       "[$empty+txs ?p :kontor.posting/account ?account] "
       "[$empty+txs ?p :kontor.posting/commodity ?pc] "
       "[$after ?account :kontor.account/commodity ?ac] "
       "[(not= ?pc ?ac)]] $after $empty+txs) ?violators] "
       "[(count ?violators) ?n] [(= 0 ?n) ?matches]]"))

(def schema
  [{:db/ident :invariant/rule  :db/valueType :db.type/keyword
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :invariant/query :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/path :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/active :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/commodity :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def old  [:kontor.account/path "Assets:Old"])

(deftest assert-invariants-runs-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:invariant/rule :kontor.posting/account  :invariant/query account-active-edn}
                                      {:invariant/rule :kontor.posting/commodity :invariant/query commodity-match-edn}]))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash" :kontor.account/active true  :kontor.account/commodity :EUR}
                                      {:kontor.account/path "Assets:Old"  :kontor.account/active false :kontor.account/commodity :EUR}]))
          ;; GOOD: active account, matching commodity → both invariants hold.
               (is (true? (inv/assert-invariants
                           conn [{:kontor.posting/account cash :kontor.posting/commodity :EUR}]))
                   "good posting passes both invariants in cljs")
          ;; VIOLATION: inactive account.
               (is (thrown? cljs.core/ExceptionInfo
                            (inv/assert-invariants
                             conn [{:kontor.posting/account old :kontor.posting/commodity :EUR}]))
                   "posting against an INACTIVE account throws in cljs")
          ;; VIOLATION: commodity mismatch (account EUR, posting USD).
               (is (thrown? cljs.core/ExceptionInfo
                            (inv/assert-invariants
                             conn [{:kontor.posting/account cash :kontor.posting/commodity :USD}]))
                   "commodity mismatch throws in cljs"))
             (<! (d/delete-database cfg))
             (done)))))
