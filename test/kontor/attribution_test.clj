(ns kontor.attribution-test
  "ADR-153 — attribution is mandatory where a control reads it, and is not
   retractable once recorded.

   ADR-150 made `:no-self-approval` fail CLOSED on a nil creator, which is
   the right verdict (you cannot verify separation of duties against an
   unknown creator; ISA 530 ¶11 + A15 treat an untestable item as a
   deviation, PCAOB AS 2201 ¶.A3(b) makes a check that passes on missing
   data deficient by design). It shipped without the other half: several
   entities that a SEEDED `:no-self-approval` policy gates never stamped a
   creator at all, so those transitions were refused unconditionally.

   Per ADR-140's pair rule every control here gets two tests — one that it
   REFUSES the illegitimate case, one that it PERMITS the legitimate one.
   The second is not ceremony: a control that refuses everything passes any
   refusal-only test, which is exactly how the ADR-150 regression shipped
   green."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.actor :as actor]
            [kontor.compliance.audit-doc :as adoc]
            [kontor.compliance.dsar :as dsar]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.governance :as gov]
            [kontor.workflow.status-machine :as sm]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap
  "A db with two registered actors — `alice` (the creator) and `bob` (the
   second pair of eyes) — plus a partner to be a DSAR subject and a
   supporting doc for the governed edges."
  []
  (let [conn (core/create-test-db)]
    (actor/register-actors! conn [{:uid "alice" :name "Alice A" :kind :person}
                                  {:uid "bob"   :name "Bob B"   :kind :person}])
    (d/transact conn [{:kontor.partner/external-id "P-subject"
                       :kontor.partner/name "Data Subject"}])
    (adoc/create-doc! conn {:code "SUPPORT-1"
                            :type :legal-memo
                            :storage-uri "s3://docs/support-1"
                            :uploaded-by-uid "alice"})
    conn))

(defn- actor-eid [db uid] (actor/resolve-actor db uid))

(defn- partner-eid [db xid]
  (d/q '[:find ?e . :in $ ?x :where [?e :kontor.partner/external-id ?x]] db xid))

;; ============================================================================
;; 1. audit-doc privilege waiver — the control installed in EVERY kontor db
;; ============================================================================
;;
;; `kontor.core/install-schema!` seeds `:no-self-approval` on every
;; `<privileged> → :none` edge (audit_doc.clj `approval-policy-seeds`). Before
;; ADR-153 `create-doc!` stamped the creator only when the OPTIONAL
;; `:uploaded-by-uid` was supplied, so a doc created via the documented
;; minimal call could never have its privilege waived — including under court
;; order. kontor's own showcase made exactly that call.

(deftest audit-doc-refuses-creation-without-an-uploader
  (let [conn (bootstrap)]
    (testing "the pre-ADR-153 minimal call is refused AT CREATION, where the
              operator can still do something about it — rather than silently
              producing a document nobody can ever declassify"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":uploaded-by-uid required"
           (adoc/create-doc! conn {:code "DOC-NO-UPLOADER"
                                   :type :customer-email
                                   :storage-uri "s3://docs/x"}))))))

(deftest audit-doc-privilege-can-still-be-waived-by-a-second-actor
  (let [conn (bootstrap)
        _ (adoc/create-doc! conn {:code "DOC-W"
                                  :type :customer-email
                                  :storage-uri "s3://docs/w"
                                  :uploaded-by-uid "alice"})
        doc (adoc/by-code (d/db conn) "DOC-W")
        support (adoc/by-code (d/db conn) "SUPPORT-1")]
    (adoc/reclassify-privilege! conn {:doc doc :to :attorney-client
                                      :changed-by-uid (actor-eid (d/db conn) "alice")
                                      :reason :privilege-determined})
    (testing "alice, who uploaded it, cannot waive it (the control still bites)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (adoc/reclassify-privilege! conn {:doc doc :to :none
                                             :changed-by-uid (actor-eid (d/db conn) "alice")
                                             :reason :privilege-waived
                                             :reason-note "court order"
                                             :supporting-doc support}))))
    (testing "bob can — THIS is the assertion whose absence let the P0 ship;
              a control that refuses every waiver also passes the test above"
      (adoc/reclassify-privilege! conn {:doc doc :to :none
                                        :changed-by-uid (actor-eid (d/db conn) "bob")
                                        :reason :privilege-waived
                                        :reason-note "waived per court order 2026-07"
                                        :supporting-doc support})
      (is (= :none (adoc/privilege-of (d/db conn) doc))))))

