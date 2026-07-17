(ns kontor.cljs-smoke-test
  "Phase-A empirical gate (note 192): confirm the two things the whole
   cross-platform port rests on actually work in datahike-cljs, before
   porting the gate/invariant core.

   1. `kontor.schema` (now .cljc) compiles + loads under ClojureScript.
   2. The `(q …)` FUNCTION-FORM subquery — the exact mechanism kontor's
      datalog invariants use (see resources/invariants/account_active.edn)
      — executes correctly against an in-memory datahike-cljs db. This is
      the disproved-blocker-#1 finding (note 192-review) turned into a test:
      the shipped invariants use `(q …)`, not the `subquery` built-in, and
      `q` is a first-class datahike built-in registered in the cljs engine.

   Runs on Node via kontor.node-runner. datahike-cljs writes are async, so
   this uses cljs.test/async + core.async."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.schema :as schema]))

;; Minimal schema — just the attrs the invariant query touches, so the test
;; is isolated from full-schema transaction concerns (a later phase).
(def mini-schema
  [{:db/ident :kontor.account/path   :db/valueType :db.type/string
    :db/unique :db.unique/identity   :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/active :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; Verbatim shape of resources/invariants/account_active.edn: an outer query
;; whose :where uses the `(q …)` function-form subquery, then count + `= 0`.
;; Holds (?matches true) iff no posting references an inactive account.
(def account-active-q
  '[:find ?matches .
    :in $before $after $empty+txs $txs
    :where
    [(q [:find ?p
         :in $after $empty+txs
         :where
         [$empty+txs ?p :kontor.posting/account ?account]
         [$after ?account :kontor.account/active false]]
        $after $empty+txs)
     ?violators]
    [(count ?violators) ?n-violators]
    [(= 0 ?n-violators) ?matches]])

(deftest schema-loads-in-cljs
  (is (pos? (count schema/all))
      "kontor.schema/all compiles + loads under ClojureScript"))

(deftest subquery-invariant-runs-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write
                      :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn mini-schema))
          ;; Violation: a posting against an INACTIVE account.
               (<! (d/transact! conn [{:db/id -1 :kontor.account/path "A:Bad"
                                       :kontor.account/active false}
                                      {:kontor.posting/account -1}]))
               (let [db @conn
                     holds? (d/q account-active-q db db db db)]
                 (is (= false holds?)
                     "inactive-account violation → invariant does NOT hold (?matches false)"))
          ;; Fix: flip the account active; the invariant should now hold.
               (<! (d/transact! conn [{:kontor.account/path "A:Bad"
                                       :kontor.account/active true}]))
               (let [db @conn
                     holds? (d/q account-active-q db db db db)]
                 (is (= true holds?)
                     "active account → invariant holds (?matches true)")))
             (<! (d/delete-database cfg))
             (done)))))
