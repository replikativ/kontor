(ns kontor.dsar-test
  "ADR-052: data-subject-access requests + the bitemporal collect walk.

   Covers:
   - file-request! → :received, :deadline-at computed, status-history.
   - collect returns entities referencing the subject, keyed by the
     registered partner-attr.
   - bitemporal collect: an older db value excludes later-added data.
   - partner-merge: :include-merged? folds in data referencing the
     merged-from duplicate.
   - legal-hold composition: collect reports :on-legal-hold? +
     :legal-holds for a held subject.
   - register-partner-attr!: a newly-registered attr is walked.
   - advance-state! fulfillment requires SoD + supporting-doc;
     denial requires supporting-doc + reason-note (ADR-038)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.dsar :as dsar]
            [kontor.legal-hold :as lhold]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:partner/external-id "SUBJECT" :partner/name "Jane Subject"
                  :partner/kind :customer}
                 {:partner/external-id "U-intake" :partner/name "Intake Officer"}
                 {:partner/external-id "U-dpo"    :partner/name "Data Protection Officer"}
                 {:partner/external-id "U-counsel" :partner/name "Counsel C"}
                 {:db/id "doc-intake"
                  :audit-doc/code "DSAR-INTAKE-001"
                  :audit-doc/type :dsar-intake-form
                  :audit-doc/storage-uri "s3://docs/dsar-intake-001"
                  :audit-doc/uploaded-at #inst "2026-05-14"}
                 {:db/id "doc-bundle"
                  :audit-doc/code "DSAR-BUNDLE-001"
                  :audit-doc/type :dsar-fulfillment-package
                  :audit-doc/storage-uri "s3://docs/dsar-bundle-001"
                  :audit-doc/uploaded-at #inst "2026-05-20"}
                 {:db/id "doc-hold"
                  :audit-doc/code "HOLD-ORDER-DSAR"
                  :audit-doc/type :legal-hold-order
                  :audit-doc/storage-uri "s3://docs/hold-dsar"
                  :audit-doc/uploaded-at #inst "2026-05-15"}])
    conn))

(defn- pe [db xid]
  (d/q '[:find ?e . :in $ ?x :where [?e :partner/external-id ?x]] db xid))

(defn- adoc [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :audit-doc/code ?c]] db code))

;; ============================================================================
;; file-request!
;; ============================================================================

(deftest file-request-sets-received-and-deadline
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        _ (dsar/file-request! conn
                              {:external-id "DSAR-2026-001"
                               :partner subject
                               :kind :access
                               :received-at #inst "2026-05-14"
                               :deadline-days 30
                               :received-via :portal
                               :supporting-doc (adoc (d/db conn) "DSAR-INTAKE-001")
                               :changed-by-uid (pe (d/db conn) "U-intake")})
        db (d/db conn)
        req-eid (dsar/by-external-id db "DSAR-2026-001")
        req (d/pull db '[*] req-eid)
        history (d/q '[:find [?h ...]
                       :in $ ?e
                       :where
                       [?h :status-history/entity ?e]
                       [?h :status-history/facet :dsar-request/state]]
                     db req-eid)]
    (is (= :received (:dsar-request/state req)))
    (is (= :access (:dsar-request/kind req)))
    (is (= #inst "2026-06-13" (:dsar-request/deadline-at req))
        "deadline-at = received-at + 30 days.")
    (is (= 1 (count history)) "Exactly one :status-history row for nil → :received.")))

;; ============================================================================
;; collect — the bitemporal reference walk
;; ============================================================================

(deftest collect-returns-referencing-entities
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        ;; Two entities referencing the subject via different attrs.
        _ (d/transact conn
                      [{:invoice/external-id "INV-SUBJ-1" :invoice/buyer subject}
                       {:partner-bank-account/partner subject}])
        result (dsar/collect (d/db conn) subject {})]
    (testing "collect surfaces the subject + the referencing entities"
      (is (= subject (:db/id (:partner result))))
      (is (contains? (:references result) :invoice/buyer))
      (is (contains? (:references result) :partner-bank-account/partner))
      (is (= 1 (count (get-in result [:references :invoice/buyer]))))
      (is (= "INV-SUBJ-1"
             (-> result :references :invoice/buyer first :invoice/external-id))))
    (testing "a partner with no referencing data has empty :references"
      (is (empty? (:references (dsar/collect (d/db conn)
                                             (pe (d/db conn) "U-dpo") {})))))))

