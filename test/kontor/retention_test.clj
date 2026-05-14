(ns kontor.retention-test
  "ADR-050: retention policy + sweeper.

   Covers:
   - define-policy! writes a :draft policy + status-history.
   - activate-policy! enforces ADR-038 (:supporting-doc + :reason-note).
   - policy-for resolves the active policy; effective-dating works;
     jurisdiction-specific beats global.
   - retention-deadline / eligible? — aged-past-deadline semantics.
   - sweep! produces work-items; dry-run applies nothing.
   - hold-blocks-expiry: place hold → sweep marks :blocked-by-hold? →
     apply-expiry! is structurally refused → release → applies.
   - :purge removes the entity; :anonymize purges only the listed
     fields."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.legal-hold :as lhold]
            [kontor.retention :as ret]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn
                [{:partner/external-id "U-counsel" :partner/name "Counsel C"}
                 {:partner/external-id "U-records" :partner/name "Records Manager"}
                 ;; Two jurisdictions for the jurisdiction-precedence test.
                 {:db/id "country-de" :country/code "DE" :country/name "Germany"}
                 {:db/id "country-us" :country/code "US" :country/name "United States"}
                 ;; The retention schedule doc (ADR-038 :supporting-doc).
                 {:db/id "doc-schedule"
                  :audit-doc/code "RETENTION-SCHEDULE-2026"
                  :audit-doc/type :retention-schedule
                  :audit-doc/uploaded-at #inst "2026-01-01"}
                 ;; Hold preservation order.
                 {:db/id "doc-hold"
                  :audit-doc/code "HOLD-ORDER-001"
                  :audit-doc/type :legal-hold-order
                  :audit-doc/uploaded-at #inst "2026-05-13"}
                 {:db/id "doc-release"
                  :audit-doc/code "HOLD-RELEASE-001"
                  :audit-doc/type :legal-hold-release
                  :audit-doc/uploaded-at #inst "2026-06-01"}])
    conn))

(defn- uid [db actor]
  (d/q '[:find ?e . :in $ ?xid
         :where [?e :partner/external-id ?xid]]
       db (str "U-" actor)))

(defn- adoc-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :audit-doc/code ?c]] db code))

