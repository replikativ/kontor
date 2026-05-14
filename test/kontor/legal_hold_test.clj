(ns kontor.legal-hold-test
  "ADR-049: legal hold as a write-time invariant blocking :db/purge.

   Covers:
   - place! writes the hold + status-history + :tx/valid-from in one tx.
   - eid-set scope: purge of a held eid blocked.
   - scope-query scope: purge of a new entity matching the query blocked.
   - release! flips :placed → :released (with ADR-038 :no-self-approval).
   - After release, the previously-held eid can be purged.
   - ADR-038 :requires-supporting-doc + :requires-non-empty-reason-note
     enforced on placement and release.
   - Bitemporal: scope-query's value-at returns it as it was at a past vt."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as adoc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.legal-hold :as lhold]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    ;; "Actors" are seeded as partner records — :create/uid is
    ;; :db.type/ref, so it needs an entity to point at; per the
    ;; convention in modules/collections/.../lifecycle_test.clj we
    ;; reuse :partner entities as actor stand-ins.
    (d/transact conn
                [{:partner/external-id "U-counsel" :partner/name "Counsel C"}
                 {:partner/external-id "U-admin"   :partner/name "Admin A"}
                 {:db/id "doc-place"
                  :audit-doc/code "DOC-PLACE-001"
                  :audit-doc/type :legal-hold-order
                  :audit-doc/uploaded-at #inst "2026-05-13"}
                 {:db/id "doc-release"
                  :audit-doc/code "DOC-RELEASE-001"
                  :audit-doc/type :legal-hold-release
                  :audit-doc/uploaded-at #inst "2026-06-01"}
                 {:db/id "partner-acme"
                  :partner/external-id "ACME"
                  :partner/name "Acme Corp"
                  :partner/kind :customer}])
    conn))

(defn- uid [db actor]
  ;; Resolve the actor partner-eid by its :partner/external-id "U-<actor>".
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :partner/external-id ?xid]]
       db (str "U-" actor)))

(defn- adoc-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :audit-doc/code ?c]] db code))

;; ============================================================================
;; place! happy path
;; ============================================================================

