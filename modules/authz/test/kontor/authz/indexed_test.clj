(ns kontor.authz.indexed-test
  "ADR-066: kontor-authz — the permission-graph traversal + the client.

   A realistic ReBAC scenario — the SpiceDB model

     account { relation owner: user
               permission admin  = owner
               permission view   = owner
               permission viewer = admin }          ; self-permission
     server  { relation account: account
               permission view  = account->admin    ; arrow permission
               permission admin = account->admin }

   exercises every path type (direct relation, arrow-to-permission,
   self-permission) through `can?`, `lookup-resources`,
   `count-resources`, `lookup-subjects`, the cursor, and the
   create/delete write path."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.authz.base :as base :refer [Relation Permission]]
            [kontor.authz.client :as client]
            [kontor.authz.core :as authz :refer [object-ref]]
            [kontor.authz.schema :as schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(def ^:private model
  [(Relation :account :owner :user)
   (Relation :server  :account :account)
   (Permission :account :admin  {:relation :owner})
   (Permission :account :view   {:relation :owner})
   (Permission :account :viewer {:permission :admin})         ; self-permission
   (Permission :server  :view   {:arrow :account :permission :admin})
   (Permission :server  :admin  {:arrow :account :permission :admin})])

(def ^:private entities
  (for [oid ["user-1" "user-2" "account-1" "account-2"
             "server-1" "server-2" "server-3"]]
    {:db/id oid :kontor.authz/object-id oid}))

(defn- ->u [id] (object-ref :user id))
(defn- ->a [id] (object-ref :account id))
(defn- ->s [id] (object-ref :server id))

(def ^:private relationships
  [(base/Relationship (->u "user-1") :owner   (->a "account-1"))
   (base/Relationship (->u "user-2") :owner   (->a "account-2"))
   (base/Relationship (->a "account-1") :account (->s "server-1"))
   (base/Relationship (->a "account-1") :account (->s "server-2"))
   (base/Relationship (->a "account-2") :account (->s "server-3"))])

(defn- fresh-client []
  (let [cfg  {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write :keep-history? false}
        _    (d/create-database cfg)
        conn (d/connect cfg)]
    (schema/install! conn)
    (d/transact conn (concat model entities relationships))
    (client/make-client conn)))

;; ============================================================================
;; can?
;; ============================================================================

(deftest can?-direct-relation-and-arrow-and-self
  (let [c (fresh-client)]
    (testing "direct relation — account admin = owner"
      (is (true?  (authz/can? c (->u "user-1") :admin (->a "account-1"))))
      (is (false? (authz/can? c (->u "user-2") :admin (->a "account-1")))))
    (testing "arrow permission — server view = account->admin"
      (is (true?  (authz/can? c (->u "user-1") :view (->s "server-1"))))
      (is (true?  (authz/can? c (->u "user-1") :view (->s "server-2"))))
      (is (false? (authz/can? c (->u "user-1") :view (->s "server-3"))))
      (is (true?  (authz/can? c (->u "user-2") :view (->s "server-3"))))
      (is (false? (authz/can? c (->u "user-2") :view (->s "server-1")))))
    (testing "self-permission — account viewer = admin"
      (is (true?  (authz/can? c (->u "user-1") :viewer (->a "account-1"))))
      (is (false? (authz/can? c (->u "user-2") :viewer (->a "account-1")))))
    (testing "can? on a non-existent subject/resource is false, not a throw"
      (is (false? (authz/can? c (->u "nobody") :view (->s "server-1"))))
      (is (false? (authz/can? c (->u "user-1") :view (->s "ghost")))))))

;; ============================================================================
;; lookup-resources + count-resources
;; ============================================================================

(deftest lookup-resources-enumerates-the-arrow-permission
  (let [c (fresh-client)]
    (testing "every server user-1 can :view (via account-1 → admin)"
      (is (= #{"server-1" "server-2"}
             (set (map :id (:data (authz/lookup-resources
                                   c {:subject (->u "user-1")
                                      :permission :view
                                      :resource/type :server})))))))
    (testing "user-2 sees only their account's server"
      (is (= #{"server-3"}
             (set (map :id (:data (authz/lookup-resources
                                   c {:subject (->u "user-2")
                                      :permission :view
                                      :resource/type :server})))))))
    (testing "count-resources agrees with lookup-resources"
      (is (= 2 (:count (authz/count-resources
                        c {:subject (->u "user-1") :permission :view
                           :resource/type :server}))))
      (is (= 1 (:count (authz/count-resources
                        c {:subject (->u "user-2") :permission :view
                           :resource/type :server})))))
    (testing "lookup-resources on a direct relation"
      (is (= #{"account-1"}
             (set (map :id (:data (authz/lookup-resources
                                   c {:subject (->u "user-1")
                                      :permission :admin
                                      :resource/type :account})))))))))

(deftest lookup-resources-cursor-pagination
  (let [c (fresh-client)
        page1 (authz/lookup-resources c {:subject (->u "user-1")
                                         :permission :view
                                         :resource/type :server
                                         :limit 1})
        page2 (authz/lookup-resources c {:subject (->u "user-1")
                                         :permission :view
                                         :resource/type :server
                                         :limit 1
                                         :cursor (:cursor page1)})]
    (testing "page 1 yields one resource + a cursor"
      (is (= 1 (count (:data page1))))
      (is (some? (:resource (:cursor page1)))))
    (testing "page 2 resumes after the cursor — a different resource"
      (is (= 1 (count (:data page2))))
      (is (not= (:id (first (:data page1)))
                (:id (first (:data page2))))))
    (testing "the two pages together cover both servers"
      (is (= #{"server-1" "server-2"}
             (set (concat (map :id (:data page1))
                          (map :id (:data page2)))))))))

;; ============================================================================
;; lookup-subjects
;; ============================================================================

(deftest lookup-subjects-reverse-traversal
  (let [c (fresh-client)]
    (testing "who can :view server-1 — user-1 via account-1"
      (is (= #{"user-1"}
             (set (map :id (:data (authz/lookup-subjects
                                   c {:resource (->s "server-1")
                                      :permission :view
                                      :subject/type :user})))))))
    (testing "who can :view server-3 — user-2"
      (is (= #{"user-2"}
             (set (map :id (:data (authz/lookup-subjects
                                   c {:resource (->s "server-3")
                                      :permission :view
                                      :subject/type :user})))))))
    (testing "who can :admin account-1 — user-1 (direct relation, reverse)"
      (is (= #{"user-1"}
             (set (map :id (:data (authz/lookup-subjects
                                   c {:resource (->a "account-1")
                                      :permission :admin
                                      :subject/type :user})))))))))

;; ============================================================================
;; write path — create / delete / read relationships
;; ============================================================================

(deftest write-and-delete-relationships
  (let [c (fresh-client)
        conn (:conn c)]
    (d/transact conn [{:db/id "user-3" :kontor.authz/object-id "user-3"}])
    (testing "create-relationship! grants access through the graph"
      (is (false? (authz/can? c (->u "user-3") :view (->s "server-1"))))
      (authz/create-relationship! c (->u "user-3") :owner (->a "account-1"))
      (is (true?  (authz/can? c (->u "user-3") :view (->s "server-1")))))
    (testing "create-relationship! on a duplicate throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (authz/create-relationship! c (->u "user-3") :owner
                                               (->a "account-1")))))
    (testing "read-relationships filters by subject"
      (is (= #{[:owner "account-1"]}
             (set (map (juxt :relation (comp :id :resource))
                       (authz/read-relationships c {:subject/id "user-3"}))))))
    (testing "delete-relationship! revokes access"
      (authz/delete-relationship! c (->u "user-3") :owner (->a "account-1"))
      (is (false? (authz/can? c (->u "user-3") :view (->s "server-1")))))
    (testing "write-relationships! returns a basis token"
      (let [result (authz/create-relationship! c (->u "user-3") :owner
                                               (->a "account-2"))]
        (is (string? (:kontor.authz/token result)))))))