;; ============================================================================
;; 2. DSAR fulfillment — GDPR Art. 12(3) runs a one-month clock
;; ============================================================================

(deftest dsar-refuses-intake-without-an-actor
  (let [conn (bootstrap)]
    (testing "a request nobody is recorded as having taken in could never be
              fulfilled, while the statutory deadline ran anyway"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":changed-by-uid required"
           (dsar/file-request! conn {:external-id "DSAR-NO-INTAKE"
                                     :partner (partner-eid (d/db conn) "P-subject")
                                     :kind :access
                                     :received-at #inst "2026-06-01"
                                     :deadline-days 30}))))))

(deftest dsar-can-still-be-fulfilled-by-a-second-actor
  (let [conn (bootstrap)
        _ (dsar/file-request! conn {:external-id "DSAR-1"
                                    :partner (partner-eid (d/db conn) "P-subject")
                                    :kind :access
                                    :received-at #inst "2026-06-01"
                                    :deadline-days 30
                                    :changed-by-uid "alice"})
        bundle (do (adoc/create-doc! conn {:code "BUNDLE-1"
                                           :type :dsar-bundle
                                           :storage-uri "s3://docs/bundle-1"
                                           :uploaded-by-uid "bob"})
                   (adoc/by-code (d/db conn) "BUNDLE-1"))]
    (dsar/advance-state! conn {:request "DSAR-1" :to :verifying-identity
                               :changed-by-uid "alice" :reason :dsar-identity-check})
    (dsar/advance-state! conn {:request "DSAR-1" :to :in-progress
                               :changed-by-uid "alice" :reason :dsar-started})
    (testing "alice, who took the request in, cannot also fulfil it"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (dsar/advance-state! conn {:request "DSAR-1" :to :fulfilled
                                      :changed-by-uid "alice"
                                      :reason :dsar-fulfilled
                                      :supporting-doc bundle}))))
    (testing "bob can — the fail-closed rule does not strand the request"
      (dsar/advance-state! conn {:request "DSAR-1" :to :fulfilled
                                 :changed-by-uid "bob"
                                 :reason :dsar-fulfilled
                                 :supporting-doc bundle
                                 :fulfilled-package bundle})
      (is (= :fulfilled (:kontor.dsar-request/state
                         (d/pull (d/db conn) [:kontor.dsar-request/state]
                                 (dsar/by-external-id (d/db conn) "DSAR-1"))))))))

;; ============================================================================
;; 2b. The rule has to understand the actor spellings ADR-150 documents
;; ============================================================================
;;
;; `check-policy` compared a RAW `:changed-by-uid` against a resolved creator
;; eid. ADR-150 deliberately made the actor slot ergonomic — `"sarah"`, a
;; lookup-ref, a uuid and an eid are all accepted — but `(= 4711 "sarah")` is
;; false, so an approver who identified themselves the DOCUMENTED way approved
;; their own work and the rule reported no violation. Fail-open, on the
;; friendly path, which is the one consumers take.

(defn- self-approval-check
  "Run `:no-self-approval` for `doc` with `spec` as the acting actor; return
   the refusal reason, or nil when it permitted."
  [conn doc spec]
  (try (sm/check-policies (d/db conn)
                          {:entity doc :entity-type :audit-doc
                           :facet :kontor.audit-doc/privilege
                           :from :attorney-client :to :none
                           :changed-by-uid spec
                           :supporting-doc (adoc/by-code (d/db conn) "SUPPORT-1")
                           :reason-note "n"})
       nil
       (catch clojure.lang.ExceptionInfo e
         (-> e ex-data :violations first :reason))))