(deftest collect-is-bitemporal
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        ;; Snapshot the db BEFORE any invoice exists.
        db-before (d/db conn)
        _ (d/transact conn [{:invoice/external-id "INV-LATE" :invoice/buyer subject}])
        db-after (d/db conn)]
    (testing "collect on the older db value does not see later-added data"
      (is (empty? (:references (dsar/collect db-before subject {})))))
    (testing "collect on the current db value sees it"
      (is (= 1 (count (get-in (dsar/collect db-after subject {})
                              [:references :invoice/buyer])))))))

(deftest collect-folds-in-merged-from-partners
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        ;; A duplicate partner that was merged INTO the subject, with
        ;; an invoice that still references the duplicate.
        _ (d/transact conn
                      [{:partner/external-id "SUBJECT-DUP" :partner/name "Jane Dup"}
                       {:invoice/external-id "INV-DUP-1"
                        :invoice/buyer [:partner/external-id "SUBJECT-DUP"]}])
        dup (pe (d/db conn) "SUBJECT-DUP")
        _ (d/transact conn
                      [{:partner-merge/duplicate-of subject
                        :partner-merge/superseded dup}])]
    (testing ":include-merged? true folds the duplicate's data into the package"
      (let [result (dsar/collect (d/db conn) subject {:include-merged? true})]
        (is (= [dup] (:merged-from result)))
        (is (= 1 (count (get-in result [:references :invoice/buyer]))))))
    (testing ":include-merged? false ignores the merge chain"
      (let [result (dsar/collect (d/db conn) subject {:include-merged? false})]
        (is (empty? (:merged-from result)))
        (is (not (contains? (:references result) :invoice/buyer)))))))

(deftest collect-reports-legal-holds
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        _ (lhold/place! conn
                        {:code "HOLD-ON-SUBJECT"
                         :matter-name "Subject under preservation"
                         :issued-by-uid (pe (d/db conn) "U-counsel")
                         :issued-at #inst "2026-05-15"
                         :supporting-doc (adoc (d/db conn) "HOLD-ORDER-DSAR")
                         :reason-note "Preserve all subject data."
                         :scope-eids [subject]})
        result (dsar/collect (d/db conn) subject {})]
    (testing "collect reports the subject is on legal hold"
      (is (true? (:on-legal-hold? result)))
      (is (= 1 (count (:legal-holds result))))
      (is (= "HOLD-ON-SUBJECT"
             (:legal-hold/code
              (d/pull (d/db conn) [:legal-hold/code]
                      (first (:legal-holds result)))))))
    (testing "an unheld partner reports :on-legal-hold? false"
      (is (false? (:on-legal-hold? (dsar/collect (d/db conn)
                                                 (pe (d/db conn) "U-dpo") {})))))))

;; ============================================================================
;; register-partner-attr!
;; ============================================================================

(deftest register-partner-attr-extends-collect
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        ;; :dsar-request/partner is NOT a kernel-seeded partner-attr —
        ;; a DSAR request references the subject but isn't part of the
        ;; default walk. Register it and confirm collect picks it up.
        had? (contains? (dsar/partner-attrs) :dsar-request/partner)
        _ (dsar/file-request! conn
                              {:external-id "DSAR-REG-1"
                               :partner subject
                               :kind :access
                               :received-at #inst "2026-05-14"
                               :deadline-days 30
                               :changed-by-uid (pe (d/db conn) "U-intake")})]
    (testing "before registration, collect does not walk :dsar-request/partner"
      (is (not had?))
      (is (not (contains? (:references (dsar/collect (d/db conn) subject {}))
                          :dsar-request/partner))))
    (testing "after register-partner-attr!, collect walks it"
      (dsar/register-partner-attr! :dsar-request/partner)
      (is (contains? (dsar/partner-attrs) :dsar-request/partner))
      (is (= 1 (count (get-in (dsar/collect (d/db conn) subject {})
                              [:references :dsar-request/partner])))))
    ;; Restore the registry so the test is order-independent.
    (swap! dsar/partner-attrs-registry disj :dsar-request/partner)))

