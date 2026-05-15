(ns kontor.authz.consumer-readiness-test
  "Tests for #127: consumer-readiness of `kontor.authz.schema/write-schema-tx-data`
   / `write-schema!` / `read-schema`.

   These close the ADR-066-deferred gap on `AuthzClient.write-schema!` /
   `read-schema`: a consumer can now install a permission schema through
   the client, read it back, and compose authz schema writes with kernel
   writes atomically via `kontor.process` (when the conn has both schemas
   installed)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.authz.base :as base]
            [kontor.authz.client :as client]
            [kontor.authz.core :as authz]
            [kontor.authz.schema :as schema]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (java.util.UUID/randomUUID)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (d/connect cfg)))

;; ============================================================================
;; Round-trip: write-schema! → read-schema
;; ============================================================================

(deftest write-schema-and-read-schema-round-trip-through-the-client
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        defs [(base/Relation :account :owner :user)
              (base/Relation :server :account :account)
              (base/Permission :account :admin {:relation :owner})
              (base/Permission :server :view
                               {:arrow :account :permission :admin})]
        _ (authz/write-schema! c defs)
        {:keys [relations permissions]} (authz/read-schema c)]
    (testing "read-schema returns both relations + permissions"
      (is (= 2 (count relations)))
      (is (= 2 (count permissions))))
    (testing "the relations round-trip"
      (is (= #{[:account :owner :user] [:server :account :account]}
             (into #{}
                   (map (juxt :authz.relation/resource-type
                              :authz.relation/relation-name
                              :authz.relation/subject-type))
                   relations))))
    (testing "the permissions round-trip"
      (is (= #{[:account :admin :self :relation :owner]
               [:server :view :account :permission :admin]}
             (into #{}
                   (map (juxt :authz.permission/resource-type
                              :authz.permission/permission-name
                              :authz.permission/source-relation-name
                              :authz.permission/target-type
                              :authz.permission/target-name))
                   permissions))))))

(deftest write-schema-is-idempotent
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        defs [(base/Relation :account :owner :user)
              (base/Permission :account :admin {:relation :owner})]
        _ (authz/write-schema! c defs)
        _ (authz/write-schema! c defs)
        {:keys [relations permissions]} (authz/read-schema c)]
    (testing "re-installing the same definitions upserts (tuple identity)"
      (is (= 1 (count relations)))
      (is (= 1 (count permissions))))))

;; ============================================================================
;; Validation — reject undefined refs
;; ============================================================================

(deftest validation-rejects-undefined-arrow
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        ;; `server :view` uses arrow `:account`, but no `:server :account`
        ;; relation is defined.
        defs [(base/Permission :server :view
                               {:arrow :account :permission :admin})]
        ex (try (authz/write-schema! c defs)
                nil
                (catch clojure.lang.ExceptionInfo e e))
        data (when ex (ex-data ex))]
    (is (some? ex) "write-schema! must throw on undefined arrow")
    (is (= :authz/schema-invalid (:type data)))
    (is (some #(= :undefined-arrow (:error %)) (:errors data)))))

(deftest validation-rejects-undefined-target-relation
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        ;; relation :owner exists; the target `:relation :reader` does not.
        defs [(base/Relation :account :owner :user)
              (base/Permission :account :admin {:relation :reader})]
        ex (try (authz/write-schema! c defs) nil
                (catch clojure.lang.ExceptionInfo e e))
        data (when ex (ex-data ex))]
    (is (some? ex))
    (is (= :authz/schema-invalid (:type data)))
    (is (some #(= :undefined-relation (:error %)) (:errors data)))))

(deftest validation-rejects-undefined-target-permission
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        defs [(base/Relation :account :owner :user)
              (base/Relation :server :account :account)
              ;; the arrow + relation resolve, but the target permission
              ;; `:account :admin` is not defined.
              (base/Permission :server :view
                               {:arrow :account :permission :admin})]
        ex (try (authz/write-schema! c defs) nil
                (catch clojure.lang.ExceptionInfo e e))
        data (when ex (ex-data ex))]
    (is (some? ex))
    (is (= :authz/schema-invalid (:type data)))
    (is (some #(= :undefined-permission (:error %)) (:errors data)))))

(deftest validation-accepts-self-arrow
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        defs [(base/Relation :account :owner :user)
              ;; default Permission spec uses :self as arrow — must NOT
              ;; be rejected even though `:self` is not a declared relation.
              (base/Permission :account :admin {:relation :owner})]]
    (testing "self-arrow validates without an `:account :self` relation"
      (is (some? (authz/write-schema! c defs))))))

;; ============================================================================
;; write-schema-tx-data — pure builder, composable
;; ============================================================================

(deftest write-schema-tx-data-returns-pure-tx-data
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        defs [(base/Relation :account :owner :user)
              (base/Permission :account :admin {:relation :owner})]
        tx-data (schema/write-schema-tx-data (d/db conn) defs)]
    (testing "tx-data is a sequential of entity maps"
      (is (sequential? tx-data))
      (is (every? map? tx-data))
      (is (= 2 (count tx-data))))
    (testing "tx-data validates BEFORE returning — undefined ref throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/write-schema-tx-data
                    (d/db conn)
                    [(base/Permission :ghost :view {:relation :owner})]))))
    (testing "the tx-data installs cleanly via raw d/transact"
      (d/transact conn tx-data)
      (is (= 1 (count (:relations (schema/read-schema (d/db conn)))))))))