(deftest self-approval-is-caught-however-the-actor-is-spelled
  (let [conn (bootstrap)
        _ (adoc/create-doc! conn {:code "DOC-S" :type :customer-email
                                  :storage-uri "s3://docs/s"
                                  :uploaded-by-uid "alice"})
        doc (adoc/by-code (d/db conn) "DOC-S")
        alice (actor-eid (d/db conn) "alice")]
    (doseq [[label spec] [["an eid"        alice]
                          ["a uid string"  "alice"]
                          ["a lookup-ref"  [:kontor.actor/uid "alice"]]]]
      (testing (str "the creator is recognised when named by " label)
        (is (re-find #"must differ from entity creator"
                     (or (self-approval-check conn doc spec) "")))))
    (testing "and a genuinely different actor still passes, in every spelling"
      (doseq [spec [(actor-eid (d/db conn) "bob") "bob" [:kontor.actor/uid "bob"]]]
        (is (nil? (self-approval-check conn doc spec)))))
    (testing "a uid the gate would only provision at commit time is caught too —
              otherwise the FIRST self-approval by a not-yet-enrolled actor
              slips through and every later one is refused, which is the worst
              place for a control to be inconsistent"
      (d/transact conn [{:kontor.audit-doc/code "DOC-T"
                         :kontor.audit-doc/type :customer-email
                         :kontor.audit-doc/storage-uri "s3://docs/t"
                         :kontor.audit/create-uid (actor-eid (d/db conn) "alice")}])
      (is (re-find #"must differ from entity creator"
                   (or (self-approval-check
                        conn (adoc/by-code (d/db conn) "DOC-T") "alice") ""))))))

;; ============================================================================
;; 3. The retraction vector — why the fail-closed verdict needs a guard
;; ============================================================================
;;
;; `datahike.db.transaction/retract-entity` collects every ref datom POINTING
;; AT the entity and retracts them. So `[:db/retractEntity <actor>]` nils
;; `:kontor.audit/create-uid` on every entity that actor ever created. The
;; first test below PROVES the vector against real datahike rather than
;; asserting it from the docs; the rest prove the guard closes it.

