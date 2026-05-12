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
                               :reason      :approved
                               :reason-note "passed fraud check"})
    (let [db (d/db *conn*)]
      (is (= :ord.status/approved (sm/current-status db eid :ord/status)))
      (let [history (sm/status-history-of db eid)]
        (is (= 1 (count history)))
        (is (= :ord.status/created (-> history first :status-history/from)))
        (is (= :ord.status/approved (-> history first :status-history/to)))
        (is (= :approved (-> history first :status-history/reason)))
        (is (= "passed fraud check" (-> history first :status-history/reason-note)))))))

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

;; ============================================================================
;; ADR-038 — Audit + governance primitives
;; ============================================================================

(deftest reason-as-keyword
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  (d/transact *conn* [{:ord/code "O-REASON" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e . :where [?e :ord/code "O-REASON"]] (d/db *conn*))]
    (sm/record-status-change! *conn*
                              {:entity eid
                               :entity-type :ord
                               :facet :ord/status
                               :to :ord.status/approved
                               :reason :approved
                               :reason-note "passes fraud check"})
    (let [history (sm/status-history-of (d/db *conn*) eid)]
      (testing "reason stored as keyword"
        (is (= :approved (-> history first :status-history/reason))))
      (testing "reason-note stored as separate string field"
        (is (= "passes fraud check"
               (-> history first :status-history/reason-note)))))))

(deftest other-reason-requires-reason-note
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  (d/transact *conn* [{:ord/code "O-OTHER" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e . :where [?e :ord/code "O-OTHER"]] (d/db *conn*))]
    (testing ":reason :other without :reason-note is rejected"
      (is (thrown? Exception
                   (sm/record-status-change! *conn*
                                             {:entity eid
                                              :entity-type :ord
                                              :facet :ord/status
                                              :to :ord.status/approved
                                              :reason :other}))))
    (testing ":reason :other with empty :reason-note also rejected"
      (is (thrown? Exception
                   (sm/record-status-change! *conn*
                                             {:entity eid
                                              :entity-type :ord
                                              :facet :ord/status
                                              :to :ord.status/approved
                                              :reason :other
                                              :reason-note ""}))))
    (testing ":reason :other with non-empty :reason-note succeeds"
      (sm/record-status-change! *conn*
                                {:entity eid
                                 :entity-type :ord
                                 :facet :ord/status
                                 :to :ord.status/approved
                                 :reason :other
                                 :reason-note "an unusual case"}))))

(deftest no-self-approval-policy
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  ;; Seed an :no-self-approval policy on :ord.status/created → :approved
  (d/transact *conn*
              [{:approval-policy/entity-type :ord
                :approval-policy/facet :ord/status
                :approval-policy/transition-from :ord.status/created
                :approval-policy/transition-to :ord.status/approved
                :approval-policy/rule :no-self-approval
                :approval-policy/active true}])
  ;; Seed two opaque user entities via partner records (which exist
  ;; in the kernel schema with :partner/external-id identity).
  (d/transact *conn*
              [{:partner/external-id "U-alice" :partner/name "Alice"}
               {:partner/external-id "U-bob"   :partner/name "Bob"}])
  (let [alice (d/q '[:find ?u . :in $ ?id
                     :where [?u :partner/external-id ?id]]
                   (d/db *conn*) "U-alice")
        bob (d/q '[:find ?u . :in $ ?id
                   :where [?u :partner/external-id ?id]]
                 (d/db *conn*) "U-bob")
        _ (d/transact *conn* [{:ord/code "O-SELF"
                               :ord/status :ord.status/created
                               :create/uid alice}])
        eid (d/q '[:find ?e . :where [?e :ord/code "O-SELF"]] (d/db *conn*))]
    (testing "self-approval rejected"
      (is (thrown-with-msg? Exception #"Approval-policy violation"
                            (sm/record-status-change!
                             *conn*
                             {:entity eid
                              :entity-type :ord
                              :facet :ord/status
                              :to :ord.status/approved
                              :changed-by-uid alice
                              :reason :approved}))))
    (testing "different actor succeeds"
      (sm/record-status-change!
       *conn*
       {:entity eid
        :entity-type :ord
        :facet :ord/status
        :to :ord.status/approved
        :changed-by-uid bob
        :reason :approved}))))

(deftest requires-supporting-doc-policy
  (install-ord-attr! *conn*)
  (seed-ord-status-transitions! *conn*)
  ;; Seed a policy requiring :supporting-doc on cancel
  (d/transact *conn*
              [{:approval-policy/entity-type :ord
                :approval-policy/facet :ord/status
                :approval-policy/transition-from :ord.status/created
                :approval-policy/transition-to :ord.status/cancelled
                :approval-policy/rule :requires-supporting-doc
                :approval-policy/active true}])
  (d/transact *conn* [{:ord/code "O-DOC" :ord/status :ord.status/created}])
  (let [eid (d/q '[:find ?e . :where [?e :ord/code "O-DOC"]] (d/db *conn*))]
    (testing "transition without :supporting-doc is rejected"
      (is (thrown-with-msg? Exception #"Approval-policy violation"
                            (sm/record-status-change!
                             *conn*
                             {:entity eid
                              :entity-type :ord
                              :facet :ord/status
                              :to :ord.status/cancelled
                              :reason :customer-request}))))
    (testing "with :supporting-doc succeeds"
      ;; Seed an :audit-doc and reference it
      (d/transact *conn*
                  [{:audit-doc/code "DOC-1"
                    :audit-doc/type :customer-email
                    :audit-doc/storage-uri "s3://test-bucket/doc-1.eml"
                    :audit-doc/uploaded-at #inst "2026-05-01"
                    :audit-doc/title "Customer cancellation request"}])
      (let [doc-eid (d/q '[:find ?d . :where [?d :audit-doc/code "DOC-1"]]
                         (d/db *conn*))]
        (sm/record-status-change!
         *conn*
         {:entity eid
          :entity-type :ord
          :facet :ord/status
          :to :ord.status/cancelled
          :reason :customer-request
          :supporting-doc doc-eid})
        (testing ":supporting-doc ref captured in history row"
          (let [history (sm/status-history-of (d/db *conn*) eid)
                last-row (last history)]
            (is (= doc-eid (-> last-row :status-history/supporting-doc :db/id)))))))))

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
                               :reason      :other
                               :reason-note "first"})
    (sm/record-status-change! *conn*
                              {:entity      eid
                               :entity-type :ord
                               :facet       :ord/status
                               :to          :ord.status/completed
                               :changed-at  #inst "2026-02-01"
                               :reason      :other
                               :reason-note "second"})
    (let [history (sm/status-history-of (d/db *conn*) eid)]
      (is (= 2 (count history)))
      (is (= ["first" "second"] (map :status-history/reason-note history))
          "history is oldest-first by changed-at"))))
