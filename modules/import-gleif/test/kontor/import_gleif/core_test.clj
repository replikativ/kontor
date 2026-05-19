(ns kontor.import-gleif.core-test
  "Tests for the GLEIF Golden Copy ingest companion."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.import-gleif.core :as gleif]))

(defn- fixture-resource [path]
  (io/resource (str "kontor/import_gleif/fixtures/" path)))

(def ^:private apple-lei      "HWUPKR0MPOU8FGXBT394")
(def ^:private microsoft-lei  "INR2EJN1ERAN0W5ZP974")
(def ^:private bmw-lei        "529900T8BM49AURSDO55")
(def ^:private vw-lei         "529900HNOAA1KXQJUQ27")
(def ^:private porsche-lei    "391200LFVPAFKMZIL632")
(def ^:private acme-lei       "984500BTC76GTQ4MM713")

(deftest valid-lei?-checks-20char-alphanumeric-shape
  (testing "20-char uppercase alphanumeric passes"
    (is (gleif/valid-lei? apple-lei))
    (is (gleif/valid-lei? bmw-lei))
    (is (gleif/valid-lei? porsche-lei)))
  (testing "wrong length fails"
    (is (not (gleif/valid-lei? "TOOSHORT")))
    (is (not (gleif/valid-lei? "TOOLONG-MORE-THAN-TWENTY-CHARS"))))
  (testing "lowercase fails (GLEIF mandates upper)"
    (is (not (gleif/valid-lei? (clojure.string/lower-case apple-lei)))))
  (testing "non-alphanumeric chars fail"
    (is (not (gleif/valid-lei? "HWUPKR0MPOU-FGXBT394")))
    (is (not (gleif/valid-lei? "HWUPKR0MPOU FGXBT394"))))
  (testing "nil and non-strings reject"
    (is (not (gleif/valid-lei? nil)))
    (is (not (gleif/valid-lei? 12345)))))

(deftest import-level-1-loads-entities-with-lei-and-status
  (let [conn (core/create-test-db)
        report (gleif/import-level-1!
                conn (fixture-resource "level1-sample.csv")
                {:source-id "gleif://test/2026-05-18"})]
    (testing "tx-report indicates the entities landed"
      (is (some? report)))
    (let [db (d/db conn)]
      (testing "every LEI resolves to a kontor :entity"
        (doseq [lei [apple-lei microsoft-lei bmw-lei vw-lei porsche-lei
                     acme-lei]]
          (is (some? (gleif/by-lei db lei))
              (str "lookup by LEI " lei))))

      (testing "the entity carries name, legal-form, registration-status"
        (let [apple (d/pull db '[*] (gleif/by-lei db apple-lei))]
          (is (= "APPLE INC." (:entity/name apple)))
          (is (= "LLC" (:entity/legal-form apple)))
          (is (= :issued (:entity/registration-status apple)))
          (is (= "gleif://test/2026-05-18" (:entity/source-id apple)))
          (is (true? (:entity/active apple)))))

      (testing ":entity/lei is unique-value — re-ingest does not duplicate"
        (gleif/import-level-1! conn (fixture-resource "level1-sample.csv")
                               {:source-id "gleif://test/2026-05-19"})
        (let [db (d/db conn)
              n (count (d/q '[:find [?e ...]
                              :where [?e :entity/lei]]
                            db))]
          (is (= 6 n) "re-ingest preserved entity count"))
        (let [apple (d/pull (d/db conn) '[*] (gleif/by-lei (d/db conn) apple-lei))]
          (testing "the source-id is updated"
            (is (= "gleif://test/2026-05-19" (:entity/source-id apple)))))))))

(deftest level-1-validation-report-classifies-rows
  (let [rows [{:LEI apple-lei :EntityLegalName "Apple"}
              {:LEI "" :EntityLegalName "Bad - no LEI"}
              {:LEI "TOOSHORT" :EntityLegalName "Bad - shape"}
              {:LEI bmw-lei :EntityLegalName "BMW"}]
        report (gleif/level-1-validation-report rows)]
    (is (= 4 (:total-count report)))
    (is (= 2 (:ok-count report)))
    (is (= 2 (count (:issues report))))
    (is (= #{:missing-lei :bad-shape}
           (set (map :status (:issues report)))))))

(deftest import-level-2-sets-parent-entity-ref-and-raw-lei-string
  (let [conn (core/create-test-db)
        _ (gleif/import-level-1! conn (fixture-resource "level1-sample.csv"))
        _ (gleif/import-level-2! conn (fixture-resource "level2-rr-sample.csv"))
        db (d/db conn)
        porsche (d/pull db '[* {:entity/parent-entity [:entity/lei :entity/name]}]
                        (gleif/by-lei db porsche-lei))
        acme (d/pull db '[* {:entity/parent-entity [:entity/lei :entity/name]}]
                     (gleif/by-lei db acme-lei))]
    (testing "Porsche has direct parent Volkswagen + raw parent-lei"
      (is (= vw-lei (:entity/parent-lei porsche)))
      (is (= vw-lei (-> porsche :entity/parent-entity :entity/lei)))
      (is (= vw-lei (:entity/ultimate-parent-lei porsche))))

    (testing "Acme has direct parent BMW + raw parent-lei"
      (is (= bmw-lei (:entity/parent-lei acme)))
      (is (= bmw-lei (-> acme :entity/parent-entity :entity/lei))))

    (testing "Apple + Microsoft remain unparented (no RR rows)"
      (let [apple (d/pull db '[*] (gleif/by-lei db apple-lei))
            ms (d/pull db '[*] (gleif/by-lei db microsoft-lei))]
        (is (nil? (:entity/parent-lei apple)))
        (is (nil? (:entity/parent-entity apple)))
        (is (nil? (:entity/parent-lei ms)))))))

(deftest forward-referenced-parent-keeps-raw-string-without-ref
  (let [conn (core/create-test-db)
        ;; Import only Porsche (no parents)
        _ (gleif/import-level-1!
           conn [{:LEI porsche-lei :EntityLegalName "Porsche AG"
                  :LegalJurisdiction "DE-BW" :LegalForm "AG"
                  :RegistrationStatus "ISSUED"}])
        ;; Import RR pointing at unloaded VW
        _ (gleif/import-level-2!
           conn [{(keyword "Relationship.StartNode.NodeID")     porsche-lei
                  (keyword "Relationship.EndNode.NodeID")       vw-lei
                  (keyword "Relationship.RelationshipType")     "IS_DIRECTLY_CONSOLIDATED_BY"
                  (keyword "Relationship.RelationshipStatus")   "ACTIVE"}])
        db (d/db conn)
        porsche (d/pull db '[*] (gleif/by-lei db porsche-lei))]
    (testing "the raw :entity/parent-lei string survives even when parent isn't loaded"
      (is (= vw-lei (:entity/parent-lei porsche))))
    (testing ":entity/parent-entity ref is NOT set (forward ref)"
      (is (nil? (:entity/parent-entity porsche))))))
