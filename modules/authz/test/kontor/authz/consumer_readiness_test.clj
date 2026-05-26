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
                   (map (juxt :kontor.authz.relation/resource-type
                              :kontor.authz.relation/relation-name
                              :kontor.authz.relation/subject-type))
                   relations))))
    (testing "the permissions round-trip"
      (is (= #{[:account :admin :self :relation :owner]
               [:server :view :account :permission :admin]}
             (into #{}
                   (map (juxt :kontor.authz.permission/resource-type
                              :kontor.authz.permission/permission-name
                              :kontor.authz.permission/source-relation-name
                              :kontor.authz.permission/target-type
                              :kontor.authz.permission/target-name))
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
    (is (= :kontor.authz/schema-invalid (:type data)))
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
    (is (= :kontor.authz/schema-invalid (:type data)))
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
    (is (= :kontor.authz/schema-invalid (:type data)))
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
      (is (= 1 (count (:relations (schema/read-schema (d/db conn))))))))
  (testing "an empty schema-defs is accepted and returns an empty tx-data"
    ;; Locked: write-schema-tx-data on `[]` is the no-op write. Useful
    ;; for migration tooling that bulk-builds schemas conditionally.
    (let [conn (fresh-conn)
          _ (schema/install! conn)]
      (is (= [] (schema/write-schema-tx-data (d/db conn) []))))))

;; ============================================================================
;; Multi-subject-type validation (P1 from review-after — note 40-equivalent)
;; ============================================================================

(deftest validation-rejects-undefined-ref-on-multi-subject-type-branches
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        c (client/make-client conn {})
        ;; `:doc :reader` forks two subject-types: :user AND :group.
        ;; The Permission's `{:permission :admin}` resolves on :user
        ;; (where we declared `:user :admin`) but NOT on :group — the
        ;; validator must reject the :group branch.
        defs [(base/Relation :doc :reader :user)
              (base/Relation :doc :reader :group)
              (base/Permission :user :admin {:relation :owner})
              (base/Relation :user :owner :uid)
              (base/Permission :doc :view {:arrow :reader :permission :admin})]
        ex (try (authz/write-schema! c defs) nil
                (catch clojure.lang.ExceptionInfo e e))
        data (when ex (ex-data ex))]
    (is (some? ex) "the :group branch's missing :admin permission must surface")
    (is (= :kontor.authz/schema-invalid (:type data)))
    (is (some (fn [e] (and (= :undefined-permission (:error e))
                           (= [:group :admin] (:ref e))))
              (:errors data)))))

;; ============================================================================
;; read-schema deterministic ordering (P1 from review-after)
;; ============================================================================

(deftest read-schema-returns-deterministically-ordered-results
  (let [defs [(base/Relation :z :owner :user)
              (base/Relation :a :owner :user)
              (base/Relation :m :owner :user)
              (base/Permission :z :admin {:relation :owner})
              (base/Permission :a :admin {:relation :owner})
              (base/Permission :m :admin {:relation :owner})]
        ;; Install on two fresh conns — different :id, different
        ;; hash-set iteration order. Both reads must agree.
        read! (fn []
                (let [conn (fresh-conn)
                      _ (schema/install! conn)
                      _ (schema/write-schema! conn defs)]
                  (schema/read-schema (d/db conn))))
        a (read!)
        b (read!)]
    (testing "the same schema reads back in the same order, regardless of conn"
      (is (= (:relations a) (:relations b)))
      (is (= (:permissions a) (:permissions b))))
    (testing "the order is the lex-by-tuple-key order"
      (is (= [:a :m :z]
             (mapv :kontor.authz.relation/resource-type (:relations a))))
      (is (= [:a :m :z]
             (mapv :kontor.authz.permission/resource-type (:permissions a)))))))