(deftest retracting-an-actor-really-does-nil-every-creator-it-authored
  (let [conn (core/create-test-db)]           ; ungoverned: we want the raw effect
    (actor/register-actor! conn {:uid "alice" :kind :person})
    (adoc/create-doc! conn {:code "DOC-V" :type :customer-email
                            :storage-uri "s3://docs/v" :uploaded-by-uid "alice"})
    (let [db  (d/db conn)
          doc (adoc/by-code db "DOC-V")
          a   (actor-eid db "alice")
          _   (is (= a (:db/id (:kontor.audit/create-uid
                                (d/pull db [{:kontor.audit/create-uid [:db/id]}] doc))))
                  "precondition: the doc records alice as its creator")
          report (d/transact conn [[:db/retractEntity a]])]
      (testing "one leaver-cleanup transaction nils the creator on the doc"
        (is (nil? (:kontor.audit/create-uid
                   (d/pull (:db-after report) [:kontor.audit/create-uid] doc)))))
      (testing "and the writer-side guard sees it in the resolved report"
        (let [v (gov/attribution-violations report)]
          (is (seq v))
          (is (contains? (into #{} (map :attr) v) :kontor.audit/create-uid)))))))

(deftest gate-refuses-retracting-an-actor
  (let [conn (bootstrap)
        a (actor-eid (d/db conn) "alice")]
    (testing "whole-entity deletion of an actor is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)attribution"
           (gate/transact-with-validation conn [[:db/retractEntity a]]))))
    (testing "so is a bare retraction of a creator datom"
      (let [doc (adoc/by-code (d/db conn) "SUPPORT-1")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)attribution"
             (gate/transact-with-validation
              conn [[:db/retract doc :kontor.audit/create-uid a]])))))
    (testing "and the entity-map nil-retract spelling of the same thing"
      (let [doc (adoc/by-code (d/db conn) "SUPPORT-1")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)attribution"
             (gate/transact-with-validation
              conn [{:db/id doc :kontor.audit/create-uid nil}])))))))

(deftest deactivation-is-the-modelled-path-and-still-works
  (let [conn (bootstrap)]
    (testing "retiring an actor is permitted — the guard refuses DELETION, not
              retirement, which is the whole reason it can afford to refuse
              deletion at all"
      (actor/register-actor! conn {:uid "alice" :active false})
      (is (true? (actor/inactive? (d/db conn) "alice"))))
    (testing "every historical ref still resolves, so the audit trail survives"
      (is (= "alice" (actor/actor-uid (d/db conn)
                                      (actor-eid (d/db conn) "alice")))))
    (testing "the writer-side guard does not mistake the cardinality-one value
              swap that deactivation performs for a deletion"
      (let [report (actor/register-actor! conn {:uid "alice" :name "Alice Renamed"})]
        (is (empty? (gov/attribution-violations report)))))))

(deftest purging-the-entity-itself-is-not-un-attribution
  (let [conn (core/create-test-db)]
    (actor/register-actor! conn {:uid "alice" :kind :person})
    (adoc/create-doc! conn {:code "DOC-P" :type :customer-email
                            :storage-uri "s3://docs/p" :uploaded-by-uid "alice"})
    (let [doc (adoc/by-code (d/db conn) "DOC-P")
          report (d/transact conn [[:db/retractEntity doc]])]
      (testing "ADR-007 permits an explicit purge, and ADR-050 retention expiry /
                ADR-052 erasure depend on it — when the whole entity goes, its
                creator going with it is the erasure those controls govern, not
                a SURVIVING entity being stripped of attribution"
        (is (empty? (gov/attribution-violations report)))))))

;; ============================================================================
;; 4. The migration path — :kontor.approval-policy/effective-from
;; ============================================================================

(defn- dsar-fulfil-policy [db]
  (d/q '[:find ?p .
         :where
         [?p :kontor.approval-policy/entity-type :dsar-request]
         [?p :kontor.approval-policy/transition-to :fulfilled]
         [?p :kontor.approval-policy/rule :no-self-approval]
         [?p :kontor.approval-policy/transition-from :in-progress]]
       db))

(defn- dsar-in-progress! [conn xid intake]
  (dsar/file-request! conn {:external-id xid
                            :partner (partner-eid (d/db conn) "P-subject")
                            :kind :access
                            :received-at #inst "2026-06-01"
                            :deadline-days 30
                            :changed-by-uid intake})
  (dsar/advance-state! conn {:request xid :to :verifying-identity
                             :changed-by-uid intake :reason :dsar-identity-check})
  (dsar/advance-state! conn {:request xid :to :in-progress
                             :changed-by-uid intake :reason :dsar-started}))

(deftest effective-from-scopes-a-policy-to-transitions-after-a-cutover
  (let [conn (bootstrap)
        _ (adoc/create-doc! conn {:code "BUNDLE-2" :type :dsar-bundle
                                  :storage-uri "s3://docs/bundle-2"
                                  :uploaded-by-uid "bob"})
        bundle (adoc/by-code (d/db conn) "BUNDLE-2")
        policy (dsar-fulfil-policy (d/db conn))]
    (is (some? policy) "the seeded :no-self-approval row is there to date-scope")
    (d/transact conn [[:db/add policy :kontor.approval-policy/effective-from
                       #inst "2030-01-01"]])
    (dsar-in-progress! conn "DSAR-CUTOVER" "alice")
    (testing "a transition dated before the cutover is not judged by the policy —
              alice may fulfil her own intake, which is what lets an existing
              book turn a fail-closed control on without stranding its history"
      (dsar/advance-state! conn {:request "DSAR-CUTOVER" :to :fulfilled
                                 :changed-by-uid "alice"
                                 :reason :dsar-fulfilled
                                 :supporting-doc bundle})
      (is (= :fulfilled (:kontor.dsar-request/state
                         (d/pull (d/db conn) [:kontor.dsar-request/state]
                                 (dsar/by-external-id (d/db conn) "DSAR-CUTOVER"))))))))

(deftest effective-from-in-the-past-still-governs
  (let [conn (bootstrap)
        _ (adoc/create-doc! conn {:code "BUNDLE-3" :type :dsar-bundle
                                  :storage-uri "s3://docs/bundle-3"
                                  :uploaded-by-uid "bob"})
        bundle (adoc/by-code (d/db conn) "BUNDLE-3")
        policy (dsar-fulfil-policy (d/db conn))]
    (d/transact conn [[:db/add policy :kontor.approval-policy/effective-from
                       #inst "2020-01-01"]])
    (dsar-in-progress! conn "DSAR-LIVE" "alice")
    (testing "a cutover already past leaves the control fully in force"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (dsar/advance-state! conn {:request "DSAR-LIVE" :to :fulfilled
                                      :changed-by-uid "alice"
                                      :reason :dsar-fulfilled
                                      :supporting-doc bundle}))))))

(deftest in-effect?-is-pure-and-defaults-to-governing
  (testing "no cutover set = governs everything (the pre-ADR-153 behaviour)"
    (is (true? (sm/in-effect? {} #inst "1999-01-01"))))
  (testing "the cutover instant itself is INSIDE the policy — 'from' is inclusive"
    (is (true? (sm/in-effect? {:kontor.approval-policy/effective-from #inst "2026-01-01"}
                              #inst "2026-01-01"))))
  (is (false? (sm/in-effect? {:kontor.approval-policy/effective-from #inst "2026-01-01"}
                             #inst "2025-12-31"))))

;; ============================================================================
;; 5. Backfilling attribution rather than exempting it
;; ============================================================================

(deftest backfill-makes-a-pre-attribution-entity-approvable-again
  (let [conn (bootstrap)
        ;; A row as an older kontor would have written it: no create-uid. Raw
        ;; d/transact because the builders now refuse to produce this shape.
        _ (d/transact conn [{:kontor.audit-doc/code "LEGACY-DOC"
                             :kontor.audit-doc/type :customer-email
                             :kontor.audit-doc/storage-uri "s3://docs/legacy"
                             :kontor.audit-doc/uploaded-at #inst "2024-01-01"
                             :kontor.audit-doc/privilege :attorney-client}])
        doc (adoc/by-code (d/db conn) "LEGACY-DOC")
        support (adoc/by-code (d/db conn) "SUPPORT-1")]
    (testing "before the backfill the fail-closed rule refuses — correctly: there
              is nothing to compare an approver against"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (adoc/reclassify-privilege! conn {:doc doc :to :none
                                             :changed-by-uid (actor-eid (d/db conn) "bob")
                                             :reason :privilege-waived
                                             :reason-note "n"
                                             :supporting-doc support}))))
    (testing "the discovery query finds it by a probe attribute"
      (is (contains? (set (actor/entities-missing-create-uid
                           (d/db conn) :kontor.audit-doc/code))
                     doc)))
    (actor/backfill-create-uid! conn {:uid "pre-2026-migration"
                                      :name "Backfilled attribution"
                                      :probe-attrs [:kontor.audit-doc/code]})
    (testing "one commit later the document is approvable by a real second actor"
      (adoc/reclassify-privilege! conn {:doc doc :to :none
                                        :changed-by-uid (actor-eid (d/db conn) "bob")
                                        :reason :privilege-waived
                                        :reason-note "waived after migration"
                                        :supporting-doc support})
      (is (= :none (adoc/privilege-of (d/db conn) doc))))
    (testing "and an auditor can tell reconstructed attribution from recorded —
              the backfill actor is :unregistered, which is the entire reason
              this is a backfill and not a :legacy exemption flag"
      (is (= :unregistered
             (:kontor.actor/kind (actor/actor (d/db conn) "pre-2026-migration")))))))

(deftest backfill-refuses-to-invent-an-actor-uid
  (let [conn (bootstrap)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":uid is required"
                          (actor/backfill-create-uid! conn {:probe-attrs [:kontor.audit-doc/code]})))))

;; ============================================================================
;; 6. The two seams agree
;; ============================================================================

(deftest attribution-guard-mirrors-the-gate
  (testing "the writer-side literal and the gate-side set are the same — a
            family that lives on only one seam means the mandatory guard and
            the advisory one disagree (kontor.governance ns docstring)"
    (is (= actor/non-retractable-attrs gov/non-retractable-attribution-attrs))))
