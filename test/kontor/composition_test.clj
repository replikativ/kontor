(ns kontor.composition-test
  "End-to-end demonstration of ADR-068's composition contract:
   every business write exposes a `*-tx-data` builder that composes
   into a single atomic, gated `kontor.process` (or, when ceremony
   is unnecessary, a single `transact-with-validation` over a
   vec-concat of builder outputs).

   The three deftests prove the cross-module property:

     1. `litigation-onboarding-composes-audit-doc-and-legal-hold`
        — one `run-process` spans `kontor.audit-doc` and
        `kontor.legal-hold`. The hold's `:supporting-doc` points at
        the audit-doc that step 1 created in the SAME tx, by
        string-tempid round-trip (ADR-067 + research note 47).

     2. `gate-violation-aborts-the-entire-composite-event`
        — when one step's tx-data fails the gate (here: a legal-
        hold release with an empty :reason-note, blocked by
        ADR-038's :requires-non-empty-reason-note approval policy),
        the WHOLE process aborts; no orphan audit-doc lands.

     3. `plain-tx-data-vec-concat-also-composes`
        — when you don't want the ceremony of `run-process`, two
        builders' tx-data vectors concat directly and route through
        `transact-with-validation` for the same atomicity + gating."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.legal-hold :as lhold]
            [kontor.process :as process]
            [kontor.validation :as v]))

(defn- bootstrap
  "Minimal seed: an 'actor' (a partner used as :kontor.audit/create-uid stand-in,
   per the convention in legal_hold_test) and an :acme target to put
   under a hold."
  []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn
                [{:db/id "u-counsel"
                  :kontor.partner/external-id "U-counsel"
                  :kontor.partner/name "Counsel C"}
                 {:db/id "p-acme"
                  :kontor.partner/external-id "ACME"
                  :kontor.partner/name "Acme Corp"}])
    conn))

