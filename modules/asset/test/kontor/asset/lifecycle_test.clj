(ns kontor.asset.lifecycle-test
  "ADR-053: kontor-asset register + lifecycle.

   Covers:
   - acquire! (:planned and :in-service?) writes the :asset + the
     nil → status row.
   - place-in-service! :planned → :in-service stamps :in-service-date.
   - dispose! records the :asset-event :disposal + drives status →
     :disposed; ADR-038 :no-self-approval + :requires-supporting-doc
     fire via the status transition.
   - impair! / revalue! record the :asset-event, keep status
     :in-service, enforce inline :justification + :reason-note guards.
   - transfer! → :transferred + re-points :asset/entity.
   - revise-useful-life! records the event.
   - componentisation via :asset/parent.
   - events-of returns events ordered by date."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 ;; Actors — :create/uid is :db.type/ref, reuse :partner
                 ;; entities as actor stand-ins (the kernel convention).
                 {:kontor.partner/external-id "U-buyer"    :kontor.partner/name "Asset Buyer"}
                 {:kontor.partner/external-id "U-manager"  :kontor.partner/name "Asset Manager"}
                 ;; GL accounts for the asset's three legs (ADR-054 posts to them).
                 {:db/id "acct-machinery"
                  :account/code "0210" :account/name "Machinery"
                  :account/type :asset :account/active true}
                 {:db/id "acct-accum"
                  :account/code "0299" :account/name "Accumulated Depreciation"
                  :account/type :asset :account/active true}
                 {:db/id "acct-dep-expense"
                  :account/code "6220" :account/name "Depreciation Expense"
                  :account/type :expense :account/active true}
                 ;; Asset class.
                 {:db/id "class-machinery"
                  :asset-class/code "machinery"
                  :asset-class/name "Machinery & Equipment"
                  :asset-class/default-useful-life-months 120}
                 ;; A second legal entity for the transfer test.
                 {:db/id "entity-sub"
                  :kontor.entity/code "SUB-DE" :kontor.entity/name "Subsidiary GmbH"}
                 ;; Supporting docs.
                 {:db/id "doc-invoice"
                  :audit-doc/code "ASSET-INV-001"
                  :audit-doc/type :acquisition-invoice
                  :audit-doc/storage-uri "s3://docs/asset-inv-001"
                  :audit-doc/uploaded-at #inst "2026-01-15"}
                 {:db/id "doc-disposal"
                  :audit-doc/code "ASSET-DISPOSAL-001"
                  :audit-doc/type :disposal-authorisation
                  :audit-doc/storage-uri "s3://docs/asset-disposal-001"
                  :audit-doc/uploaded-at #inst "2026-09-01"}
                 {:db/id "doc-impair"
                  :audit-doc/code "ASSET-IMPAIR-001"
                  :audit-doc/type :impairment-test-memo
                  :audit-doc/storage-uri "s3://docs/asset-impair-001"
                  :audit-doc/uploaded-at #inst "2026-06-30"}])
    conn))

(defn- uid [db actor]
  (d/q '[:find ?e . :in $ ?x :where [?e :kontor.partner/external-id ?x]]
       db (str "U-" actor)))

(defn- ref-eid [db tempid-attr v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db tempid-attr v))

(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- adoc [db code] (ref-eid db :audit-doc/code code))
(defn- acct [db code] (ref-eid db :account/code code))
(defn- entity-eid [db code] (ref-eid db :kontor.entity/code code))
(defn- class-eid [db code] (ref-eid db :asset-class/code code))

;; Acquire a standard in-service machinery asset; returns the eid.
(defn- acquire-machine! [conn code]
  (let [db (d/db conn)]
    (asset/acquire! conn
                    {:code code
                     :name (str "Machine " code)
                     :class (class-eid db "machinery")
                     :acquisition-cost 120000.00M
                     :acquisition-commodity (commodity db)
                     :acquisition-date #inst "2026-01-15"
                     :in-service? true
                     :asset-account (acct db "0210")
                     :accumulated-account (acct db "0299")
                     :expense-account (acct db "6220")
                     :origin-document (adoc db "ASSET-INV-001")
                     :changed-by-uid (uid db "buyer")})
    (asset/by-code (d/db conn) code)))

;; ============================================================================
;; Acquisition
;; ============================================================================

(deftest acquire-planned-then-place-in-service
  (let [conn (bootstrap)
        db (d/db conn)
        _ (asset/acquire! conn
                          {:code "MACH-PLAN"
                           :name "Planned Machine"
                           :class (class-eid db "machinery")
                           :acquisition-cost 50000.00M
                           :acquisition-commodity (commodity db)
                           :acquisition-date #inst "2026-02-01"
                           :changed-by-uid (uid db "buyer")})
        a (asset/pull-asset (d/db conn) "MACH-PLAN")]
    (testing "acquire! defaults to :planned, no :in-service-date"
      (is (= :planned (:asset/status a)))
      (is (nil? (:asset/in-service-date a))))
    (testing "a status-history row records nil → :planned"
      (is (= 1 (count (d/q '[:find [?h ...]
                             :in $ ?e
                             :where
                             [?h :status-history/entity ?e]
                             [?h :status-history/facet :asset/status]]
                           (d/db conn) (asset/by-code (d/db conn) "MACH-PLAN"))))))
    (testing "place-in-service! → :in-service, stamps :in-service-date"
      (asset/place-in-service! conn
                               {:asset "MACH-PLAN"
                                :in-service-date #inst "2026-02-15"
                                :changed-by-uid (uid (d/db conn) "manager")})
      (let [a' (asset/pull-asset (d/db conn) "MACH-PLAN")]
        (is (= :in-service (:asset/status a')))
        (is (= #inst "2026-02-15" (:asset/in-service-date a')))))))

(deftest acquire-in-service-stamps-in-service-date
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-001")
        a (asset/pull-asset (d/db conn) "MACH-001")]
    (is (= :in-service (:asset/status a)))
    (is (= #inst "2026-01-15" (:asset/in-service-date a))
        ":in-service-date defaults to :acquisition-date when :in-service?")
    (is (= 120000.00M (:asset/acquisition-cost a)))))

;; ============================================================================
;; Disposal — governed by the status machine (ADR-038)
;; ============================================================================

(deftest dispose-records-event-and-drives-status
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-DISP")]
    (testing "dispose! by a different actor with the authorisation succeeds"
      (asset/dispose! conn
                      {:asset "MACH-DISP"
                       :date #inst "2026-09-15"
                       :changed-by-uid (uid (d/db conn) "manager")  ; ≠ buyer
                       :justification (adoc (d/db conn) "ASSET-DISPOSAL-001")
                       :proceeds 30000.00M
                       :commodity (commodity (d/db conn))
                       :reason-note "Sold to scrap dealer."})
      (let [a (asset/pull-asset (d/db conn) "MACH-DISP")
            events (asset/events-of (d/db conn) "MACH-DISP")]
        (is (= :disposed (:asset/status a)))
        (is (= 1 (count events)))
        (is (= :disposal (:asset-event/kind (first events))))
        (is (= 30000.00M (:asset-event/amount (first events))))))))

(deftest dispose-by-acquirer-rejected
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-SELF")]
    (testing "the acquirer cannot dispose their own asset (:no-self-approval)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (asset/dispose! conn
                           {:asset "MACH-SELF"
                            :date #inst "2026-09-15"
                            :changed-by-uid (uid (d/db conn) "buyer")  ; = acquirer!
                            :justification (adoc (d/db conn) "ASSET-DISPOSAL-001")
                            :reason-note "Trying to self-dispose."}))))))

(deftest dispose-without-justification-rejected
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-NODOC")]
    (testing "dispose! without :justification is rejected (inline guard)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":justification required"
           (asset/dispose! conn
                           {:asset "MACH-NODOC"
                            :date #inst "2026-09-15"
                            :changed-by-uid (uid (d/db conn) "manager")
                            :reason-note "No doc."}))))))

;; ============================================================================
;; In-service events — impair / revalue / revise-useful-life
;; ============================================================================

(deftest impair-records-event-keeps-in-service
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-IMP")]
    (asset/impair! conn
                   {:asset "MACH-IMP"
                    :date #inst "2026-06-30"
                    :amount 15000.00M
                    :commodity (commodity (d/db conn))
                    :justification (adoc (d/db conn) "ASSET-IMPAIR-001")
                    :reason-note "Recoverable amount below carrying value — market downturn."})
    (let [a (asset/pull-asset (d/db conn) "MACH-IMP")
          events (asset/events-of (d/db conn) "MACH-IMP")]
      (testing "asset stays :in-service after impairment"
        (is (= :in-service (:asset/status a))))
      (testing "the :impairment :asset-event is recorded"
        (is (= 1 (count events)))
        (is (= :impairment (:asset-event/kind (first events))))
        (is (= 15000.00M (:asset-event/amount (first events))))))))

(deftest impair-without-justification-rejected
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-IMP2")]
    (testing "impair! without :justification is rejected (inline guard)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":justification required"
           (asset/impair! conn
                          {:asset "MACH-IMP2"
                           :date #inst "2026-06-30"
                           :amount 15000.00M
                           :commodity (commodity (d/db conn))
                           :reason-note "No memo."}))))
    (testing "impair! without :reason-note is rejected (inline guard)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":reason-note required"
           (asset/impair! conn
                          {:asset "MACH-IMP2"
                           :date #inst "2026-06-30"
                           :amount 15000.00M
                           :commodity (commodity (d/db conn))
                           :justification (adoc (d/db conn) "ASSET-IMPAIR-001")}))))))