(defn- country-eid [db iso]
  (d/q '[:find ?e . :in $ ?c :where [?e :country/code ?c]] db iso))

;; Seed an audit-doc whose :uploaded-at is the retention clock anchor.
(defn- seed-doc! [conn code uploaded-at]
  (d/transact conn [{:audit-doc/code code
                     :audit-doc/type :customer-email
                     :audit-doc/title (str "Doc " code)
                     :audit-doc/description "sensitive contents"
                     :audit-doc/uploaded-at uploaded-at}])
  (adoc-eid (d/db conn) code))

;; Define + activate a :purge policy on :audit-doc, anchored on
;; :audit-doc/uploaded-at.
(defn- active-purge-policy! [conn {:keys [code duration-years effective-from
                                          effective-until jurisdiction]
                                   :or {duration-years 7
                                        effective-from #inst "2000-01-01"}}]
  (ret/define-policy! conn
                      (cond-> {:code code
                               :applies-to [:audit-doc]
                               :duration-years duration-years
                               :triggered-by :audit-doc/uploaded-at
                               :expiry-action :purge
                               :effective-from effective-from
                               :legal-basis "Test policy"
                               :changed-by-uid (uid (d/db conn) "records")}
                        effective-until (assoc :effective-until effective-until)
                        jurisdiction    (assoc :jurisdiction jurisdiction)))
  (let [policy-eid (ret/by-code (d/db conn) code)]
    (ret/activate-policy! conn
                          {:policy-eid policy-eid
                           :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                           :reason-note "Statutory retention period."
                           :changed-by-uid (uid (d/db conn) "records")})
    policy-eid))

;; ============================================================================
;; define-policy! / activate-policy!
;; ============================================================================

(deftest define-policy-writes-draft-and-status-history
  (let [conn (bootstrap)
        _ (ret/define-policy! conn
                              {:code "DE-HGB-257"
                               :applies-to [:audit-doc :transaction]
                               :duration-years 10
                               :triggered-by :audit-doc/uploaded-at
                               :expiry-action :purge
                               :effective-from #inst "2025-01-01"
                               :legal-basis "HGB §257"
                               :changed-by-uid (uid (d/db conn) "records")})
        db (d/db conn)
        policy-eid (ret/by-code db "DE-HGB-257")
        policy (d/pull db '[*] policy-eid)
        history (d/q '[:find [?h ...]
                       :in $ ?e
                       :where
                       [?h :status-history/entity ?e]
                       [?h :status-history/facet :retention-policy/state]]
                     db policy-eid)]
    (is (= :draft (:retention-policy/state policy)))
    (is (= 10 (:retention-policy/duration-years policy)))
    (is (= #{:audit-doc :transaction} (set (:retention-policy/applies-to policy))))
    (is (= 1 (count history)) "Exactly one :status-history row for nil → :draft.")))

(deftest activate-requires-supporting-doc
  (let [conn (bootstrap)
        _ (ret/define-policy! conn
                              {:code "P-NEEDS-DOC"
                               :applies-to [:audit-doc]
                               :duration-years 7
                               :triggered-by :audit-doc/uploaded-at
                               :expiry-action :purge
                               :effective-from #inst "2025-01-01"
                               :legal-basis "Test"
                               :changed-by-uid (uid (d/db conn) "records")})
        policy-eid (ret/by-code (d/db conn) "P-NEEDS-DOC")]
    (testing "activate without :supporting-doc is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":supporting-doc required"
           (ret/activate-policy! conn
                                 {:policy-eid policy-eid
                                  :reason-note "no doc"
                                  :changed-by-uid (uid (d/db conn) "records")}))))
    (testing "activate without :reason-note is rejected (ADR-038)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":reason-note required"
           (ret/activate-policy! conn
                                 {:policy-eid policy-eid
                                  :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                                  :changed-by-uid (uid (d/db conn) "records")}))))
    (testing "activate with both succeeds"
      (ret/activate-policy! conn
                            {:policy-eid policy-eid
                             :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                             :reason-note "Statutory."
                             :changed-by-uid (uid (d/db conn) "records")})
      (is (= :active (:retention-policy/state
                      (d/pull (d/db conn) [:retention-policy/state] policy-eid)))))))

;; ============================================================================
;; policy-for / retention-deadline / eligible?
;; ============================================================================

(deftest policy-for-resolves-active-policy
  (let [conn (bootstrap)
        policy-eid (active-purge-policy! conn {:code "P-GLOBAL"})]
    (testing "policy-for finds the active policy for :audit-doc"
      (is (= policy-eid (ret/policy-for (d/db conn) :audit-doc {}))))
    (testing "policy-for returns nil for an entity-type with no policy"
      (is (nil? (ret/policy-for (d/db conn) :transaction {}))))))

(deftest effective-dating-picks-vintage-policy
  (let [conn (bootstrap)
        ;; Old policy: 5y, effective 2000–2025.
        _ (active-purge-policy! conn {:code "P-OLD"
                                      :duration-years 5
                                      :effective-from #inst "2000-01-01"
                                      :effective-until #inst "2025-01-01"})
        ;; New policy: 10y, effective 2025 onward.
        new-eid (active-purge-policy! conn {:code "P-NEW"
                                            :duration-years 10
                                            :effective-from #inst "2025-01-01"})]
    (testing "as-of in the new window resolves the new policy"
      (is (= new-eid (ret/policy-for (d/db conn) :audit-doc
                                     {:as-of #inst "2026-06-01"}))))
    (testing "as-of in the old window resolves the old policy"
      (is (= (ret/by-code (d/db conn) "P-OLD")
             (ret/policy-for (d/db conn) :audit-doc
                             {:as-of #inst "2010-06-01"}))))))

(deftest jurisdiction-specific-beats-global
  (let [conn (bootstrap)
        de (country-eid (d/db conn) "DE")
        _ (active-purge-policy! conn {:code "P-GLOBAL-J"})
        de-eid (active-purge-policy! conn {:code "P-DE" :jurisdiction de})]
    (testing "with a DE jurisdiction, the DE-specific policy wins"
      (is (= de-eid (ret/policy-for (d/db conn) :audit-doc {:jurisdiction de}))))
    (testing "with no jurisdiction, the global policy resolves"
      (is (= (ret/by-code (d/db conn) "P-GLOBAL-J")
             (ret/policy-for (d/db conn) :audit-doc {}))))))

(deftest deadline-and-eligibility
  (let [conn (bootstrap)
        policy-eid (active-purge-policy! conn {:code "P-7Y" :duration-years 7})
        ;; A doc uploaded 2018-01-01 → deadline 2025-01-01.
        old-doc (seed-doc! conn "DOC-OLD" #inst "2018-01-01")
        ;; A doc uploaded 2024-01-01 → deadline 2031-01-01.
        new-doc (seed-doc! conn "DOC-NEW" #inst "2024-01-01")]
    (testing "retention-deadline = anchor + duration-years"
      (is (= #inst "2025-01-01" (ret/retention-deadline (d/db conn) old-doc policy-eid)))
      (is (= #inst "2031-01-01" (ret/retention-deadline (d/db conn) new-doc policy-eid))))
    (testing "eligible? true once aged past the deadline"
      (is (ret/eligible? (d/db conn) old-doc policy-eid {:as-of #inst "2026-06-01"})))
    (testing "eligible? false before the deadline"
      (is (not (ret/eligible? (d/db conn) new-doc policy-eid {:as-of #inst "2026-06-01"}))))))

;; ============================================================================
;; sweep! / sweep-and-apply!
;; ============================================================================

(deftest sweep-produces-work-items-and-applies
  (let [conn (bootstrap)
        _ (active-purge-policy! conn {:code "P-SWEEP" :duration-years 7})
        old-doc (seed-doc! conn "DOC-EXPIRED" #inst "2017-01-01")
        _new-doc (seed-doc! conn "DOC-FRESH" #inst "2025-01-01")
        items (ret/sweep! (d/db conn) {:entity-type :audit-doc
                                       :as-of #inst "2026-06-01"})]
    (testing "sweep! flags only the aged-past-deadline doc"
      (is (= 1 (count items)))
      (is (= old-doc (:entity-eid (first items))))
      (is (= :purge (:action (first items))))
      (is (false? (:blocked-by-hold? (first items)))))
    (testing "dry-run applies nothing"
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc
                                          :as-of #inst "2026-06-01"
                                          :dry-run? true})]
        (is (empty? (:applied r)))
        (is (= 1 (count (:would-apply r))))
        (is (some? (adoc-eid (d/db conn) "DOC-EXPIRED")))))
    (testing "sweep-and-apply! purges the expired doc"
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc
                                          :as-of #inst "2026-06-01"})]
        (is (= 1 (count (:applied r))))
        (is (empty? (:blocked r)))
        (is (nil? (adoc-eid (d/db conn) "DOC-EXPIRED"))
            "Expired doc is purged.")
        (is (some? (adoc-eid (d/db conn) "DOC-FRESH"))
            "Fresh doc is untouched.")))))

;; ============================================================================
;; The headline: hold-blocks-expiry
;; ============================================================================

(deftest legal-hold-blocks-retention-expiry
  (let [conn (bootstrap)
        _ (active-purge-policy! conn {:code "P-HOLD-TEST" :duration-years 7})
        held-doc (seed-doc! conn "DOC-HELD" #inst "2017-01-01")
        ;; Place a legal hold scoping the expired doc.
        _ (lhold/place! conn
                        {:code "HOLD-RET"
                         :matter-name "Retention-vs-hold test"
                         :issued-by-uid (uid (d/db conn) "counsel")
                         :issued-at #inst "2026-05-13"
                         :supporting-doc (adoc-eid (d/db conn) "HOLD-ORDER-001")
                         :reason-note "Preserve despite retention expiry."
                         :scope-eids [held-doc]})]
    (testing "sweep! marks the held doc :blocked-by-hold?"
      (let [items (ret/sweep! (d/db conn) {:entity-type :audit-doc
                                           :as-of #inst "2026-06-01"})
            held-item (first (filter #(= held-doc (:entity-eid %)) items))]
        (is (some? held-item))
        (is (true? (:blocked-by-hold? held-item)))))
    (testing "sweep-and-apply! skips the held doc; it stays in the DB"
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc
                                          :as-of #inst "2026-06-01"})]
        (is (empty? (:applied r)))
        (is (= 1 (count (:blocked r))))
        (is (some? (adoc-eid (d/db conn) "DOC-HELD")))))
    (testing "apply-expiry! is STRUCTURALLY refused even called directly on a held doc"
      ;; The load-bearing guarantee: apply-expiry! runs the
      ;; validators directly, so the ADR-049 hold-middleware fires.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"blocked by active legal hold"
           (ret/apply-expiry! conn {:entity-eid held-doc
                                    :policy-eid (ret/by-code (d/db conn) "P-HOLD-TEST")
                                    :action :purge})))
      ;; P1-1 (research note 32): the :type must be reachable on
      ;; (ex-data e) directly — not buried in (.getCause e).
      (is (= :legal-hold/purge-blocked
             (try (ret/apply-expiry! conn {:entity-eid held-doc
                                           :policy-eid (ret/by-code (d/db conn) "P-HOLD-TEST")
                                           :action :purge})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    (:type (ex-data e)))))
          "exception :type is on (ex-data e), not double-wrapped"))
    (testing "after release, the next sweep applies the expiry"
      (lhold/release! conn
                      {:hold-eid (lhold/by-code (d/db conn) "HOLD-RET")
                       :released-by-uid (uid (d/db conn) "records")
                       :supporting-doc (adoc-eid (d/db conn) "HOLD-RELEASE-001")
                       :reason-note "Matter closed; retention may proceed."})
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc
                                          :as-of #inst "2026-06-01"})]
        (is (= 1 (count (:applied r))))
        (is (nil? (adoc-eid (d/db conn) "DOC-HELD"))
            "Released doc is now purged on the next sweep.")))))

;; ============================================================================
;; P1-2 — :applies-to cross-check guards against a cross-namespace anchor
;; ============================================================================

(deftest cross-namespace-anchor-does-not-sweep-unintended-types
  ;; A policy :applies-to [:audit-doc] but :triggered-by an attribute
  ;; in a DIFFERENT namespace (:status-history/changed-at). Without the
  ;; :applies-to cross-check, candidate-eids would enumerate every
  ;; :status-history row in the DB as a candidate. The guard filters
  ;; them out — a :status-history row carries no :audit-doc/* attr.
  (let [conn (bootstrap)
        _ (ret/define-policy! conn
                              {:code "P-CROSS-NS"
                               :applies-to [:audit-doc]
                               :duration-years 1
                               :triggered-by :status-history/changed-at
                               :expiry-action :purge
                               :effective-from #inst "2000-01-01"
                               :legal-basis "Cross-namespace anchor test"
                               :changed-by-uid (uid (d/db conn) "records")})
        policy-eid (ret/by-code (d/db conn) "P-CROSS-NS")
        _ (ret/activate-policy! conn
                                {:policy-eid policy-eid
                                 :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                                 :reason-note "Activate."
                                 :changed-by-uid (uid (d/db conn) "records")})]
    ;; The DB has :status-history rows (every define/activate wrote
    ;; some), all well past a 1-year deadline relative to 2026 — but
    ;; none is an :audit-doc, so none is a candidate.
    (testing "the cross-namespace anchor sweeps zero entities"
      (is (empty? (ret/sweep! (d/db conn) {:entity-type :audit-doc
                                           :as-of #inst "2026-06-01"}))))))

;; ============================================================================
;; :anonymize action
;; ============================================================================

(deftest anonymize-purges-only-listed-fields
  (let [conn (bootstrap)
        ;; Anonymize policy: purge :title + :description, keep :code.
        _ (ret/define-policy! conn
                              {:code "P-ANON"
                               :applies-to [:audit-doc]
                               :duration-years 3
                               :triggered-by :audit-doc/uploaded-at
                               :expiry-action :anonymize
                               :anonymize-fields [:audit-doc/title
                                                  :audit-doc/description]
                               :effective-from #inst "2000-01-01"
                               :legal-basis "GDPR Art. 5(1)(e) — anonymize but keep"
                               :changed-by-uid (uid (d/db conn) "records")})
        policy-eid (ret/by-code (d/db conn) "P-ANON")
        _ (ret/activate-policy! conn
                                {:policy-eid policy-eid
                                 :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                                 :reason-note "Anonymization schedule."
                                 :changed-by-uid (uid (d/db conn) "records")})
        old-doc (seed-doc! conn "DOC-ANON" #inst "2020-01-01")]
    (testing "sweep-and-apply! anonymizes the aged doc"
      (let [r (ret/sweep-and-apply! conn {:entity-type :audit-doc
                                          :as-of #inst "2026-06-01"})]
        (is (= 1 (count (:applied r))))))
    (testing "PII fields are purged; identity field survives"
      (let [doc (d/pull (d/db conn) '[*] old-doc)]
        (is (= "DOC-ANON" (:audit-doc/code doc)) ":code survives.")
        (is (= :customer-email (:audit-doc/type doc)) ":type survives.")
        (is (nil? (:audit-doc/title doc)) ":title purged.")
        (is (nil? (:audit-doc/description doc)) ":description purged.")))))

;; ============================================================================
;; supersede
;; ============================================================================

(deftest supersede-makes-policy-terminal
  (let [conn (bootstrap)
        policy-eid (active-purge-policy! conn {:code "P-SUPERSEDE"})]
    (testing "supersede without :supporting-doc is rejected (ADR-038, P1-3 fix)"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":supporting-doc required"
           (ret/supersede-policy! conn
                                  {:policy-eid policy-eid
                                   :changed-by-uid (uid (d/db conn) "records")
                                   :reason-note "no doc"}))))
    (testing "supersede with both succeeds; policy becomes terminal"
      (ret/supersede-policy! conn
                             {:policy-eid policy-eid
                              :changed-by-uid (uid (d/db conn) "records")
                              :supporting-doc (adoc-eid (d/db conn) "RETENTION-SCHEDULE-2026")
                              :reason-note "Replaced by 2027 schedule."})
      (is (= :superseded (:retention-policy/state
                          (d/pull (d/db conn) [:retention-policy/state] policy-eid))))
      (is (nil? (ret/policy-for (d/db conn) :audit-doc {}))))))
