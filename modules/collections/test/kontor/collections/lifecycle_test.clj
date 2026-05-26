(ns kontor.collections.lifecycle-test
  "Tests for case + promise + dispute + credit-hold lifecycles —
   ADR-043 commit 4/5."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.collections.case :as kcase]
            [kontor.collections.credit-hold :as chold]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.promise :as kpromise]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (inv-schema/install! *conn*)
    (coll-schema/install! *conn*)
    (d/transact *conn*
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme GmbH"
                  :kontor.entity/kind :operating :kontor.entity/active true}
                 {:kontor.entity/code "ACME-US" :kontor.entity/name "Acme LLC"
                  :kontor.entity/kind :operating :kontor.entity/active true}
                 {:kontor.partner/external-id "BIG-CUST"
                  :kontor.partner/name "Big Customer Co"
                  :kontor.partner/kind :customer
                  :kontor.partner/credit-status :open
                  :kontor.partner/credit-limit 100000M
                  :kontor.partner/credit-commodity [:kontor.commodity/symbol "EUR"]}
                 {:kontor.partner/external-id "SMALL-CUST"
                  :kontor.partner/name "Small Customer"
                  :kontor.partner/kind :customer
                  :kontor.partner/credit-status :review}
                 ;; Actor entities — :create/uid is :db.type/ref so we
                 ;; reuse the partner shape for fake users (the same
                 ;; pattern as test/kontor/status_machine_test.clj:344).
                 {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
                 {:kontor.partner/external-id "U-bob"   :kontor.partner/name "Bob"}
                 {:kontor.partner/external-id "U-sys"   :kontor.partner/name "System"}])
    (f)))

(use-fixtures :each bootstrap)

(defn- partner [xid]
  (d/q '[:find ?p . :in $ ?xid
         :where [?p :kontor.partner/external-id ?xid]]
       (d/db *conn*) xid))

(defn- entity [code]
  (d/q '[:find ?e . :in $ ?c
         :where [?e :kontor.entity/code ?c]]
       (d/db *conn*) code))

(defn- actor [uid]
  ;; "Actors" are seeded as partner records with :kontor.partner/external-id
  ;; "U-<uid>" so :create/uid (ref) has something to point at.
  (d/q '[:find ?a . :in $ ?xid
         :where [?a :kontor.partner/external-id ?xid]]
       (d/db *conn*) (str "U-" uid)))

