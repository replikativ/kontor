(ns kontor.collections.schema-test
  "Schema-only tests for the kontor-collections companion — ADR-043
   commit 3/5. Verifies install! transacts cleanly, attrs are
   queryable, and the seeded state-transitions cover the documented
   lifecycle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (inv-schema/install! *conn*)
    (coll-schema/install! *conn*)
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Schema install
;; ============================================================================

;; NOTE: install! is NOT idempotent for :status-transition seeds —
;; the composite-tuple identity with :applies-to-org absent (= nil
;; in the tuple) doesn't upsert in datahike. Same caveat as
;; procurement / sales / invoice installs. Re-install once per DB.

(deftest core-attrs-are-installed
  (let [db (d/db *conn*)
        attr-known? (fn [k]
                      (some? (:db/valueType (d/pull db [:db/valueType] k))))]
    (is (attr-known? :kontor.collection-case/code))
    (is (attr-known? :kontor.collection-case/partner))
    (is (attr-known? :kontor.collection-case/state))
    (is (attr-known? :kontor.payment-promise/case))
    (is (attr-known? :kontor.payment-promise/status))
    (is (attr-known? :kontor.dispute/invoice))
    (is (attr-known? :kontor.dispute/state))
    (is (attr-known? :kontor.credit-hold/partner))
    (is (attr-known? :kontor.credit-hold/entity))
    (is (attr-known? :kontor.dunning-policy/code))
    (is (attr-known? :kontor.dunning-policy/levels))
    (is (attr-known? :kontor.dunning-event/case))
    (is (attr-known? :kontor.dunning-event/level))
    (is (attr-known? :kontor.dunning-pause/case))
    (is (attr-known? :kontor.invoice/collections-status))))

;; ============================================================================
;; State-machine seeds
;; ============================================================================

(deftest collection-case-transitions-seeded
  (let [db (d/db *conn*)]
    (testing "open → dunning-l1 legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :open :dunning-l1)))
    (testing "dunning-l1 → dunning-l2 legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :dunning-l1 :dunning-l2)))
    (testing "PTP suppression: open → promised legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :open :promised)))
    (testing "Promise broken: promised → open legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :promised :open)))
    (testing "Dispute suppression: open → disputed legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :open :disputed)))
    (testing "Final notice → legal escalation legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :final-notice :legal)))
    (testing "Legal → written-off legal"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :legal :written-off)))
    (testing "Closed paid from any active level"
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :open :paid))
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :dunning-l1 :paid))
      (is (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                :final-notice :paid)))
    (testing "Illegal transitions rejected"
      (is (not (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                     :written-off :open)))
      (is (not (sm/legal-transition? db :collection-case :kontor.collection-case/state
                                     :paid :open))))))

(deftest payment-promise-transitions-seeded
  (let [db (d/db *conn*)]
    (is (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                              :nil :open))
    (is (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                              :open :kept))
    (is (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                              :open :broken))
    (is (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                              :open :renegotiated))
    (is (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                              :broken :renegotiated))
    (testing "kept is terminal"
      (is (not (sm/legal-transition? db :payment-promise :kontor.payment-promise/status
                                     :kept :open))))))

(deftest dispute-transitions-seeded
  (let [db (d/db *conn*)]
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :nil :open))
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :open :under-review))
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :under-review :resolved))
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :open :resolved))
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :under-review :escalated))
    (is (sm/legal-transition? db :dispute :kontor.dispute/state :escalated :resolved))
    (testing "resolved is terminal"
      (is (not (sm/legal-transition? db :dispute :kontor.dispute/state
                                     :resolved :open))))))

(deftest invoice-collections-status-transitions-seeded
  (let [db (d/db *conn*)]
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :nil :current))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :current :overdue))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :overdue :in-collection))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :current :disputed))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :overdue :disputed))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :in-collection :disputed))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :disputed :overdue))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :in-collection :paid))
    (is (sm/legal-transition? db :invoice :kontor.invoice/collections-status
                              :in-collection :written-off))))

;; ============================================================================
;; Multi-facet on :invoice
;; ============================================================================

(deftest invoice-collections-status-is-independent-of-invoice-status
  ;; ADR-034 multi-facet: :invoice can carry independent state
  ;; machines for :kontor.invoice/status (the kernel lifecycle) AND
  ;; :kontor.invoice/collections-status (sales/AR collections workflow).
  ;; They don't interfere.
  (testing "An invoice can have both facets set"
    (d/transact *conn*
                [{:db/id "inv"
                  :kontor.invoice/external-id "INV-MF"
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/collections-status :overdue
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/currency "EUR"}])
    (let [db (d/db *conn*)
          inv (d/pull db [:kontor.invoice/collections-status :kontor.invoice/status]
                      [:kontor.invoice/external-id "INV-MF"])]
      (is (= :overdue (:kontor.invoice/collections-status inv)))
      (is (= :sent (:kontor.invoice/status inv))))))

;; ============================================================================
;; Seed counts (sanity)
;; ============================================================================

(deftest expected-seed-counts
  (let [db (d/db *conn*)]
    (testing "collection-case state machine"
      (is (= 20 (d/q '[:find (count ?t) .
                       :where [?t :kontor.status-transition/entity-type :collection-case]]
                     db))))
    (testing "payment-promise state machine"
      (is (= 6 (d/q '[:find (count ?t) .
                      :where [?t :kontor.status-transition/entity-type :payment-promise]]
                    db))))
    (testing "dispute state machine"
      (is (= 6 (d/q '[:find (count ?t) .
                      :where [?t :kontor.status-transition/entity-type :dispute]]
                    db))))
    (testing "invoice/collections-status facet"
      (is (= 12 (d/q '[:find (count ?t) .
                       :where
                       [?t :kontor.status-transition/entity-type :invoice]
                       [?t :kontor.status-transition/facet :kontor.invoice/collections-status]]
                     db))))))
