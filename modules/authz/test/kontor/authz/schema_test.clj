(ns kontor.authz.schema-test
  "ADR-065: kontor-authz — the schema + the entity-map builders.

   Covers (the first unit — core protocol + base builders + schema):
   - install! lays down the :kontor.authz/* attributes idempotently.
   - Relation / Permission / Relationship builders emit the component
     attributes, and datahike auto-computes the composite tuple
     index attributes (:kontor.authz.relation/identity,
     :kontor.authz.permission/identity, :kontor.authz.relationship/forward +
     /reverse).
   - the tuple :db.unique/identity constraints dedupe — re-declaring
     a Relation / Permission / Relationship upserts onto the same
     entity rather than creating a duplicate.
   - object-ref builds a typed subject/resource reference."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.authz.base :as base]
            [kontor.authz.core :as authz]
            [kontor.authz.schema :as schema]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (java.util.UUID/randomUUID)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (d/connect cfg)))

;; ============================================================================
;; install!
;; ============================================================================

(deftest install-lays-down-the-authz-attrs
  (let [conn (fresh-conn)]
    (schema/install! conn)
    (let [db (d/db conn)
          attr? (fn [a] (some? (d/q '[:find ?e . :in $ ?a
                                      :where [?e :db/ident ?a]] db a)))]
      (testing "the core component + tuple attrs are installed"
        (is (attr? :kontor.authz/object-id))
        (is (attr? :kontor.authz.relation/identity))
        (is (attr? :kontor.authz.permission/identity))
        (is (attr? :kontor.authz.relationship/forward))
        (is (attr? :kontor.authz.relationship/reverse))))
    (testing "install! is idempotent"
      (is (some? (schema/install! conn))))))

;; ============================================================================
;; Builders + tuple auto-computation
;; ============================================================================

(deftest builders-emit-components-and-tuples-auto-compute
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        ;; the access model: account { relation owner: user
        ;;                              permission admin = owner }
        ;;                   server  { relation account: account
        ;;                             permission view = account->admin }
        _ (d/transact conn [(base/Relation :account :owner :user)
                            (base/Relation :server :account :account)
                            (base/Permission :account :admin {:relation :owner})
                            (base/Permission :server :view
                                             {:arrow :account :permission :admin})])
        db (d/db conn)]
    (testing "Relation emits its component attrs"
      (is (= [:account :owner :user]
             (first (d/q '[:find ?rt ?rn ?st
                           :where
                           [?e :kontor.authz.relation/resource-type ?rt]
                           [?e :kontor.authz.relation/relation-name ?rn]
                           [?e :kontor.authz.relation/subject-type ?st]
                           [(= ?rt :account)]]
                         db)))))
    (testing "the :kontor.authz.relation/identity tuple auto-computes"
      (is (= [:account :owner :user]
             (:kontor.authz.relation/identity
              (d/pull db [:kontor.authz.relation/identity]
                      [:kontor.authz.relation/identity [:account :owner :user]])))))
    (testing "the :kontor.authz.permission/identity tuple auto-computes (arrow permission)"
      (is (some? (d/q '[:find ?e .
                        :where
                        [?e :kontor.authz.permission/identity
                         [:server :account :permission :admin :view]]]
                      db))))
    (testing "Permission with :relation spec resolves through a relation"
      (let [p (d/pull db [:kontor.authz.permission/source-relation-name
                          :kontor.authz.permission/target-type
                          :kontor.authz.permission/target-name]
                      [:kontor.authz.permission/identity
                       [:account :self :relation :owner :admin]])]
        (is (= :self     (:kontor.authz.permission/source-relation-name p)))
        (is (= :relation (:kontor.authz.permission/target-type p)))
        (is (= :owner    (:kontor.authz.permission/target-name p)))))))

(deftest relationship-builder-and-forward-reverse-tuples
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        ;; subject + resource entities (consumer-defined; here keyed by
        ;; :kontor.authz/object-id, the external-id handle)
        _ (d/transact conn [{:db/id "u" :kontor.authz/object-id "user-1"}
                            {:db/id "a" :kontor.authz/object-id "account-1"}])
        db0 (d/db conn)
        u (d/q '[:find ?e . :where [?e :kontor.authz/object-id "user-1"]] db0)
        a (d/q '[:find ?e . :where [?e :kontor.authz/object-id "account-1"]] db0)
        _ (d/transact conn [(base/Relationship (authz/object-ref :user u)
                                               :owner
                                               (authz/object-ref :account a))])
        db (d/db conn)]
    (testing "the forward tuple auto-computes — (s-type s rel r-type r)"
      (is (some? (d/q '[:find ?e . :in $ ?fwd
                        :where [?e :kontor.authz.relationship/forward ?fwd]]
                      db [:user u :owner :account a]))))
    (testing "the reverse tuple auto-computes — (r-type r rel s-type s)"
      (is (some? (d/q '[:find ?e . :in $ ?rev
                        :where [?e :kontor.authz.relationship/reverse ?rev]]
                      db [:account a :owner :user u]))))
    (testing "the relationship refs resolve to the subject + resource entities"
      (let [rel (d/pull db [{:kontor.authz.relationship/subject [:kontor.authz/object-id]}
                            {:kontor.authz.relationship/resource [:kontor.authz/object-id]}]
                        (d/q '[:find ?e . :where [?e :kontor.authz.relationship/forward _]]
                             db))]
        (is (= "user-1"    (:kontor.authz/object-id (:kontor.authz.relationship/subject rel))))
        (is (= "account-1" (:kontor.authz/object-id (:kontor.authz.relationship/resource rel))))))))

;; ============================================================================
;; Tuple :db.unique/identity dedup
;; ============================================================================

(deftest tuple-identity-dedupes-definitions-and-edges
  (let [conn (fresh-conn)
        _ (schema/install! conn)
        _ (d/transact conn [{:db/id "u" :kontor.authz/object-id "user-1"}
                            {:db/id "a" :kontor.authz/object-id "account-1"}])
        db0 (d/db conn)
        u (d/q '[:find ?e . :where [?e :kontor.authz/object-id "user-1"]] db0)
        a (d/q '[:find ?e . :where [?e :kontor.authz/object-id "account-1"]] db0)]
    (testing "re-declaring a Relation upserts (one entity, not two)"
      (d/transact conn [(base/Relation :account :owner :user)])
      (d/transact conn [(base/Relation :account :owner :user)])
      (is (= 1 (count (d/q '[:find [?e ...]
                             :where [?e :kontor.authz.relation/identity
                                     [:account :owner :user]]]
                           (d/db conn))))))
    (testing "re-creating an identical Relationship upserts (one edge, not two)"
      (d/transact conn [(base/Relationship (authz/object-ref :user u) :owner
                                           (authz/object-ref :account a))])
      (d/transact conn [(base/Relationship (authz/object-ref :user u) :owner
                                           (authz/object-ref :account a))])
      (is (= 1 (count (d/q '[:find [?e ...]
                             :where [?e :kontor.authz.relationship/forward
                                     [:user ?u :owner :account ?a]]]
                           (d/db conn))))))))

;; ============================================================================
;; object-ref
;; ============================================================================

(deftest object-ref-builds-typed-references
  (testing "2-arity — a plain subject/resource"
    (let [o (authz/object-ref :user "alice")]
      (is (= :user (:type o)))
      (is (= "alice" (:id o)))
      (is (nil? (:relation o)))))
  (testing "3-arity — a userset (subject-relation)"
    (let [o (authz/object-ref :group "admins" :member)]
      (is (= :member (:relation o))))))
