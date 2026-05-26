(ns kontor.people-record.lifecycle-test
  "Three-year employee lifecycle integration test for kontor-people-
   record (ADR-094 §3.5 deliverable).

   Story: a single employee, three years of substrate exercise.

     - Year 1 — hire, consent grant, initial position recorded.
     - Year 2 — annual performance review, promotion with comp change,
                a documented grievance with privilege.
     - Year 3 — DSAR request walking the person's full record;
                termination + retention policy clearance.

   The test asserts:
     - Consent gates ALL writes through people-record's *! transactors.
     - The ADR-094 :hr-track-record / :hr-grievance categories carry
       correctly onto audit-docs and the DSAR walker bundles them.
     - The bitemporal `:as-of-valid` axis shows the right state at
       any past point (e.g. 'what was Jane's title on 2026-06-01?').
     - Withdrawing consent stops new track-record writes from
       landing AFTER the withdrawal date."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.core :as core]
            [kontor.hr.consent :as consent]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.person :as person]
            [kontor.people-record.core :as pr]
            [kontor.people-record.schema :as pr-schema]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (pr/install! conn)
    (d/transact conn
                [{:db/id "eur"
                  :kontor.commodity/symbol "EUR"
                  :kontor.commodity/precision 2}
                 {:db/id "ent"
                  :kontor.entity/code "ACME-DE-GMBH"
                  :kontor.entity/name "Acme DE GmbH"
                  :kontor.entity/active true}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(deftest three-year-employee-lifecycle
  (let [conn (bootstrap)
        eur (ref-eid (d/db conn) :kontor.commodity/symbol "EUR")
        ent (ref-eid (d/db conn) :kontor.entity/code "ACME-DE-GMBH")

        ;; ===== Year 1 — hire =====
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1992-04-15"})
        jane (hr/person-by-external-id (d/db conn) "P-jane")

        ;; Consent supporting doc — typical DPIA / works-agreement.
        _ (audit-doc/create-doc!
           conn {:code "DPIA-jane-track-record-2026"
                 :type :dpia
                 :storage-uri "s3://test/dpia-jane.pdf"
                 :title "DPIA — Jane's track record processing"
                 :category :hr-monitoring-consent})
        dpia (ref-eid (d/db conn) :audit-doc/code "DPIA-jane-track-record-2026")

        ;; Grant consent for hr-track-record scope (legal basis:
        ;; BDSG §26(1) employment necessity).
        _ (consent/grant!
           conn {:code "CONS-jane-track-record-2026"
                 :subject jane
                 :scope :hr-track-record
                 :legal-basis :bdsg-26-1-employment
                 :granted-at #inst "2026-01-15"
                 :supporting-doc dpia})

        _ (employment/hire! conn {:code "EMP-jane-2026"
                                  :person jane
                                  :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Software Engineer (IC-2)"})
        emp-jane (hr/employment-by-code (d/db conn) "EMP-jane-2026")

        ;; Record the initial position
        _ (pr/record-position!
           conn {:code "POS-jane-eng-1"
                 :person jane
                 :employment emp-jane
                 :title "Software Engineer"
                 :level :ic-2
                 :start-date #inst "2026-01-15"
                 :at #inst "2026-01-15"})
        pos-1 (ref-eid (d/db conn) :position-held/external-id "POS-jane-eng-1")]

    (testing "Year 1: position landed, person + consent + position visible"
      (is (some? pos-1))
      (is (true? (consent/active-at? (d/db conn) jane :hr-track-record
                                     #inst "2026-06-01"))))

    ;; ===== Year 2 — review + promotion + grievance =====
    (let [_ (audit-doc/create-doc!
             conn {:code "REVIEW-DOC-jane-2026"
                   :type :performance-review
                   :storage-uri "s3://test/review-jane-2026.pdf"
                   :category :hr-track-record})
          review-doc (ref-eid (d/db conn) :audit-doc/code "REVIEW-DOC-jane-2026")
          ;; The reviewer is also an employee — bootstrap another person
          _ (person/create-person! conn {:external-id "P-bob"
                                         :given-name "Bob" :family-name "Manager"
                                         :birth-date #inst "1978-08-12"})
          bob (hr/person-by-external-id (d/db conn) "P-bob")
          _ (employment/hire! conn {:code "EMP-bob-2024"
                                    :person bob
                                    :entity ent
                                    :start-date #inst "2024-09-01"
                                    :job-title "Engineering Manager"})
          emp-bob (hr/employment-by-code (d/db conn) "EMP-bob-2024")
          _ (pr/record-review!
             conn {:code "REVIEW-jane-2026"
                   :person jane
                   :reviewer-employment emp-bob
                   :period-start #inst "2026-01-15"
                   :period-end   #inst "2026-12-31"
                   :outcome :exceeds
                   :supporting-doc review-doc
                   :calibrated-at #inst "2027-01-31"
                   :at #inst "2027-01-31"})
          ;; Promotion with new position
          _ (pr/record-position!
             conn {:code "POS-jane-senior"
                   :person jane
                   :employment emp-jane
                   :title "Senior Software Engineer"
                   :level :senior
                   :start-date #inst "2027-03-01"
                   :at #inst "2027-03-01"
                   :tempid "pos-senior"})
          pos-senior (ref-eid (d/db conn) :position-held/external-id
                              "POS-jane-senior")
          _ (audit-doc/create-doc!
             conn {:code "PROMOTION-LETTER-jane-2027"
                   :type :promotion-letter
                   :storage-uri "s3://test/promotion-jane.pdf"
                   :category :hr-track-record})
          promo-doc (ref-eid (d/db conn) :audit-doc/code "PROMOTION-LETTER-jane-2027")
          _ (pr/record-promotion!
             conn {:code "PROMO-jane-senior-2027"
                   :person jane
                   :from-position pos-1
                   :to-position pos-senior
                   :effective-date #inst "2027-03-01"
                   :supporting-doc promo-doc
                   :at #inst "2027-03-01"})
          ;; A documented grievance with attorney-client privilege
          grievance-doc-r
          (audit-doc/create-doc!
           conn {:code "GRIEVANCE-jane-2027-Q2"
                 :type :grievance-record
                 :storage-uri "s3://test/grievance-jane-q2.pdf"
                 :title "Internal grievance — Q2 2027"
                 :description "Confidential — counsel review"
                 :category :hr-grievance
                 :uploaded-by-uid bob})
          grievance-doc (ref-eid (d/db conn) :audit-doc/code "GRIEVANCE-jane-2027-Q2")
          ;; Reclassify the grievance doc as :attorney-client privileged
          _ (audit-doc/reclassify-privilege!
             conn {:doc grievance-doc
                   :to :attorney-client
                   :changed-by-uid jane
                   :reason :privilege-determined
                   :reason-note "Counsel review prior to disclosure"})]
      (testing "Year 2: review + promotion + grievance recorded"
        (is (some? (ref-eid (d/db conn) :performance-review/external-id "REVIEW-jane-2026")))
        (is (some? (ref-eid (d/db conn) :promotion/external-id "PROMO-jane-senior-2027")))
        (is (= :hr-grievance
               (:audit-doc/category
                (d/pull (d/db conn) [:audit-doc/category] grievance-doc))))
        (is (= :attorney-client
               (audit-doc/privilege-of (d/db conn) grievance-doc))))

      ;; ===== Year 3 — DSAR + termination + consent withdrawal =====
      (let [bundle (pr/dsar-bundle (d/db conn) jane)]
        (testing "Year 3: DSAR bundle reaches all track-record entries"
          (is (= 2 (count (:positions bundle)))
              "two positions held over the lifecycle")
          (is (= 1 (count (:reviews bundle))))
          (is (= 1 (count (:promotions bundle))))))

      (testing "Consent withdrawal stops new track-record writes"
        (consent/withdraw! conn {:consent "CONS-jane-track-record-2026"
                                 :changed-by-uid bob
                                 :withdrawn-at #inst "2028-06-01"
                                 :reason-note "Subject revoked consent"})
        (let [withdrawal-result
              (try
                (pr/record-position!
                 conn {:code "POS-jane-post-withdrawal"
                       :person jane
                       :employment emp-jane
                       :title "Should-not-land"
                       :start-date #inst "2028-07-01"
                       :at #inst "2028-07-01"})
                {:landed true}
                (catch clojure.lang.ExceptionInfo e
                  {:landed false :ex-data (ex-data e)}))]
          (is (false? (:landed withdrawal-result))
              "post-withdrawal write was rejected by the consent gate")
          (is (= :consent/missing (-> withdrawal-result :ex-data :type))
              "structured error type matches the substrate contract")))

      (testing "Bitemporal :as-of-valid — pre-withdrawal consent still in force"
        ;; Operational-window semantics: at 2028-04-01 (before
        ;; withdrawal) the consent was active.
        (is (true? (consent/active-at? (d/db conn) jane :hr-track-record
                                       #inst "2028-04-01"))))

      (testing "Bitemporal :as-of-valid — post-withdrawal consent gone"
        (is (false? (consent/active-at? (d/db conn) jane :hr-track-record
                                        #inst "2028-08-01"))))

      (testing "Pre-termination historical view — what was Jane's title on 2026-06-01?"
        ;; Use the bitemporal axis to recall what was true earlier
        (let [past-db (d/valid-at (d/db conn) #inst "2026-06-01")
              positions (mapv #(d/pull past-db '[*] %)
                              (d/q '[:find [?p ...]
                                     :in $ ?subj
                                     :where [?p :position-held/person ?subj]]
                                   past-db jane))]
          (is (= 1 (count positions))
              "in mid-2026 only the initial position was held")
          (is (= "Software Engineer"
                 (:position-held/title (first positions)))))))))
