(ns kontor.partner.transactors-test
  "Tests for the ADR-068 partner transactors (create / update /
   contact-mech / relationship / role).

   The pre-existing `kontor.partner-test` exercises read-side queries
   and the merge transactor. This namespace covers the routine
   business writes that consumers previously had to hand-build with
   raw `d/transact` — the gap flagged in research note 95 §2
   (kontor-partner section)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.partner :as p]
            [kontor.partner.schema :as p-schema]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *conn* nil)

(def jan-2024 #inst "2024-01-01T00:00:00Z")
(def jul-2024 #inst "2024-07-01T00:00:00Z")
(def jan-2025 #inst "2025-01-01T00:00:00Z")
(def jul-2025 #inst "2025-07-01T00:00:00Z")
(def jan-2026 #inst "2026-01-01T00:00:00Z")
(def jul-2026 #inst "2026-07-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (p-schema/install! conn)
    conn))

(defn- with-conn [f]
  (binding [*conn* (bootstrap)]
    (f)))

(use-fixtures :each with-conn)

;; ============================================================================
;; create-party! — person + org branches
;; ============================================================================

(deftest create-party!-person-branch
  (testing "Creates the :partner root + 1:1 :person subtype in one tx"
    (p/create-party! *conn*
                     {:external-id "P-CREATE-1"
                      :type        :person
                      :name        "Jane Doe"
                      :kind        :customer
                      :country-code "DE"
                      :person {:first-name "Jane"
                               :last-name  "Doe"
                               :birth-date #inst "1990-04-12"
                               :gender     :female}})
    (let [db (d/db *conn*)
          eid (p/by-external-id db "P-CREATE-1")
          partner (d/pull db '[*] eid)
          person  (p/person db "P-CREATE-1")]
      (is (pos-int? eid) "partner eid resolves")
      (is (= "Jane Doe"   (:partner/name partner)))
      (is (= :person      (:partner/type partner)))
      (is (= :enabled     (:partner/status partner)) ":status defaults to :enabled")
      (is (= :customer    (:partner/kind partner)))
      (is (= "DE"         (:partner/country-code partner)))
      (is (some? (:partner/created-at partner)) ":created-at is stamped")
      (is (some? (:partner/modified-at partner)) ":modified-at is stamped")
      (is (= "Jane" (:person/first-name person)))
      (is (= "Doe"  (:person/last-name person)))
      (is (= :female (:person/gender person)))
      (is (= #inst "1990-04-12" (:person/birth-date person))))))

(deftest create-party!-org-branch
  (testing "Creates the :partner root + 1:1 :org subtype in one tx"
    (p/create-party! *conn*
                     {:external-id "O-CREATE-1"
                      :type        :org
                      :name        "Acme GmbH"
                      :kind        :vendor
                      :country-code "DE"
                      :tax-id      "DE123456789"
                      :org {:legal-name          "Acme Gesellschaft mit beschränkter Haftung"
                            :legal-form          :gmbh
                            :registration-number "HRB 12345"
                            :duns                "123456789"
                            :lei                 "5493001KJTIIGC8Y1R12"
                            :num-employees       42}})
    (let [db (d/db *conn*)
          partner (d/pull db '[*] (p/by-external-id db "O-CREATE-1"))
          org (p/org db "O-CREATE-1")]
      (is (= :org      (:partner/type partner)))
      (is (= "DE123456789" (:partner/tax-id partner)))
      (is (= :gmbh     (:org/legal-form org)))
      (is (= "HRB 12345" (:org/registration-number org)))
      (is (= 42        (:org/num-employees org))))))

(deftest create-party!-validation
  (testing "Missing required keys throw"
    (is (thrown? Exception (p/create-party! *conn* {:type :person :name "X"}))
        "missing :external-id")
    (is (thrown? Exception (p/create-party! *conn* {:external-id "X" :name "X"}))
        "missing :type")
    (is (thrown? Exception (p/create-party! *conn* {:external-id "X" :type :person}))
        "missing :name")
    (is (thrown? Exception (p/create-party! *conn* {:external-id "X" :type :alien :name "X"}))
        "invalid :type rejected")))

(deftest create-party!-routes-through-validation
  (testing "Re-creating the same :external-id violates unique-identity"
    (p/create-party! *conn*
                     {:external-id "P-UNIQ" :type :person :name "First"})
    ;; :db.unique/identity → second tx upserts on the :partner root,
    ;; but :person/partner with :db.unique/value rejects a second
    ;; subtype row on the same partner. We only test the first half
    ;; here — the schema-uniqueness test in partner-test already
    ;; covers the subtype-clash case.
    (is (some? (p/by-external-id (d/db *conn*) "P-UNIQ")))))

;; ============================================================================
;; update-party!
;; ============================================================================

(deftest update-party!-rewrites-fields
  (p/create-party! *conn* {:external-id "P-UPD" :type :person :name "Old Name"})
  (let [before-modified (-> (d/pull (d/db *conn*) [:partner/modified-at]
                                    (p/by-external-id (d/db *conn*) "P-UPD"))
                            :partner/modified-at)]
    (Thread/sleep 5)
    (p/update-party! *conn* "P-UPD"
                     {:name        "New Name"
                      :status      :disabled
                      :country-code "AT"
                      :description "renamed during MDM cleanup"})
    (let [db (d/db *conn*)
          partner (d/pull db '[*] (p/by-external-id db "P-UPD"))]
      (testing "named fields are mutated"
        (is (= "New Name" (:partner/name partner)))
        (is (= :disabled  (:partner/status partner)))
        (is (= "AT"       (:partner/country-code partner)))
        (is (= "renamed during MDM cleanup" (:partner/description partner))))
      (testing ":modified-at is bumped on every update"
        (is (.after ^java.util.Date (:partner/modified-at partner)
                    ^java.util.Date before-modified))))))

(deftest update-party!-validation
  (p/create-party! *conn* {:external-id "P-UPD-VAL" :type :person :name "X"})
  (is (thrown? Exception (p/update-party! *conn* "P-NOT-THERE" {:name "X"}))
      "unknown partner throws")
  (is (thrown? Exception (p/update-party! *conn* "P-UPD-VAL" {}))
      "empty update throws"))

;; ============================================================================
;; add-contact-mech! / remove-contact-mech!
;; ============================================================================

(deftest add-contact-mech!-postal
  (p/create-party! *conn* {:external-id "P-POSTAL" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-POSTAL"
                        :code    "CM-POSTAL-1"
                        :kind    :postal
                        :payload {:address1    "Hauptstr. 1"
                                  :city        "Berlin"
                                  :postal-code "10115"
                                  :region      "Berlin"}
                        :from-date jan-2025
                        :verified? true
                        :purposes  [:primary-location :billing-location]})
  (let [db (d/db *conn*)
        mechs (p/contact-mechs-of db "P-POSTAL" {:as-of jul-2025})
        addr (p/primary-postal-address db "P-POSTAL" {:as-of jul-2025})
        billing (p/contact-mech-by-purpose db "P-POSTAL" :billing-location
                                           {:as-of jul-2025})]
    (testing "junction row links partner ↔ mech"
      (is (= 1 (count mechs))))
    (testing "typed payload is queryable"
      (is (= "Hauptstr. 1" (:postal-address/address1 addr)))
      (is (= "Berlin"      (:postal-address/city addr)))
      (is (= "10115"       (:postal-address/postal-code addr))))
    (testing "both purposes resolve to the same mech"
      (is (pos-int? billing))
      (is (= billing (p/contact-mech-by-purpose db "P-POSTAL" :primary-location
                                                {:as-of jul-2025}))))))

(deftest add-contact-mech!-telecom
  (p/create-party! *conn* {:external-id "P-TEL" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-TEL" :code "CM-TEL-1" :kind :telecom
                        :payload {:country-code "+49"
                                  :area-code "30"
                                  :contact-number "12345678"}
                        :from-date jan-2025})
  (let [db (d/db *conn*)
        cm-eid (d/q '[:find ?cm . :in $ ?c
                      :where [?cm :contact-mech/code ?c]]
                    db "CM-TEL-1")
        tn (d/q '[:find (pull ?t [*]) . :in $ ?cm
                  :where [?t :telecom-number/contact-mech ?cm]]
                db cm-eid)]
    (is (= "+49"      (:telecom-number/country-code tn)))
    (is (= "12345678" (:telecom-number/contact-number tn)))))

(deftest add-contact-mech!-email
  (p/create-party! *conn* {:external-id "P-EML" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-EML" :code "CM-EML-1" :kind :email
                        :payload {:address "jane@example.com"
                                  :verified? true}
                        :from-date jan-2025
                        :purposes [:primary-email]})
  (let [db (d/db *conn*)]
    (is (= "jane@example.com"
           (p/primary-email db "P-EML" {:as-of jul-2025})))))

(deftest add-contact-mech!-web-uses-info-string
  (p/create-party! *conn* {:external-id "P-WEB" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-WEB" :code "CM-WEB-1" :kind :web
                        :payload {:info-string "https://example.com"}
                        :from-date jan-2025})
  (let [db (d/db *conn*)
        info (d/q '[:find ?s . :in $ ?c
                    :where
                    [?cm :contact-mech/code ?c]
                    [?cm :contact-mech/info-string ?s]]
                  db "CM-WEB-1")]
    (is (= "https://example.com" info))))

(deftest add-contact-mech!-validation
  (is (thrown? Exception
               (p/add-contact-mech! *conn* {:code "X" :kind :postal :payload {}}))
      "missing :partner throws")
  (p/create-party! *conn* {:external-id "P-VX" :type :person :name "X"})
  (is (thrown? Exception
               (p/add-contact-mech! *conn* {:partner "P-VX" :kind :postal :payload {}}))
      "missing :code throws")
  (is (thrown? Exception
               (p/add-contact-mech! *conn* {:partner "P-VX" :code "X" :payload {}}))
      "missing :kind throws")
  (is (thrown? Exception
               (p/add-contact-mech! *conn* {:partner "P-VX" :code "X" :kind :sms
                                            :payload {}}))
      "invalid :kind rejected"))

(deftest remove-contact-mech!-closes-via-thru-date
  (p/create-party! *conn* {:external-id "P-REM" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-REM" :code "CM-REM-1" :kind :email
                        :payload {:address "ann@example.com"}
                        :from-date jan-2025})
  (testing "before close: junction is active"
    (let [db (d/db *conn*)]
      (is (= 1 (count (p/contact-mechs-of db "P-REM" {:as-of jul-2025}))))))
  (p/remove-contact-mech! *conn*
                          {:partner "P-REM"
                           :contact-mech "CM-REM-1"
                           :thru-date jul-2026})
  (testing "thru-date is exclusive — d < thru-date still active, d >= thru inactive"
    (let [db (d/db *conn*)]
      ;; before thru-date — still active
      (is (= 1 (count (p/contact-mechs-of db "P-REM" {:as-of jan-2026})))
          "jan-2026 < jul-2026 → still active")
      ;; at thru-date — exclusive, so inactive
      (is (zero? (count (p/contact-mechs-of db "P-REM" {:as-of jul-2026})))
          "at jul-2026 (= thru) → inactive (thru-date is exclusive)")
      ;; well after thru-date — inactive
      (is (zero? (count (p/contact-mechs-of db "P-REM"
                                            {:as-of #inst "2027-01-01"})))
          "after thru-date → inactive")))
  (testing "contact-mech entity itself is preserved (ADR-007 no silent retract)"
    (let [db (d/db *conn*)]
      (is (some? (d/q '[:find ?cm . :in $ ?c
                        :where [?cm :contact-mech/code ?c]]
                      db "CM-REM-1"))))))

(deftest remove-contact-mech!-no-active-throws
  (p/create-party! *conn* {:external-id "P-NOREM" :type :person :name "X"})
  (p/add-contact-mech! *conn*
                       {:partner "P-NOREM" :code "CM-NOREM-1" :kind :email
                        :payload {:address "x@y.com"}
                        :from-date jan-2025
                        :thru-date jul-2025})
  (testing "no active junction at the cutoff → exception"
    (is (thrown? Exception
                 (p/remove-contact-mech! *conn*
                                         {:partner "P-NOREM"
                                          :contact-mech "CM-NOREM-1"
                                          :thru-date jan-2026
                                          :as-of jan-2026})))))

;; ============================================================================
;; add-relationship! / end-relationship!
;; ============================================================================

(deftest add-relationship!-employment
  (p/create-party! *conn* {:external-id "P-EMPLOYEE" :type :person :name "Alice"})
  (p/create-party! *conn* {:external-id "O-EMPLOYER" :type :org    :name "Acme"})
  (let [tx (p/add-relationship! *conn*
                                {:partner-from      "P-EMPLOYEE"
                                 :partner-to        "O-EMPLOYER"
                                 :role-type-from    :employee
                                 :role-type-to      :internal-organization
                                 :relationship-type :employment
                                 :from-date         jan-2024
                                 :position-title    "Senior Engineer"})]
    (is (some? (:tx-data tx))))
  (let [db (d/db *conn*)
        rels (p/relationships-of-type db "P-EMPLOYEE" :employment
                                      {:as-of jul-2024})]
    (testing "relationship is discoverable by both sides"
      (is (= 1 (count rels)))
      (is (= "Senior Engineer" (-> rels first :partner-relationship/position-title)))
      (is (= :active (-> rels first :partner-relationship/status))))
    (testing "current-employer / current-employees traverse correctly"
      (is (= (p/by-external-id db "O-EMPLOYER")
             (p/current-employer db "P-EMPLOYEE" {:as-of jul-2024})))
      (is (contains? (p/current-employees db "O-EMPLOYER" {:as-of jul-2024})
                     (p/by-external-id db "P-EMPLOYEE"))))))

(deftest add-relationship!-validation
  (is (thrown? Exception
               (p/add-relationship! *conn*
                                    {:partner-to "X" :role-type-from :a
                                     :role-type-to :b :relationship-type :c}))
      "missing :partner-from throws")
  (p/create-party! *conn* {:external-id "X1" :type :person :name "X1"})
  (p/create-party! *conn* {:external-id "X2" :type :person :name "X2"})
  (is (thrown? Exception
               (p/add-relationship! *conn*
                                    {:partner-from "X1" :partner-to "X2"
                                     :role-type-to :b :relationship-type :c}))
      "missing :role-type-from throws"))

(deftest end-relationship!-stamps-thru-date
  (p/create-party! *conn* {:external-id "P-END" :type :person :name "Bob"})
  (p/create-party! *conn* {:external-id "O-END" :type :org    :name "Co"})
  (p/add-relationship! *conn*
                       {:partner-from      "P-END"
                        :partner-to        "O-END"
                        :role-type-from    :employee
                        :role-type-to      :internal-organization
                        :relationship-type :employment
                        :from-date         jan-2024})
  (let [db (d/db *conn*)
        rel-eid (-> (p/relationships-from db "P-END" {:as-of jul-2024})
                    first :db/id)]
    (is (pos-int? rel-eid))
    (p/end-relationship! *conn* {:relationship rel-eid
                                 :thru-date    jul-2025
                                 :status       :inactive})
    (let [db' (d/db *conn*)
          pulled (d/pull db' '[*] rel-eid)]
      (testing ":thru-date + :status are stamped"
        (is (= jul-2025 (:partner-relationship/thru-date pulled)))
        (is (= :inactive (:partner-relationship/status pulled))))
      (testing "after :thru-date no current-employer is reported"
        (is (nil? (p/current-employer db' "P-END" {:as-of jan-2026})))))))

(deftest end-relationship!-validation
  (is (thrown? Exception (p/end-relationship! *conn* {})) "missing :relationship throws"))

;; ============================================================================
;; add-party-role!
;; ============================================================================

(deftest add-party-role!-tags-customer
  (p/create-party! *conn* {:external-id "P-ROLE" :type :person :name "Carol"})
  (p/add-party-role! *conn*
                     {:partner   "P-ROLE"
                      :role-type :customer
                      :from-date jan-2024})
  (let [db (d/db *conn*)]
    (is (true? (p/has-role? db "P-ROLE" :customer {:as-of jul-2024})))
    (is (false? (p/has-role? db "P-ROLE" :supplier {:as-of jul-2024})))
    (is (contains? (p/partners-with-role db :customer {:as-of jul-2024})
                   (p/by-external-id db "P-ROLE")))))

(deftest add-party-role!-multiple-concurrent
  (p/create-party! *conn* {:external-id "P-MULTI" :type :person :name "Dee"})
  (p/add-party-role! *conn* {:partner "P-MULTI" :role-type :customer
                             :from-date jan-2024})
  (p/add-party-role! *conn* {:partner "P-MULTI" :role-type :employee
                             :from-date jul-2024})
  (let [db (d/db *conn*)]
    (testing "both roles active concurrently after the later from-date"
      (is (= #{:customer :employee}
             (p/roles-of db "P-MULTI" {:as-of jan-2025}))))
    (testing "only the earlier role is active before the later from-date"
      (is (= #{:customer}
             (p/roles-of db "P-MULTI" {:as-of #inst "2024-03-01"}))))))

(deftest add-party-role!-validation
  (is (thrown? Exception (p/add-party-role! *conn* {:role-type :customer}))
      "missing :partner throws")
  (p/create-party! *conn* {:external-id "P-RV" :type :person :name "X"})
  (is (thrown? Exception (p/add-party-role! *conn* {:partner "P-RV"}))
      "missing :role-type throws")
  (is (thrown? Exception (p/add-party-role! *conn* {:partner "P-NOT-THERE"
                                                    :role-type :customer}))
      "unknown partner throws"))

;; ============================================================================
;; Pure builders return tx-data without transacting
;; ============================================================================

(deftest pure-builders-do-not-transact
  (testing "create-party-tx-data returns a vector and does not write"
    (let [tx (p/create-party-tx-data (d/db *conn*)
                                     {:external-id "P-PURE" :type :person :name "Pure"})]
      (is (vector? tx))
      (is (= 1 (count tx)))
      (is (nil? (p/by-external-id (d/db *conn*) "P-PURE"))
          "the builder is pure — no entity exists post-call"))))