;; ============================================================================
;; raw-eid client (no external-id layer)
;; ============================================================================

(deftest raw-eid-client
  (let [cfg  {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write :keep-history? false}
        _    (d/create-database cfg)
        conn (d/connect cfg)
        _    (schema/install! conn)
        _    (d/transact conn (concat model entities relationships))
        c    (client/make-client conn {:entity->object-id :db/id
                                       :object-id->ident  identity})
        db   (d/db conn)
        eid  (fn [oid] (d/q '[:find ?e . :in $ ?o
                              :where [?e :kontor.authz/object-id ?o]] db oid))]
    (testing "can? works with raw datahike eids"
      (is (true? (authz/can? c (object-ref :user (eid "user-1"))
                             :view (object-ref :server (eid "server-1"))))))
    (testing "lookup-resources returns raw eids"
      (is (= #{(eid "server-1") (eid "server-2")}
             (set (map :id (:data (authz/lookup-resources
                                   c {:subject (object-ref :user (eid "user-1"))
                                      :permission :view
                                      :resource/type :server})))))))))

;; ============================================================================
;; Review-after regression coverage (ADR-066 review — research note 43)
;; ============================================================================

(deftest read-relationships-rejects-an-unresolvable-id
  ;; P0 regression: a non-existent external id used to silently drop the
  ;; filter and over-return EVERY relationship (a confused-deputy read).
  (let [c (fresh-client)]
    (testing "an unresolvable :subject/id throws — it does NOT over-return"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"does not resolve"
           (authz/read-relationships c {:subject/id "ghost"
                                        :resource/type :server}))))
    (testing "an unresolvable :resource/id throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"does not resolve"
           (authz/read-relationships c {:resource/id "ghost"}))))
    (testing "a resolvable id still works"
      (is (= 1 (count (authz/read-relationships c {:subject/id "user-1"})))))))