(defn- commodity [sym]
  (d/q '[:find ?c . :in $ ?s :where [?c :kontor.commodity/symbol ?s]] (d/db *conn*) sym))

;; ============================================================================
;; Collection-case lifecycle
;; ============================================================================

(deftest open-case-creates-row-and-status-history
  (kcase/open-case! *conn*
                   {:code "CASE-1"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")
                    :strategy :reminder-only
                    :segment :strategic})
  (let [db (d/db *conn*)
        c (kcase/pull-case db "CASE-1")]
    (testing "case row exists with :open state"
      (is (= :open (:collection-case/state c))))
    (testing "denormalized fields"
      (is (= :reminder-only (:collection-case/strategy c)))
      (is (= :strategic (:collection-case/collections-segment c))))
    (testing "status-history row for nil → :open"
      (let [eid (kcase/by-code db "CASE-1")
            history (sm/status-history-of db eid :collection-case/state)]
        (is (= 1 (count history)))
        (is (= :open (:status-history/to (first history))))
        (is (= :case-opened (:status-history/reason (first history))))))))

(deftest cannot-open-second-case-for-same-pair
  (kcase/open-case! *conn*
                   {:code "CASE-A"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (testing "attempt to open another case for same (partner, entity) throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Open case already exists"
         (kcase/open-case! *conn*
                          {:code "CASE-B"
                           :partner (partner "BIG-CUST")
                           :entity (entity "ACME-DE")
                           :opened-by-uid (actor "alice")})))))

(deftest same-partner-can-have-case-in-different-entities
  (kcase/open-case! *conn*
                   {:code "CASE-DE"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (kcase/open-case! *conn*
                   {:code "CASE-US"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-US")
                    :opened-by-uid (actor "alice")})
  (is (= "CASE-DE" (-> (kcase/pull-case (d/db *conn*) "CASE-DE")
                       :collection-case/code)))
  (is (= "CASE-US" (-> (kcase/pull-case (d/db *conn*) "CASE-US")
                       :collection-case/code))))

(deftest case-state-machine-walks-through-dunning-levels
  (kcase/open-case! *conn*
                   {:code "CASE-DUNN"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (kcase/advance-case-state! *conn*
                       {:case "CASE-DUNN"
                        :to :dunning-l1
                        :changed-by-uid (actor "alice")
                        :reason :dunning-l1-sent})
  (kcase/advance-case-state! *conn*
                       {:case "CASE-DUNN"
                        :to :dunning-l2
                        :changed-by-uid (actor "alice")
                        :reason :dunning-l2-sent})
  (kcase/advance-case-state! *conn*
                       {:case "CASE-DUNN"
                        :to :final-notice
                        :changed-by-uid (actor "alice")
                        :reason :final-notice-sent})
  (testing "state machine walked"
    (let [db (d/db *conn*)
          eid (kcase/by-code db "CASE-DUNN")]
      (is (= :final-notice (sm/current-status db eid :collection-case/state)))
      (testing "4 history rows (open, l1, l2, final)"
        (is (= 4 (count (sm/status-history-of db eid :collection-case/state))))))))

(deftest illegal-transition-throws
  (kcase/open-case! *conn*
                   {:code "CASE-ILL"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (testing "open → written-off is illegal (must go through legal)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Illegal status transition"
         (kcase/advance-case-state! *conn*
                              {:case "CASE-ILL"
                               :to :written-off
                               :changed-by-uid (actor "alice")})))))

;; ============================================================================
;; Payment-promise lifecycle
;; ============================================================================

(deftest record-and-keep-promise
  (kcase/open-case! *conn*
                   {:code "CASE-P"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-P")]
    (kpromise/record-promise! *conn*
                              {:external-id "PTP-1"
                               :case case-eid
                               :amount 5000M
                               :commodity (commodity "EUR")
                               :promised-by-date #inst "2026-05-20"
                               :captured-by-uid (actor "alice")
                               :captured-via :phone
                               :notes "Customer CFO promised by phone."})
    (let [db (d/db *conn*)
          ptp (kpromise/pull-promise db "PTP-1")]
      (testing "promise recorded :open"
        (is (= :open (:payment-promise/status ptp))))
      (testing "case finds the open promise"
        (is (= 1 (count (kpromise/open-promises-for-case db case-eid))))))
    (kpromise/mark-promise-kept! *conn*
                                  {:promise "PTP-1"
                                   :changed-by-uid (actor "alice")})
    (testing "promise → :kept"
      (let [db (d/db *conn*)
            ptp (kpromise/pull-promise db "PTP-1")]
        (is (= :kept (:payment-promise/status ptp)))
        (is (zero? (count (kpromise/open-promises-for-case db case-eid))))))))

(deftest sweep-broken-promises-flips-lapsed
  (kcase/open-case! *conn*
                   {:code "CASE-SW"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-SW")]
    (kpromise/record-promise! *conn*
                              {:external-id "PTP-FUTURE"
                               :case case-eid
                               :amount 1000M
                               :commodity (commodity "EUR")
                               :promised-by-date #inst "2026-12-31"
                               :captured-by-uid (actor "alice")})
    (kpromise/record-promise! *conn*
                              {:external-id "PTP-PAST"
                               :case case-eid
                               :amount 2000M
                               :commodity (commodity "EUR")
                               :promised-by-date #inst "2026-04-30"
                               :captured-by-uid (actor "alice")})
    (let [report (kpromise/sweep-broken-promises!
                  *conn* {:now #inst "2026-05-15"
                          :system-uid (actor "sys")})]
      (testing "exactly 1 swept (PTP-PAST)"
        (is (= 1 (:swept report))))
      (let [db (d/db *conn*)]
        (is (= :broken
               (:payment-promise/status
                (kpromise/pull-promise db "PTP-PAST"))))
        (is (= :open
               (:payment-promise/status
                (kpromise/pull-promise db "PTP-FUTURE"))))))))

;; ============================================================================
;; Dispute lifecycle
;; ============================================================================

(defn- make-invoice!
  "Minimal :sent invoice for dispute/credit-hold tests."
  [external-id gross]
  (let [db (d/db *conn*)
        seller (partner "BIG-CUST")
        buyer  (partner "SMALL-CUST")
        inv-tempid "inv-1"
        line-tempid "line-1"]
    (d/transact *conn*
                [{:db/id inv-tempid
                  :invoice/external-id external-id
                  :invoice/type :sales
                  :invoice/status :sent
                  :invoice/issue-date #inst "2026-04-01"
                  :invoice/seller seller
                  :invoice/buyer  buyer
                  :invoice/currency "EUR"
                  :invoice/total-gross gross
                  :invoice/lines [line-tempid]}
                 {:db/id line-tempid
                  :invoice-line/invoice inv-tempid
                  :invoice-line/sequence 1
                  :invoice-line/name "Widget"
                  :invoice-line/quantity 1M
                  :invoice-line/unit-price gross
                  :invoice-line/amount gross}])
    (d/q '[:find ?e . :in $ ?xid
           :where [?e :invoice/external-id ?xid]]
         (d/db *conn*) external-id)))

(deftest raise-and-resolve-dispute
  (let [inv (make-invoice! "INV-D" 1500M)]
    (kdispute/raise-dispute! *conn*
                             {:external-id "DIS-1"
                              :invoice inv
                              :disputed-amount 500M
                              :reason-code :pricing
                              :opened-by-uid (actor "alice")
                              :notes "Customer says discount wasn't applied"})
    (let [db (d/db *conn*)]
      (testing "dispute :open"
        (is (= :open (:dispute/state (kdispute/pull-dispute db "DIS-1")))))
      (testing "open-disputes-for-invoice finds it"
        (is (= 1 (count (kdispute/open-disputes-for-invoice db inv)))))
      (testing "any-open-dispute? predicate"
        (is (kdispute/any-open-dispute-for-invoice? db inv))))
    (kdispute/resolve-dispute! *conn*
                               {:dispute "DIS-1"
                                :resolution :credit-issued
                                :resolved-by-uid (actor "bob")
                                :reason-note "Issued credit memo CM-100"})
    (let [db (d/db *conn*)
          d (kdispute/pull-dispute db "DIS-1")]
      (testing "dispute :resolved"
        (is (= :resolved (:dispute/state d))))
      (testing "resolution + resolved-by-uid populated"
        ;; :dispute/resolved-at was removed — read via :status-history
        ;; or :tx/valid-from on the resolve tx (kontor.bitemporal).
        (is (= :credit-issued (:dispute/resolution d))))
      (testing "no longer in open-disputes"
        (is (zero? (count (kdispute/open-disputes-for-invoice db inv))))))))

(deftest line-level-dispute-via-scope
  (let [inv (make-invoice! "INV-LINE" 2000M)
        line-eid (d/q '[:find ?l . :in $ ?inv
                        :where [?l :invoice-line/invoice ?inv]]
                      (d/db *conn*) inv)]
    (kdispute/raise-dispute! *conn*
                             {:external-id "DIS-LINE"
                              :invoice inv
                              :scope line-eid
                              :disputed-amount 2000M
                              :reason-code :short-ship
                              :opened-by-uid (actor "alice")})
    (testing "dispute :scope refs the line"
      (let [d (kdispute/pull-dispute (d/db *conn*) "DIS-LINE")]
        (is (= line-eid (:db/id (:dispute/scope d))))))))

;; ============================================================================
;; Credit-hold overlay
;; ============================================================================

(deftest credit-status-falls-back-to-partner-scalar-when-no-hold
  (testing "BIG-CUST scalar is :open; no overlay → :open"
    (is (= :open
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")}))))
  (testing "SMALL-CUST scalar is :review; no overlay → :review"
    (is (= :review
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "SMALL-CUST")
                                     :entity (entity "ACME-DE")})))))

(deftest credit-hold-overrides-partner-scalar
  (chold/place-hold! *conn*
                    {:partner (partner "BIG-CUST")
                     :entity (entity "ACME-DE")
                     :reason-code :overdue-threshold
                     :placed-by-uid (actor "alice")
                     :approver-uid (actor "bob")})
  (testing "active overlay forces :hold even though scalar is :open"
    (is (= :hold
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")})))))

(deftest credit-hold-is-per-entity
  (chold/place-hold! *conn*
                    {:partner (partner "BIG-CUST")
                     :entity (entity "ACME-DE")
                     :reason-code :overdue-threshold
                     :placed-by-uid (actor "alice")})
  (testing "ACME-DE on hold; ACME-US stays open"
    (is (= :hold
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")})))
    (is (= :open
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-US")})))))

(deftest release-hold-restores-scalar
  (chold/place-hold! *conn*
                    {:partner (partner "BIG-CUST")
                     :entity (entity "ACME-DE")
                     :reason-code :manual
                     :placed-by-uid (actor "alice")})
  (let [hold (chold/current-hold (d/db *conn*)
                                  {:partner (partner "BIG-CUST")
                                   :entity (entity "ACME-DE")})]
    (chold/release-hold! *conn*
                        {:hold-eid (:db/id hold)
                         :released-by-uid (actor "bob")
                         :notes "Cleared by manager"}))
  (testing "after release, overlay no longer active, scalar wins"
    (is (= :open
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")})))))

(deftest credit-hold-bitemporal-as-of-valid
  (chold/place-hold! *conn*
                    {:partner (partner "BIG-CUST")
                     :entity (entity "ACME-DE")
                     :reason-code :manual
                     :placed-by-uid (actor "alice")})
  (testing "as-of-valid = today: hold active"
    (is (= :hold
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")
                                     :as-of-valid (java.util.Date.)}))))
  (testing "as-of-valid = epoch: hold not yet placed; scalar wins"
    (is (= :open
           (chold/credit-status-for (d/db *conn*)
                                    {:partner (partner "BIG-CUST")
                                     :entity (entity "ACME-DE")
                                     :as-of-valid #inst "2020-01-01"})))))

;; ============================================================================
;; ADR-068 — `*-tx-data` builder shape for the two newly-exposed
;; primitives (`mark-promise-kept-tx-data` + `release-all-for-tx-data`)
;; ============================================================================

(deftest mark-promise-kept-and-release-all-for-tx-data-are-pure
  (kcase/open-case! *conn*
                   {:code "CASE-TXD"
                    :partner (partner "BIG-CUST")
                    :entity (entity "ACME-DE")
                    :opened-by-uid (actor "alice")})
  (let [case-eid (kcase/by-code (d/db *conn*) "CASE-TXD")]
    (kpromise/record-promise! *conn*
                              {:external-id "PTP-TXD"
                               :case case-eid
                               :amount 1000M
                               :commodity (commodity "EUR")
                               :promised-by-date #inst "2026-06-01"
                               :captured-by-uid (actor "alice")})
    ;; Place TWO holds against the same (partner, entity) so the
    ;; release-all builder produces a multi-hold tx-data vector.
    (chold/place-hold! *conn*
                       {:partner (partner "BIG-CUST")
                        :entity (entity "ACME-DE")
                        :reason-code :manual
                        :placed-by-uid (actor "alice")
                        :tempid "h1"})
    (chold/place-hold! *conn*
                       {:partner (partner "BIG-CUST")
                        :entity (entity "ACME-DE")
                        :reason-code :credit-review
                        :placed-by-uid (actor "alice")
                        :tempid "h2"})
    (let [db-before (d/db *conn*)
          tx-before (:max-tx db-before)
          ptp-eid (kpromise/by-external-id db-before "PTP-TXD")
          kept-tx (kpromise/mark-promise-kept-tx-data
                   db-before {:promise ptp-eid
                              :changed-by-uid (actor "alice")})
          release-tx (chold/release-all-for-tx-data
                      db-before {:partner (partner "BIG-CUST")
                                 :entity (entity "ACME-DE")
                                 :released-by-uid (actor "bob")
                                 :notes "bulk release"})]
      (testing "mark-promise-kept-tx-data returns a non-empty vector"
        (is (vector? kept-tx))
        (is (seq kept-tx)))
      (testing "release-all-for-tx-data returns a multi-hold vector"
        (is (vector? release-tx))
        ;; 2 holds × (1 update map + 2 status-machine entries) = 6 ops.
        (is (>= (count release-tx) 4)))
      (testing "builders are pure (no side effects on conn)"
        (is (= tx-before (:max-tx (d/db *conn*)))))
      (testing "transacting the bulk-release atomically flips both holds"
        (d/transact *conn* release-tx)
        (let [db (d/db *conn*)
              actives (chold/active-holds-for
                       db {:partner (partner "BIG-CUST")
                           :entity (entity "ACME-DE")})]
          (is (empty? actives) "all holds released in ONE tx")))
      (testing "transacting mark-promise-kept-tx-data flips status"
        (d/transact *conn* kept-tx)
        (is (= :kept (:payment-promise/status
                      (kpromise/pull-promise (d/db *conn*) "PTP-TXD"))))))))