(defn- partner-eid [db ext]
  (d/q '[:find ?e . :in $ ?x :where [?e :kontor.partner/external-id ?x]] db ext))

;; ============================================================================
;; 1. One run-process across two modules + cross-module tempid threading
;; ============================================================================

(deftest litigation-onboarding-composes-audit-doc-and-legal-hold
  (let [conn (bootstrap)
        db (d/db conn)
        counsel (partner-eid db "U-counsel")
        acme (partner-eid db "ACME")
        report (process/run-process
                conn
                {:steps [;; STEP 1 — upload the subpoena (audit-doc)
                         (fn [sdb _ctx]
                           (audit-doc/create-doc-tx-data
                            sdb {:tempid "subpoena"
                                 :code "SUB-2026-001"
                                 :type :subpoena
                                 :storage-uri "s3://docs/sub-2026-001.pdf"
                                 :uploaded-by-uid counsel
                                 :uploaded-at #inst "2026-05-14"}))
                         ;; STEP 2 — place the hold; :supporting-doc
                         ;; references the audit-doc by STRING TEMPID
                         ;; (resolves to the SAME eid step 1 will commit
                         ;; — ADR-067, research note 47).
                         (fn [sdb _ctx]
                           (lhold/place-tx-data
                            sdb {:tempid "hold-acme"
                                 :code "HOLD-ACME-2026"
                                 :matter-name "Acme v. Doe 24-CV-1234"
                                 :issued-by-uid counsel
                                 :issued-at #inst "2026-05-14"
                                 :supporting-doc "subpoena"
                                 :scope-eids [acme]
                                 :reason-note "preservation order received"}))]
                 :vt-from #inst "2026-05-14"
                 :vt-to kbt/forever})
        tempids (:tempids report)
        db' (d/db conn)
        doc-eid (get tempids "subpoena")
        hold-eid (get tempids "hold-acme")
        hold (d/pull db' '[:kontor.legal-hold/code :kontor.legal-hold/state
                           {:kontor.legal-hold/supporting-doc [:db/id :kontor.audit-doc/code]}
                           :kontor.legal-hold/scope-eids]
                     hold-eid)]
    (testing "both entities land in one atomic process"
      (is (some? doc-eid))
      (is (some? hold-eid))
      (is (= doc-eid (-> hold :kontor.legal-hold/supporting-doc :db/id))
          "the hold's supporting-doc resolves to the audit-doc the SAME
           process created — the string tempid round-tripped"))
    (testing "the audit-doc is reachable as a normal entity"
      (is (= "SUB-2026-001"
             (:kontor.audit-doc/code (-> hold :kontor.legal-hold/supporting-doc)))))
    (testing "the hold is :placed and scoped to acme"
      (is (= :placed (:kontor.legal-hold/state hold)))
      (is (= [{:db/id acme}] (:kontor.legal-hold/scope-eids hold))))
    (testing "exactly one datahike tx — both entities share it"
      (let [doc-tx (d/q '[:find ?t .
                          :in $ ?e
                          :where [?e :kontor.audit-doc/code _ ?t]]
                        db' doc-eid)
            hold-tx (d/q '[:find ?t .
                           :in $ ?e
                           :where [?e :kontor.legal-hold/code _ ?t]]
                         db' hold-eid)]
        (is (= doc-tx hold-tx)
            "datahike-tx (the implicit :tx) is the same for both —
             single d/transact, single commit-id")))))

;; ============================================================================
;; 2. Gate violation aborts the whole composite event (atomic)
;; ============================================================================

(deftest gate-violation-aborts-the-entire-composite-event
  (let [conn (bootstrap)
        db (d/db conn)
        counsel (partner-eid db "U-counsel")
        acme (partner-eid db "ACME")
        ;; Pre-existing hold so we have something to (incorrectly) release.
        _ (lhold/place! conn {:code "HOLD-PRE-EXIST"
                              :matter-name "Pre-existing matter"
                              :issued-by-uid counsel
                              :issued-at #inst "2026-04-01"
                              :supporting-doc
                              (-> (audit-doc/create-doc! conn
                                                         {:code "DOC-PLACE"
                                                          :type :legal-hold-order
                                                          :storage-uri "s3://docs/place.pdf"})
                                  :tempids (get "audit-doc-1"))
                              :scope-eids [acme]
                              :reason-note "preservation."})
        db1 (d/db conn)
        ;; Doc count before the doomed process:
        doc-count-before (d/q '[:find (count ?e) . :where [?e :kontor.audit-doc/code _]] db1)
        hold-eid (lhold/by-code db1 "HOLD-PRE-EXIST")
        attempt
        (try
          (process/run-process
           conn
           {:steps [;; STEP 1 — create a fresh audit-doc.
                    (fn [sdb _ctx]
                      (audit-doc/create-doc-tx-data
                       sdb {:tempid "release-order"
                            :code "DOC-RELEASE-DOOMED"
                            :type :legal-hold-release
                            :storage-uri "s3://docs/release.pdf"
                            :uploaded-by-uid counsel
                            :uploaded-at #inst "2026-05-14"}))
                    ;; STEP 2 — release the hold with an EMPTY
                    ;; reason-note. ADR-038's policy
                    ;; :requires-non-empty-reason-note fires inside
                    ;; release-tx-data → throws.
                    (fn [sdb _ctx]
                      (lhold/release-tx-data
                       sdb {:hold-eid hold-eid
                            :released-by-uid counsel
                            :supporting-doc "release-order"
                            :reason-note ""}))]
            :vt-from #inst "2026-05-14"})
          ::no-throw
          (catch clojure.lang.ExceptionInfo e
            [(:type (ex-data e)) (.getMessage e)]))
        db2 (d/db conn)
        doc-count-after (d/q '[:find (count ?e) . :where [?e :kontor.audit-doc/code _]] db2)
        hold-after (d/pull db2 [:kontor.legal-hold/state] hold-eid)]
    (testing "the process threw (the release-tx-data guard fires)"
      (is (not= ::no-throw attempt)))
    (testing "the doomed audit-doc was NOT created — atomic abort"
      (is (= doc-count-before doc-count-after))
      (is (nil? (d/q '[:find ?e . :where [?e :kontor.audit-doc/code "DOC-RELEASE-DOOMED"]]
                     db2))))
    (testing "the pre-existing hold is untouched"
      (is (= :placed (:kontor.legal-hold/state hold-after))))))

;; ============================================================================
;; 3. Plain tx-data concat — composition without `run-process` ceremony
;; ============================================================================

(deftest plain-tx-data-vec-concat-also-composes
  (testing "two builders' tx-data compose by vec-concat + one outer
            kbt/with-vt + transact-with-validation — the cheap path
            when you don't need run-process's serialization /
            speculative-db threading"
    (let [conn (bootstrap)
          db (d/db conn)
          counsel (partner-eid db "U-counsel")
          acme (partner-eid db "ACME")
          ;; Build both fragments off the SAME db snapshot.
          doc-frag (audit-doc/create-doc-tx-data
                    db {:tempid "doc-direct"
                        :code "SUB-DIRECT-001"
                        :type :subpoena
                        :storage-uri "s3://docs/direct.pdf"
                        :uploaded-by-uid counsel
                        :uploaded-at #inst "2026-05-14"})
          hold-frag (lhold/place-tx-data
                     db {:tempid "hold-direct"
                         :code "HOLD-DIRECT-2026"
                         :matter-name "Direct composition test"
                         :issued-by-uid counsel
                         :issued-at #inst "2026-05-14"
                         :supporting-doc "doc-direct"
                         :scope-eids [acme]
                         :reason-note "test"})
          combined (kbt/with-vt (into (vec doc-frag) hold-frag)
                     #inst "2026-05-14" kbt/forever)
          report (v/transact-with-validation conn combined)
          tempids (:tempids report)
          db' (d/db conn)
          hold (d/pull db' '[:kontor.legal-hold/code
                             {:kontor.legal-hold/supporting-doc [:kontor.audit-doc/code]}]
                       (get tempids "hold-direct"))]
      (is (= "SUB-DIRECT-001"
             (-> hold :kontor.legal-hold/supporting-doc :kontor.audit-doc/code))
          "the hold's supporting-doc points at the doc the SAME tx-data
           created — vec-concat + tempid threading works without
           run-process"))))