(deftest cyclic-permission-schema-does-not-loop
  ;; P1 regression: a cyclic permission schema (an authoring typo) used
  ;; to StackOverflow can?/lookup-subjects. It must terminate (deny).
  (let [cfg  {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write :keep-history? false}
        _    (d/create-database cfg)
        conn (d/connect cfg)
        _    (schema/install! conn)
        ;; :loop = :loop (self-cycle); + a valid relation so entities exist
        _    (d/transact conn [(Relation :account :owner :user)
                               (Permission :account :loop {:permission :loop})
                               {:db/id "u" :kontor.authz/object-id "u"}
                               {:db/id "a" :kontor.authz/object-id "a"}
                               (base/Relationship (object-ref :user "u") :owner
                                                  (object-ref :account "a"))])
        c    (client/make-client conn)]
    (testing "can? on a cyclic permission terminates and denies"
      (is (false? (authz/can? c (->u "u") :loop (->a "a")))))
    (testing "lookup-resources on a cyclic permission terminates"
      (is (= [] (:data (authz/lookup-resources
                        c {:subject (->u "u") :permission :loop
                           :resource/type :account})))))
    (testing "lookup-subjects on a cyclic permission terminates"
      (is (= [] (:data (authz/lookup-subjects
                        c {:resource (->a "a") :permission :loop
                           :subject/type :user})))))))

(deftest relation-rejects-out-of-range-subject-type
  ;; P1 regression: a subject-type outside :a..:z is silently missed by
  ;; the :a/:z range-scan — Relation now throws at definition time.
  (testing ":zebra (sorts past :z) is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sort within :a..:z"
                          (Relation :account :owner :zebra))))
  (testing ":Account (uppercase, sorts before :a) is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sort within :a..:z"
                          (Relation :account :owner :Account))))
  (testing "an in-range subject-type is accepted"
    (is (map? (Relation :account :owner :user)))))

(deftest parallel-paths-to-one-resource-dedupe
  ;; The case the merge-dedupe machinery exists for: a subject reaching
  ;; one resource via TWO permission clauses (a union) must yield it
  ;; ONCE — lookup over-count + cursor-skip if dedup is broken.
  (let [cfg  {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write :keep-history? false}
        _    (d/create-database cfg)
        conn (d/connect cfg)
        _    (schema/install! conn)
        _    (d/transact
              conn
              [(Relation :doc :owner :user)
               (Relation :doc :editor :user)
               ;; view = owner + editor — two union clauses
               (Permission :doc :view {:relation :owner})
               (Permission :doc :view {:relation :editor})
               {:db/id "u" :kontor.authz/object-id "u"}
               {:db/id "d" :kontor.authz/object-id "d"}
               ;; u is BOTH owner and editor of d — two parallel paths
               (base/Relationship (object-ref :user "u") :owner
                                  (object-ref :doc "d"))
               (base/Relationship (object-ref :user "u") :editor
                                  (object-ref :doc "d"))])
        c    (client/make-client conn)]
    (testing "can? grants via the union"
      (is (true? (authz/can? c (->u "u") :view (object-ref :doc "d")))))
    (testing "lookup-resources yields the doc exactly ONCE (dedup)"
      (is (= ["d"] (map :id (:data (authz/lookup-resources
                                    c {:subject (->u "u") :permission :view
                                       :resource/type :doc}))))))
    (testing "count-resources counts it ONCE"
      (is (= 1 (:count (authz/count-resources
                        c {:subject (->u "u") :permission :view
                           :resource/type :doc})))))
    (testing "lookup-subjects yields the user exactly once"
      (is (= ["u"] (map :id (:data (authz/lookup-subjects
                                    c {:resource (object-ref :doc "d")
                                       :permission :view
                                       :subject/type :user}))))))))

(deftest arrow-to-relation-path
  ;; Coverage gap: only arrow→permission was modelled by the main
  ;; fixture; this exercises arrow→relation.
  (let [cfg  {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write :keep-history? false}
        _    (d/create-database cfg)
        conn (d/connect cfg)
        _    (schema/install! conn)
        _    (d/transact
              conn
              [(Relation :account :owner :user)
               (Relation :server  :account :account)
               ;; server owner-view = account->owner  (arrow → relation)
               (Permission :server :owner-view {:arrow :account
                                                :relation :owner})
               {:db/id "u" :kontor.authz/object-id "u"}
               {:db/id "a" :kontor.authz/object-id "a"}
               {:db/id "s" :kontor.authz/object-id "s"}
               (base/Relationship (object-ref :user "u") :owner
                                  (object-ref :account "a"))
               (base/Relationship (object-ref :account "a") :account
                                  (object-ref :server "s"))])
        c    (client/make-client conn)]
    (testing "can? resolves an arrow→relation path"
      (is (true? (authz/can? c (->u "u") :owner-view (object-ref :server "s")))))
    (testing "lookup-resources enumerates an arrow→relation path"
      (is (= ["s"] (map :id (:data (authz/lookup-resources
                                    c {:subject (->u "u")
                                       :permission :owner-view
                                       :resource/type :server}))))))
    (testing "lookup-subjects reverses an arrow→relation path"
      (is (= ["u"] (map :id (:data (authz/lookup-subjects
                                    c {:resource (object-ref :server "s")
                                       :permission :owner-view
                                       :subject/type :user}))))))))
