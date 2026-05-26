(ns kontor.audit-doc-privilege-test
  "ADR-051: :audit-doc/privilege classification.

   Covers:
   - reclassify-privilege! :none → :attorney-client (upgrade — ungated).
   - re-classification between two privileged values (ungated).
   - waiver (→ :none) requires :no-self-approval — the creator can't
     waive their own doc's privilege.
   - waiver requires :supporting-doc + :reason-note (ADR-038).
   - waiver by a different actor with both succeeds.
   - visible-to? / filter-by-privilege label rules.
   - bitemporal: value-at on :audit-doc/privilege returns the
     classification as of a past valid-time."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as adoc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.partner/external-id "U-paralegal" :kontor.partner/name "Paralegal P"}
                 {:kontor.partner/external-id "U-counsel"   :kontor.partner/name "Counsel C"}
                 ;; The waiver-determination memo.
                 {:db/id "doc-memo"
                  :audit-doc/code "WAIVER-MEMO-001"
                  :audit-doc/type :legal-memo
                  :audit-doc/storage-uri "s3://docs/waiver-memo-001"
                  :audit-doc/uploaded-at #inst "2026-05-14"}])
    conn))

(defn- uid [db actor]
  (d/q '[:find ?e . :in $ ?xid
         :where [?e :kontor.partner/external-id ?xid]]
       db (str "U-" actor)))

;; Create an :audit-doc whose creator (:kontor.audit/create-uid) is the paralegal.
(defn- seed-doc! [conn code]
  (adoc/create-doc! conn
                    {:code code
                     :type :customer-email
                     :storage-uri (str "s3://docs/" code)
                     :uploaded-by-uid (uid (d/db conn) "paralegal")})
  (adoc/by-code (d/db conn) code))

;; ============================================================================
;; Classification — upgrades are ungated
;; ============================================================================

(deftest upgrade-is-ungated
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-1")
        paralegal (uid (d/db conn) "paralegal")]
    (testing "doc starts at :none (nil normalized)"
      (is (= :none (adoc/privilege-of (d/db conn) doc))))
    (testing ":none → :attorney-client by the creator, no supporting-doc — succeeds"
      ;; Over-classification is the safe direction; no approval gate.
      (adoc/reclassify-privilege! conn
                                  {:doc doc
                                   :to :attorney-client
                                   :changed-by-uid paralegal
                                   :reason :privilege-determined})
      (is (= :attorney-client (adoc/privilege-of (d/db conn) doc))))
    (testing "the change wrote a :status-history row"
      (let [history (d/q '[:find [?h ...]
                           :in $ ?e
                           :where
                           [?h :kontor.status-history/entity ?e]
                           [?h :kontor.status-history/facet :audit-doc/privilege]]
                         (d/db conn) doc)]
        (is (= 1 (count history)))))))

(deftest reclassify-between-privileged-is-ungated
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-2")
        paralegal (uid (d/db conn) "paralegal")]
    (adoc/reclassify-privilege! conn
                                {:doc doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined})
    (testing ":attorney-client → :work-product by the same actor — succeeds (ungated)"
      (adoc/reclassify-privilege! conn
                                  {:doc doc :to :work-product
                                   :changed-by-uid paralegal
                                   :reason :privilege-reclassified})
      (is (= :work-product (adoc/privilege-of (d/db conn) doc))))))

;; ============================================================================
;; Waiver — the governed edge
;; ============================================================================

(deftest waiver-requires-no-self-approval
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-3")
        paralegal (uid (d/db conn) "paralegal")]
    (adoc/reclassify-privilege! conn
                                {:doc doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined})
    (testing "the doc creator cannot waive its privilege (ADR-038 :no-self-approval)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (adoc/reclassify-privilege! conn
                                       {:doc doc :to :none
                                        :changed-by-uid paralegal   ; = :kontor.audit/create-uid!
                                        :reason :privilege-waived
                                        :reason-note "Trying to self-waive."
                                        :supporting-doc (adoc/by-code (d/db conn)
                                                                      "WAIVER-MEMO-001")}))))))

(deftest waiver-requires-supporting-doc-and-reason-note
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-4")
        paralegal (uid (d/db conn) "paralegal")
        counsel (uid (d/db conn) "counsel")]
    (adoc/reclassify-privilege! conn
                                {:doc doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined})
    (testing "waiver without :supporting-doc is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (adoc/reclassify-privilege! conn
                                       {:doc doc :to :none
                                        :changed-by-uid counsel
                                        :reason :privilege-waived
                                        :reason-note "Waived per litigation review."}))))
    (testing "waiver without :reason-note is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (adoc/reclassify-privilege! conn
                                       {:doc doc :to :none
                                        :changed-by-uid counsel
                                        :reason :privilege-waived
                                        :supporting-doc (adoc/by-code (d/db conn)
                                                                      "WAIVER-MEMO-001")}))))))

(deftest waiver-by-different-actor-with-doc-succeeds
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-5")
        paralegal (uid (d/db conn) "paralegal")
        counsel (uid (d/db conn) "counsel")]
    (adoc/reclassify-privilege! conn
                                {:doc doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined})
    (testing "a different actor, with supporting-doc + reason-note, can waive"
      (adoc/reclassify-privilege! conn
                                  {:doc doc :to :none
                                   :changed-by-uid counsel
                                   :reason :privilege-waived
                                   :reason-note "Waived per litigation-review determination."
                                   :supporting-doc (adoc/by-code (d/db conn)
                                                                 "WAIVER-MEMO-001")})
      (is (= :none (adoc/privilege-of (d/db conn) doc))))))

;; ============================================================================
;; visible-to? / filter-by-privilege
;; ============================================================================

(deftest visibility-label-rules
  (let [conn (bootstrap)
        public-doc (seed-doc! conn "DOC-PUBLIC")
        priv-doc (seed-doc! conn "DOC-PRIV")
        paralegal (uid (d/db conn) "paralegal")]
    (adoc/reclassify-privilege! conn
                                {:doc priv-doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined})
    (testing ":none doc is visible to everyone, even an empty privilege set"
      (is (adoc/visible-to? (d/db conn) public-doc #{})))
    (testing ":attorney-client doc is NOT visible without the matching privilege"
      (is (not (adoc/visible-to? (d/db conn) priv-doc #{:work-product}))))
    (testing ":attorney-client doc IS visible to a viewer holding it"
      (is (adoc/visible-to? (d/db conn) priv-doc #{:attorney-client :work-product})))
    (testing "filter-by-privilege keeps only the visible docs"
      (let [visible (adoc/filter-by-privilege (d/db conn)
                                              [public-doc priv-doc]
                                              #{:work-product})]
        (is (= [public-doc] visible))))))

;; ============================================================================
;; Bitemporal — privilege classification at a past valid-time
;; ============================================================================

(deftest privilege-value-at-is-bitemporal
  (let [conn (bootstrap)
        doc (seed-doc! conn "DOC-BT")
        paralegal (uid (d/db conn) "paralegal")]
    ;; Classified :attorney-client effective 2026-05-14.
    (adoc/reclassify-privilege! conn
                                {:doc doc :to :attorney-client
                                 :changed-by-uid paralegal
                                 :reason :privilege-determined
                                 :vt-from #inst "2026-05-14"})
    (testing "d/valid-at resolves the privilege classification at a valid-time"
      (is (= :attorney-client
             (:audit-doc/privilege
              (d/pull (d/valid-at (d/db conn) #inst "2026-05-20")
                      [:audit-doc/privilege] doc)))))))