(deftest revise-useful-life-records-event
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-LIFE")]
    (asset/revise-useful-life! conn
                               {:asset "MACH-LIFE"
                                :date #inst "2026-12-31"
                                :new-useful-life-months 84
                                :changed-by-uid (uid (d/db conn) "manager")
                                :reason-note "Annual IAS 16 review — lifespan extended."})
    (let [events (asset/events-of (d/db conn) "MACH-LIFE")]
      (is (= :useful-life-revision (:asset-event/kind (first events))))
      (is (= 84 (:asset-event/new-useful-life-months (first events))))
      (is (= :in-service (:asset/status (asset/pull-asset (d/db conn) "MACH-LIFE")))))))

;; ============================================================================
;; Transfer
;; ============================================================================

(deftest transfer-changes-status-and-entity
  (let [conn (bootstrap)
        _ (acquire-machine! conn "MACH-XFER")]
    (asset/transfer! conn
                     {:asset "MACH-XFER"
                      :date #inst "2026-07-01"
                      :changed-by-uid (uid (d/db conn) "manager")
                      :to-entity (entity-eid (d/db conn) "SUB-DE")
                      :reason-note "Intercompany transfer to subsidiary."})
    (let [a (asset/pull-asset (d/db conn) "MACH-XFER")]
      (is (= :transferred (:asset/status a)))
      (is (= (entity-eid (d/db conn) "SUB-DE")
             (:db/id (:asset/entity a))))
      (is (= :transfer (:asset-event/kind
                        (first (asset/events-of (d/db conn) "MACH-XFER"))))))))

;; ============================================================================
;; Componentisation — :asset/parent
;; ============================================================================

(deftest componentisation-via-asset-parent
  (let [conn (bootstrap)
        whole (acquire-machine! conn "BUILDING-1")
        db (d/db conn)
        ;; A component is just an :asset whose :parent points at the whole.
        _ (asset/acquire! conn
                          {:code "BUILDING-1-ROOF"
                           :name "Roof component"
                           :class (class-eid db "machinery")
                           :acquisition-cost 30000.00M
                           :acquisition-commodity (commodity db)
                           :acquisition-date #inst "2026-01-15"
                           :in-service? true
                           :parent whole
                           :changed-by-uid (uid db "buyer")})
        component (asset/pull-asset (d/db conn) "BUILDING-1-ROOF")]
    (testing "the component references the whole via :asset/parent"
      (is (= whole (:db/id (:asset/parent component)))))
    (testing "both are independent :in-service assets"
      (is (= :in-service (:asset/status component)))
      (is (= :in-service (:asset/status (asset/pull-asset (d/db conn) "BUILDING-1")))))))