;; ============================================================================
;; advance-state! — fulfillment + denial governance (ADR-038)
;; ============================================================================

(deftest fulfillment-requires-sod-and-supporting-doc
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        intake (pe (d/db conn) "U-intake")
        dpo (pe (d/db conn) "U-dpo")
        _ (dsar/file-request! conn
                              {:external-id "DSAR-FUL-1"
                               :partner subject
                               :kind :access
                               :received-at #inst "2026-05-14"
                               :deadline-days 30
                               :changed-by-uid intake})
        ;; received → verifying-identity → in-progress
        _ (dsar/advance-state! conn {:request "DSAR-FUL-1" :to :verifying-identity
                                     :changed-by-uid intake :reason :dsar-id-check})
        _ (dsar/advance-state! conn {:request "DSAR-FUL-1" :to :in-progress
                                     :changed-by-uid intake :reason :dsar-id-verified})]
    (testing ":identity-verified-at stamped on the verifying → in-progress edge"
      (is (some? (:dsar-request/identity-verified-at
                  (d/pull (d/db conn) [:dsar-request/identity-verified-at]
                          (dsar/by-external-id (d/db conn) "DSAR-FUL-1"))))))
    (testing "fulfillment without :supporting-doc is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (dsar/advance-state! conn {:request "DSAR-FUL-1" :to :fulfilled
                                      :changed-by-uid dpo
                                      :reason :dsar-fulfilled}))))
    (testing "fulfillment by the intake person is rejected (:no-self-approval)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (dsar/advance-state! conn {:request "DSAR-FUL-1" :to :fulfilled
                                      :changed-by-uid intake     ; = creator!
                                      :reason :dsar-fulfilled
                                      :supporting-doc (adoc (d/db conn) "DSAR-BUNDLE-001")}))))
    (testing "fulfillment by a different actor with the bundle succeeds"
      (dsar/advance-state! conn {:request "DSAR-FUL-1" :to :fulfilled
                                 :changed-by-uid dpo
                                 :reason :dsar-fulfilled
                                 :supporting-doc (adoc (d/db conn) "DSAR-BUNDLE-001")
                                 :fulfilled-package (adoc (d/db conn) "DSAR-BUNDLE-001")})
      (let [req (d/pull (d/db conn) '[*]
                        (dsar/by-external-id (d/db conn) "DSAR-FUL-1"))]
        (is (= :fulfilled (:dsar-request/state req)))
        (is (some? (:dsar-request/fulfilled-at req)))
        (is (some? (:dsar-request/fulfilled-package req)))))))

(deftest denial-requires-supporting-doc-and-reason-note
  (let [conn (bootstrap)
        subject (pe (d/db conn) "SUBJECT")
        intake (pe (d/db conn) "U-intake")
        dpo (pe (d/db conn) "U-dpo")
        _ (dsar/file-request! conn
                              {:external-id "DSAR-DEN-1"
                               :partner subject
                               :kind :erasure
                               :received-at #inst "2026-05-14"
                               :deadline-days 30
                               :changed-by-uid intake})
        _ (dsar/advance-state! conn {:request "DSAR-DEN-1" :to :verifying-identity
                                     :changed-by-uid intake :reason :dsar-id-check})
        _ (dsar/advance-state! conn {:request "DSAR-DEN-1" :to :in-progress
                                     :changed-by-uid intake :reason :dsar-id-verified})]
    (testing "denial without supporting-doc + reason-note is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (dsar/advance-state! conn {:request "DSAR-DEN-1" :to :denied
                                      :changed-by-uid dpo
                                      :reason :dsar-denied}))))
    (testing "denial with both succeeds and records :denied-reason"
      (dsar/advance-state! conn {:request "DSAR-DEN-1" :to :denied
                                 :changed-by-uid dpo
                                 :reason :dsar-denied
                                 :reason-note "Records exempt under statutory retention."
                                 :supporting-doc (adoc (d/db conn) "DSAR-INTAKE-001")
                                 :denied-reason :exempt-records})
      (let [req (d/pull (d/db conn) '[*]
                        (dsar/by-external-id (d/db conn) "DSAR-DEN-1"))]
        (is (= :denied (:dsar-request/state req)))
        (is (= :exempt-records (:dsar-request/denied-reason req)))))))
