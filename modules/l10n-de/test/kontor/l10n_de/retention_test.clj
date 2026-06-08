(ns kontor.l10n-de.retention-test
  "Smoke test for the DE retention-policy seeds (ADR-094)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.retention :as retention]))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(deftest installs-the-seven-de-retention-seeds
  (let [conn (core/create-test-db)
        _ (retention/install! conn)
        db (d/db conn)]
    (testing "all 7 named seed rows present"
      (doseq [code ["DE-HGB257-hr-personnel"
                    "DE-BDSG-hr-track-record"
                    "DE-GefStoffV-hr-medical"
                    "DE-DSGVO-hr-activity-content-floor"
                    "DE-DSGVO-hr-monitoring-consent"
                    "DE-BetrVG-hr-grievance"
                    "DE-AO147-payroll-filing"]]
        (is (some? (ref-eid db :kontor.retention-policy/code code))
            (str "seed row " code))))

    (testing "the hr-activity-content seed is a 0-year :purge floor"
      (let [eid (ref-eid db :kontor.retention-policy/code
                         "DE-DSGVO-hr-activity-content-floor")
            row (d/pull db '[*] eid)]
        (is (= 0 (:kontor.retention-policy/duration-years row)))
        (is (= :purge (:kontor.retention-policy/expiry-action row)))
        (is (= :hr-activity-content (:kontor.retention-policy/category row)))))

    (testing "the hr-medical seed is a 30-year archive policy"
      (let [eid (ref-eid db :kontor.retention-policy/code
                         "DE-GefStoffV-hr-medical")
            row (d/pull db '[*] eid)]
        (is (= 30 (:kontor.retention-policy/duration-years row)))
        (is (= :archive-to-cold-storage
               (:kontor.retention-policy/expiry-action row)))))

    (testing ":kontor.country/code DE exists + is referenced"
      (let [de-eid (ref-eid db :kontor.country/code "DE")
            row (d/pull db [:kontor.retention-policy/jurisdiction]
                        (ref-eid db :kontor.retention-policy/code
                                 "DE-HGB257-hr-personnel"))]
        (is (some? de-eid))
        (is (= de-eid (-> row :kontor.retention-policy/jurisdiction :db/id))))))

  (testing "install! is idempotent — second call is a no-op"
    (let [conn (core/create-test-db)
          _ (retention/install! conn)
          db1 (d/db conn)
          tx-id-1 (:max-tx db1)
          _ (retention/install! conn)
          db2 (d/db conn)
          ;; Note: install! always transacts the :kontor.country/code "DE" row
          ;; once (idempotent on the unique attribute) so we don't
          ;; assert tx-id equality. The check is that no new
          ;; retention-policy rows landed.
          n2 (count
              (d/q '[:find [?e ...]
                     :where [?e :kontor.retention-policy/code]]
                   db2))]
      (is (= 7 n2)))))
