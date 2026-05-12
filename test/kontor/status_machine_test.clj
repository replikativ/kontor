(ns kontor.status-machine-test
  "Tests for the generic :status-transition + :status-history primitive
   (ADR-034)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Seeding helpers
;; ============================================================================

(defn- seed-order-status-transitions!
  "Seed a minimal order status vocabulary: nil → :created → :approved
   → :completed (plus :cancelled escape). Tenant-wide."
  [conn]
  (d/transact conn
              [{:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/nil
                :status-transition/to :order.status/created
                :status-transition/active true
                :status-transition/name "Create Order"}
               {:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/created
                :status-transition/to :order.status/approved
                :status-transition/active true
                :status-transition/name "Approve Order"}
               {:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/approved
                :status-transition/to :order.status/completed
                :status-transition/active true
                :status-transition/name "Complete Order"}
               {:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/created
                :status-transition/to :order.status/cancelled
                :status-transition/active true
                :status-transition/name "Cancel Order"}
               {:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/approved
                :status-transition/to :order.status/cancelled
                :status-transition/active true
                :status-transition/name "Cancel Approved Order"}]))

;; To exercise the table without a real :order entity, we use a tiny
;; throwaway schema for an :ord entity with an :ord/status attr that
;; behaves like :order/status. (The kernel only owns the
;; :status-transition + :status-history attrs; consumers bring their
;; own facet attribute.)
(defn- install-ord-attr! [conn]
  (d/transact conn
              [{:db/ident :ord/code
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}
               {:db/ident :ord/status
                :db/valueType :db.type/keyword
                :db/cardinality :db.cardinality/one}]))

(defn- seed-ord-status-transitions!
  "Same vocabulary, scoped to entity-type :ord and facet :ord/status."
  [conn]
  (d/transact conn
              [{:status-transition/entity-type :ord
                :status-transition/facet :ord/status
                :status-transition/from :ord.status/nil
                :status-transition/to :ord.status/created
                :status-transition/active true
                :status-transition/name "Create Ord"}
               {:status-transition/entity-type :ord
                :status-transition/facet :ord/status
                :status-transition/from :ord.status/created
                :status-transition/to :ord.status/approved
                :status-transition/active true
                :status-transition/name "Approve Ord"}
               {:status-transition/entity-type :ord
                :status-transition/facet :ord/status
                :status-transition/from :ord.status/approved
                :status-transition/to :ord.status/completed
                :status-transition/active true
                :status-transition/name "Complete Ord"}
               {:status-transition/entity-type :ord
                :status-transition/facet :ord/status
                :status-transition/from :ord.status/created
                :status-transition/to :ord.status/cancelled
                :status-transition/active true
                :status-transition/name "Cancel Ord"}]))

;; ============================================================================
;; Schema presence
;; ============================================================================

(deftest schema-attrs-present
  (let [db (d/db *conn*)
        idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
    (doseq [a [:status-transition/entity-type
               :status-transition/facet
               :status-transition/from
               :status-transition/to
               :status-transition/applies-to-org
               :status-transition/active
               :status-transition/identity
               :status-history/entity
               :status-history/entity-type
               :status-history/facet
               :status-history/from
               :status-history/to
               :status-history/changed-at
               :status-history/changed-by-uid
               :status-history/reason
               :status-history/origin-transaction]]
      (is (contains? idents a) (str "missing: " a)))))

;; ============================================================================
;; legal-transition?
;; ============================================================================

(deftest legal-transition-matches-seeded-rows
  (seed-order-status-transitions! *conn*)
  (let [db (d/db *conn*)]
    (testing "seeded transitions are legal"
      (is (true? (sm/legal-transition? db :order :order/status
                                       :order.status/nil
                                       :order.status/created)))
      (is (true? (sm/legal-transition? db :order :order/status
                                       :order.status/created
                                       :order.status/approved)))
      (is (true? (sm/legal-transition? db :order :order/status
                                       :order.status/approved
                                       :order.status/completed))))
    (testing "non-seeded transitions are illegal"
      (is (false? (sm/legal-transition? db :order :order/status
                                        :order.status/nil
                                        :order.status/completed))
          "skip-to-completed without intermediate states")
      (is (false? (sm/legal-transition? db :order :order/status
                                        :order.status/completed
                                        :order.status/approved))
          "regress completed → approved")
      (is (false? (sm/legal-transition? db :order :order/status
                                        :order.status/cancelled
                                        :order.status/created))
          "cancelled is terminal"))))

(deftest legal-transitions-from-returns-set
  (seed-order-status-transitions! *conn*)
  (let [db (d/db *conn*)]
    (is (= #{:order.status/approved :order.status/cancelled}
           (sm/legal-transitions-from db :order :order/status :order.status/created)))
    (is (= #{:order.status/completed :order.status/cancelled}
           (sm/legal-transitions-from db :order :order/status :order.status/approved)))
    (is (= #{:order.status/created}
           (sm/legal-transitions-from db :order :order/status :order.status/nil)))
    (is (= #{}
           (sm/legal-transitions-from db :order :order/status :order.status/completed))
        "completed has no onward transitions in this seed")))

(deftest inactive-transitions-are-ignored
  (d/transact *conn*
              [{:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/created
                :status-transition/to :order.status/approved
                :status-transition/active false
                :status-transition/name "(deactivated)"}])
  (let [db (d/db *conn*)]
    (is (false? (sm/legal-transition? db :order :order/status
                                      :order.status/created
                                      :order.status/approved))
        "inactive row should not match")))

;; ============================================================================
;; Org-scoped overrides
;; ============================================================================

(deftest org-scoped-override-extends-defaults
  (seed-order-status-transitions! *conn*)
  ;; Create two orgs and add an org-specific override on one.
  (d/transact *conn*
              [{:entity/code "ACME"
                :entity/name "Acme Corp"
                :entity/kind :operating
                :entity/active true}
               {:entity/code "OTHER"
                :entity/name "Other Co"
                :entity/kind :operating
                :entity/active true}])
  ;; ACME allows :completed → :approved (re-open), which is NOT in the
  ;; tenant-wide vocabulary.
  (d/transact *conn*
              [{:status-transition/entity-type :order
                :status-transition/facet :order/status
                :status-transition/from :order.status/completed
                :status-transition/to :order.status/approved
                :status-transition/applies-to-org [:entity/code "ACME"]
                :status-transition/active true
                :status-transition/name "Re-open ACME order"}])
  (let [db (d/db *conn*)]
    (testing "ACME can re-open completed orders (org override)"
      (is (true? (sm/legal-transition? db :order :order/status
                                       :order.status/completed
                                       :order.status/approved
                                       "ACME"))))
    (testing "OTHER cannot re-open (no override)"
      (is (false? (sm/legal-transition? db :order :order/status
                                        :order.status/completed
                                        :order.status/approved
                                        "OTHER"))))
    (testing "Tenant-wide query (no org) does not match the override"
      (is (false? (sm/legal-transition? db :order :order/status
                                        :order.status/completed
                                        :order.status/approved))))
    (testing "legal-transitions-from merges tenant-wide + org-specific for ACME"
      (let [acme (sm/legal-transitions-from db :order :order/status
                                            :order.status/approved "ACME")]
        (is (contains? acme :order.status/completed))
        (is (contains? acme :order.status/cancelled))))))

;; ============================================================================
;; record-status-change!
;; ============================================================================

(deftest record-status-change-happy-path
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  (d/transact *conn* [{:ord/code "O-1" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e .
                   :in $ ?c
                   :where [?e :ord/code ?c]]
                 (d/db *conn*) "O-1")]
    (sm/record-status-change! *conn*
                              {:entity      eid
                               :entity-type :ord
                               :facet       :ord/status
                               :to          :ord.status/approved
                               :reason      "passed fraud check"})
    (let [db (d/db *conn*)]
      (is (= :ord.status/approved (sm/current-status db eid :ord/status)))
      (let [history (sm/status-history-of db eid)]
        (is (= 1 (count history)))
        (is (= :ord.status/created (-> history first :status-history/from)))
        (is (= :ord.status/approved (-> history first :status-history/to)))
        (is (= "passed fraud check" (-> history first :status-history/reason)))))))

(deftest record-status-change-rejects-illegal-transition
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  (d/transact *conn* [{:ord/code "O-2" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e :in $ ?c
                   :where [?e :ord/code ?c]]
                 (d/db *conn*) "O-2")
        eid (ffirst eid)]
    (is (thrown? Exception
                 (sm/record-status-change! *conn*
                                           {:entity      eid
                                            :entity-type :ord
                                            :facet       :ord/status
                                            :to          :ord.status/completed})))
    (testing "the entity's facet remains unchanged after rejection"
      (is (= :ord.status/created
             (sm/current-status (d/db *conn*) eid :ord/status))))))

(deftest record-status-change-history-respects-order
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  (d/transact *conn* [{:ord/code "O-3" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e .
                   :in $ ?c
                   :where [?e :ord/code ?c]]
                 (d/db *conn*) "O-3")]
    (sm/record-status-change! *conn*
                              {:entity      eid
                               :entity-type :ord
                               :facet       :ord/status
                               :to          :ord.status/approved
                               :changed-at  #inst "2026-01-01"
                               :reason      "first"})
    (sm/record-status-change! *conn*
                              {:entity      eid
                               :entity-type :ord
                               :facet       :ord/status
                               :to          :ord.status/completed
                               :changed-at  #inst "2026-02-01"
                               :reason      "second"})
    (let [history (sm/status-history-of (d/db *conn*) eid)]
      (is (= 2 (count history)))
      (is (= ["first" "second"] (map :status-history/reason history))
          "history is oldest-first by changed-at"))))
