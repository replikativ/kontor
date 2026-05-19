(ns kontor.hr.consent-test
  "Tests for ADR-094 — `:consent/*` schema + `kontor.hr.consent`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.hr.consent :as consent]
            [kontor.hr.core :as hr]
            [kontor.hr.person :as person])
  (:import [java.util Date]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(deftest canonical-categories-includes-new-hr-values
  (testing "ADR-094 canonical categories include the 8 new HR values"
    (let [s audit-doc/canonical-category-set]
      (is (contains? s :hr-track-record))
      (is (contains? s :hr-activity-monitoring))
      (is (contains? s :hr-activity-content))
      (is (contains? s :hr-communications))
      (is (contains? s :hr-background-check))
      (is (contains? s :hr-compensation-negotiation))
      (is (contains? s :hr-grievance))
      (is (contains? s :hr-monitoring-consent))
      ;; pre-existing values still there
      (is (contains? s :payroll-filing))
      (is (contains? s :hr-personnel))
      (is (contains? s :hr-medical))
      (is (contains? s :hr-immigration)))))

(deftest grant-creates-active-consent-row
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-1"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1985-01-01"})
        jane (hr/person-by-external-id (d/db conn) "P-1")
        report (consent/grant!
                conn {:code "C-jane-track-record"
                      :subject jane
                      :scope :hr-track-record
                      :legal-basis :bdsg-26-1-employment
                      :granted-at #inst "2026-01-15"})
        db (:db-after report)
        eid (consent/by-code db "C-jane-track-record")
        row (d/pull db '[*] eid)]
    (testing "row created with the required fields"
      (is (some? eid))
      (is (= jane (-> row :consent/subject :db/id)))
      (is (= :hr-track-record (:consent/scope row)))
      (is (= :bdsg-26-1-employment (:consent/legal-basis row)))
      (is (= :active (:consent/state row)))
      (is (= #inst "2026-01-15" (:consent/granted-at row)))
      (is (nil? (:consent/withdrawn-at row))))))

(deftest active-at?-respects-grant-and-withdrawal-windows
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-1"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1985-01-01"})
        jane (hr/person-by-external-id (d/db conn) "P-1")
        _ (consent/grant!
           conn {:code "C-1"
                 :subject jane
                 :scope :hr-activity-monitoring
                 :legal-basis :bdsg-26-4-collective-agreement
                 :granted-at #inst "2026-01-15"})
        db1 (d/db conn)]
    (testing "before grant — not active"
      (is (false? (consent/active-at? db1 jane :hr-activity-monitoring
                                      #inst "2026-01-14"))))
    (testing "after grant, before withdrawal — active"
      (is (true? (consent/active-at? db1 jane :hr-activity-monitoring
                                     #inst "2026-06-01")))
      (is (true? (consent/active-at? db1 jane :hr-activity-monitoring
                                     #inst "2026-12-31"))))
    (testing "different scope — not active (scopes are independent)"
      (is (false? (consent/active-at? db1 jane :hr-track-record
                                      #inst "2026-06-01"))))

    ;; Now withdraw and reassert.
    (let [_ (consent/withdraw!
             conn {:consent "C-1"
                   :changed-by-uid jane ; person is also used as actor here
                   :withdrawn-at #inst "2026-07-01"
                   :reason-note "subject revoked consent"})
          db2 (d/db conn)]
      (testing "before withdrawal — still active under d/db AFTER withdrawal recorded"
        (is (true? (consent/active-at? db2 jane :hr-activity-monitoring
                                       #inst "2026-06-30"))))
      (testing "AT withdrawal — no longer active (open boundary)"
        (is (false? (consent/active-at? db2 jane :hr-activity-monitoring
                                        #inst "2026-07-01"))))
      (testing "after withdrawal — not active"
        (is (false? (consent/active-at? db2 jane :hr-activity-monitoring
                                        #inst "2026-08-01")))))))

(deftest supersede-deactivates-old-and-creates-new
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-1"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1985-01-01"})
        jane (hr/person-by-external-id (d/db conn) "P-1")
        _ (consent/grant!
           conn {:code "C-old"
                 :subject jane
                 :scope :hr-track-record
                 :legal-basis :bdsg-26-1-employment
                 :granted-at #inst "2026-01-15"})
        report (consent/supersede!
                conn {:old "C-old"
                      :new {:code "C-new"
                            :subject jane
                            :scope :hr-track-record
                            :legal-basis :works-agreement
                            :granted-at #inst "2027-01-01"}
                      :changed-by-uid jane
                      :reason-note "renewed under new Betriebsvereinbarung"})
        db (:db-after report)
        old (d/pull db '[*] (consent/by-code db "C-old"))
        new (d/pull db '[*] (consent/by-code db "C-new"))]
    (testing "old row marked superseded"
      (is (= :superseded (:consent/state old))))
    (testing "new row active"
      (is (= :active (:consent/state new)))
      (is (= :works-agreement (:consent/legal-basis new))))
    (testing "for-subject returns both, sorted by :granted-at"
      (let [rows (consent/for-subject db jane)]
        (is (= 2 (count rows)))
        (is (= ["C-old" "C-new"] (map :consent/code rows)))))))

(deftest for-subject-returns-empty-for-unknown-person
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-1"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1985-01-01"})
        jane (hr/person-by-external-id (d/db conn) "P-1")]
    (is (= [] (consent/for-subject (d/db conn) jane)))))