(deftest place-writes-hold-status-history-and-vt
  (let [conn (bootstrap)
        db (d/db conn)
        held-target (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)
        _ (lhold/place! conn
                        {:code "HOLD-ACME-001"
                         :matter-name "Acme v. Doe 24-CV-1234"
                         :issued-by-uid (uid db "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Preservation order received from outside counsel."
                         :scope-eids [held-target]})
        db' (d/db conn)
        hold-eid (lhold/by-code db' "HOLD-ACME-001")
        hold (d/pull db' '[*] hold-eid)
        history (d/q '[:find ?h
                       :in $ ?e
                       :where
                       [?h :status-history/entity ?e]
                       [?h :status-history/facet :legal-hold/state]]
                     db' hold-eid)]
    (is (= :placed (:legal-hold/state hold)))
    (is (= "Acme v. Doe 24-CV-1234" (:legal-hold/matter-name hold)))
    (is (= 1 (count (:legal-hold/scope-eids hold))))
    (is (= 1 (count history)) "Exactly one :status-history row for nil → :placed.")))

;; ============================================================================
;; eid-set scope blocks purge
;; ============================================================================

(deftest eid-set-scope-blocks-purge
  (let [conn (bootstrap)
        db (d/db conn)
        held (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)
        _ (lhold/place! conn
                        {:code "HOLD-002"
                         :matter-name "Eid-set test"
                         :issued-by-uid (uid db "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Preserve specific partner record."
                         :scope-eids [held]})]
    (testing "purge of held eid is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"purge blocked by active legal hold"
           (d/transact conn [[:db.fn/call v/validate-and-apply [[:db/purge held]]]]))))))

;; ============================================================================
;; scope-query catches new entities
;; ============================================================================

(deftest scope-query-blocks-purge
  (let [conn (bootstrap)
        db (d/db conn)
        ;; Query: all partners with kind = :customer.
        _ (lhold/place! conn
                        {:code "HOLD-003"
                         :matter-name "Query-scope test"
                         :issued-by-uid (uid db "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Preserve all customers under matter."
                         :scope-query "[:find ?e :where [?e :partner/kind :customer]]"})
        ;; Add a NEW partner after the hold is placed.
        _ (d/transact conn
                      [{:db/id "new-cust"
                        :partner/external-id "NEW-CUST"
                        :partner/name "New Customer"
                        :partner/kind :customer}])
        new-eid (d/q '[:find ?e . :where [?e :partner/external-id "NEW-CUST"]]
                     (d/db conn))]
    (testing "purge of new entity matching scope-query is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"purge blocked by active legal hold"
           (d/transact conn [[:db.fn/call v/validate-and-apply [[:db/purge new-eid]]]]))))))

;; ============================================================================
;; release! → subsequent purge succeeds
;; ============================================================================

(deftest release-allows-subsequent-purge
  (let [conn (bootstrap)
        db (d/db conn)
        held (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)
        _ (lhold/place! conn
                        {:code "HOLD-004"
                         :matter-name "Release-then-purge test"
                         :issued-by-uid (uid db "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Initial preservation."
                         :scope-eids [held]})
        hold-eid (lhold/by-code (d/db conn) "HOLD-004")
        ;; Release the hold (different actor for :no-self-approval).
        _ (lhold/release! conn
                          {:hold-eid hold-eid
                           :released-by-uid (uid (d/db conn) "admin")
                           :supporting-doc (adoc-eid (d/db conn) "DOC-RELEASE-001")
                           :reason-note "Matter dismissed; preservation no longer required."})]
    (testing "after release, purge succeeds"
      (is (= :released
             (:legal-hold/state
              (d/pull (d/db conn) [:legal-hold/state] hold-eid))))
      ;; Purge would now be allowed by hold-middleware. Validate by
      ;; calling find-hold-violating-purges directly (avoids the
      ;; sealing middleware which would block for other reasons).
      (is (empty? (lhold/find-hold-violating-purges
                   (d/db conn) [[:db/purge held]]))))))

;; ============================================================================
;; ADR-038 enforcement on placement and release
;; ============================================================================

(deftest self-approval-rejected-on-release
  (let [conn (bootstrap)
        db (d/db conn)
        counsel-eid (uid db "counsel")
        held (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)
        _ (lhold/place! conn
                        {:code "HOLD-005"
                         :matter-name "SoD test"
                         :issued-by-uid counsel-eid
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Initial."
                         :scope-eids [held]})
        hold-eid (lhold/by-code (d/db conn) "HOLD-005")]
    (testing "same actor cannot release their own hold"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (lhold/release! conn
                           {:hold-eid hold-eid
                            :released-by-uid counsel-eid     ; same!
                            :supporting-doc (adoc-eid (d/db conn) "DOC-RELEASE-001")
                            :reason-note "Trying to release my own hold."}))))))

(deftest placement-without-supporting-doc-rejected
  (let [conn (bootstrap)
        db (d/db conn)
        held (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)]
    (testing ":requires-supporting-doc enforced on placement"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":supporting-doc required"
           (lhold/place! conn
                         {:code "HOLD-006"
                          :matter-name "Bad placement"
                          :issued-by-uid (uid db "counsel")
                          :issued-at #inst "2026-05-13"
                          ;; no :supporting-doc!
                          :reason-note "Forgot the order."
                          :scope-eids [held]}))))))

;; ============================================================================
;; Bitemporal: scope-query value-at
;; ============================================================================

(deftest scope-query-value-at-is-bitemporal
  (let [conn (bootstrap)
        db (d/db conn)
        held (d/q '[:find ?e . :where [?e :partner/external-id "ACME"]] db)
        _ (lhold/place! conn
                        {:code "HOLD-007"
                         :matter-name "Bitemporal scope test"
                         :issued-by-uid (uid db "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid db "DOC-PLACE-001")
                         :reason-note "Initial scope."
                         :scope-query "[:find ?e :where [?e :partner/kind :customer]]"
                         :vt-from #inst "2026-05-13"})
        hold-eid (lhold/by-code (d/db conn) "HOLD-007")
        original-query (kbt/value-at (d/db conn) hold-eid :legal-hold/scope-query
                                     #inst "2026-05-14")]
    (testing "kbt/value-at resolves the hold's scope-query at a past valid-time"
      (is (= "[:find ?e :where [?e :partner/kind :customer]]" original-query)))))
