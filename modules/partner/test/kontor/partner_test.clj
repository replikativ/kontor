(ns kontor.partner-test
  "Tests for kontor-partner — ADR-033.

   Covers schema install, party/person/org composition, subtype
   uniqueness constraint, contact-mech polymorphism, temporal
   junction validity, multi-purpose routing, role assignment and
   lookup, and relationship traversal (employment in both
   directions)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.partner :as p]
            [kontor.partner.schema :as p-schema]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *conn* nil)

(def jan-01 #inst "2024-01-01T00:00:00Z")
(def jun-01 #inst "2024-06-01T00:00:00Z")
(def jun-15 #inst "2024-06-15T00:00:00Z")
(def aug-01 #inst "2024-08-01T00:00:00Z")
(def jan-2025 #inst "2025-01-01T00:00:00Z")
(def jan-2026 #inst "2026-01-01T00:00:00Z")
(def jun-2026 #inst "2026-06-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (p-schema/install! conn)
    conn))

(defn- with-conn [f]
  (binding [*conn* (bootstrap)]
    (f)))

(use-fixtures :each with-conn)

;; ============================================================================
;; Schema install
;; ============================================================================

(deftest schema-installs-without-errors
  (testing "Schema install is idempotent — re-running adds nothing new"
    ;; The bootstrap fixture already installed once; this is the
    ;; second install. install! returns `conn` for composition
    ;; — every companion install! returns conn.
    (let [r (p-schema/install! *conn*)]
      (is (= r *conn*))))

  (testing "Every documented attribute is present"
    (let [db (d/db *conn*)
          idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
      (doseq [a [:kontor.partner/type :kontor.partner/status :kontor.partner/preferred-commodity
                 :kontor.person/partner :kontor.person/first-name :kontor.person/last-name
                 :kontor.person/birth-date :kontor.person/national-id
                 :kontor.org/partner :kontor.org/legal-name :kontor.org/legal-form
                 :kontor.org/registration-number :kontor.org/duns :kontor.org/lei
                 :kontor.contact-mech/code :kontor.contact-mech/type
                 :kontor.postal-address/contact-mech :kontor.postal-address/address1
                 :kontor.postal-address/city :kontor.postal-address/postal-code
                 :kontor.postal-address/country :kontor.postal-address/state
                 :kontor.telecom-number/contact-mech :kontor.telecom-number/country-code
                 :kontor.telecom-number/contact-number
                 :kontor.email-address/contact-mech :kontor.email-address/address
                 :kontor.email-address/verified?
                 :kontor.partner-contact-mech/partner
                 :kontor.partner-contact-mech/contact-mech
                 :kontor.partner-contact-mech/from-date
                 :kontor.partner-contact-mech/identity
                 :kontor.partner-contact-mech-purpose/partner
                 :kontor.partner-contact-mech-purpose/purpose-type
                 :kontor.partner-contact-mech-purpose/identity
                 :kontor.partner-role/partner :kontor.partner-role/role-type
                 :kontor.partner-role/from-date :kontor.partner-role/identity
                 :kontor.partner-relationship/partner-from
                 :kontor.partner-relationship/partner-to
                 :kontor.partner-relationship/role-type-from
                 :kontor.partner-relationship/role-type-to
                 :kontor.partner-relationship/from-date
                 :kontor.partner-relationship/relationship-type
                 :kontor.partner-relationship/identity]]
        (is (contains? idents a) (str "missing attr: " a))))))

;; ============================================================================
;; Party / person / org composition
;; ============================================================================

(defn- transact!
  "Synchronous transact convenience for tests."
  [conn tx-data]
  (d/transact conn tx-data))

(deftest person-creation-and-resolution
  (transact! *conn*
             [{:kontor.partner/external-id "P-1001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Jane Doe"}
              {:kontor.person/partner    [:kontor.partner/external-id "P-1001"]
               :kontor.person/first-name "Jane"
               :kontor.person/last-name  "Doe"
               :kontor.person/birth-date #inst "1985-03-12"}])
  (let [db (d/db *conn*)]
    (testing "by-external-id resolves"
      (is (pos-int? (p/by-external-id db "P-1001")))
      (is (= (p/by-external-id db "P-1001")
             (p/resolve-partner db "P-1001"))))
    (testing "person subtype pulls correctly"
      (let [pp (p/person db "P-1001")]
        (is (= "Jane" (:kontor.person/first-name pp)))
        (is (= "Doe" (:kontor.person/last-name pp)))
        (is (= #inst "1985-03-12" (:kontor.person/birth-date pp)))))
    (testing "org subtype is nil for a person partner"
      (is (nil? (p/org db "P-1001"))))))

(deftest org-creation-with-registration-identifiers
  (transact! *conn*
             [{:kontor.partner/external-id "O-2001"
               :kontor.partner/type        :org
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Acme GmbH"}
              {:kontor.org/partner             [:kontor.partner/external-id "O-2001"]
               :kontor.org/legal-name          "Acme Gesellschaft mit beschränkter Haftung"
               :kontor.org/legal-form          :gmbh
               :kontor.org/trading-name        "Acme"
               :kontor.org/registration-number "HRB 12345"
               :kontor.org/duns                "123456789"
               :kontor.org/lei                 "5493001KJTIIGC8Y1R12"
               :kontor.org/num-employees       42}])
  (let [db (d/db *conn*)
        o  (p/org db "O-2001")]
    (testing "org subtype pulls registration identifiers"
      (is (= "Acme Gesellschaft mit beschränkter Haftung" (:kontor.org/legal-name o)))
      (is (= :gmbh (:kontor.org/legal-form o)))
      (is (= "HRB 12345" (:kontor.org/registration-number o)))
      (is (= "5493001KJTIIGC8Y1R12" (:kontor.org/lei o)))
      (is (= 42 (:kontor.org/num-employees o))))
    (testing "person subtype is nil for an org partner"
      (is (nil? (p/person db "O-2001"))))))

(deftest subtype-fk-is-1-to-1
  (testing "A partner cannot have two :person rows (db.unique/value enforces it)"
    (transact! *conn*
               [{:kontor.partner/external-id "P-DUP"
                 :kontor.partner/type        :person
                 :kontor.partner/name        "Original"}])
    (transact! *conn*
               [{:kontor.person/partner    [:kontor.partner/external-id "P-DUP"]
                 :kontor.person/first-name "First"}])
    (is (thrown? Exception
                 (transact! *conn*
                            [{:kontor.person/partner    [:kontor.partner/external-id "P-DUP"]
                              :kontor.person/first-name "Second"}])))))

;; ============================================================================
;; Contact-mech polymorphism
;; ============================================================================

(deftest postal-address-roundtrip
  (transact! *conn*
             [{:kontor.partner/external-id "P-3001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Postal Test"}
              {:kontor.contact-mech/code "CM-postal-1"
               :kontor.contact-mech/type :postal}
              {:kontor.postal-address/contact-mech [:kontor.contact-mech/code "CM-postal-1"]
               :kontor.postal-address/address1     "Hauptstrasse 1"
               :kontor.postal-address/city         "Berlin"
               :kontor.postal-address/postal-code  "10115"
               :kontor.postal-address/region       "Berlin"}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-3001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-postal-1"]
               :kontor.partner-contact-mech/from-date    jan-2025
               :kontor.partner-contact-mech/verified?    true}
              {:kontor.partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-3001"]
               :kontor.partner-contact-mech-purpose/contact-mech [:kontor.contact-mech/code "CM-postal-1"]
               :kontor.partner-contact-mech-purpose/purpose-type :primary-location
               :kontor.partner-contact-mech-purpose/from-date    jan-2025}])
  (let [db (d/db *conn*)
        addr (p/primary-postal-address db "P-3001" {:as-of jan-2026})]
    (is (= "Hauptstrasse 1" (:kontor.postal-address/address1 addr)))
    (is (= "Berlin" (:kontor.postal-address/city addr)))
    (is (= "10115" (:kontor.postal-address/postal-code addr)))))

(deftest one-email-serves-multiple-purposes
  (transact! *conn*
             [{:kontor.partner/external-id "P-4001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Multi-Purpose"}
              {:kontor.contact-mech/code "CM-multi-1"
               :kontor.contact-mech/type :email}
              {:kontor.email-address/contact-mech [:kontor.contact-mech/code "CM-multi-1"]
               :kontor.email-address/address      "jane@example.com"
               :kontor.email-address/verified?    true}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-4001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-multi-1"]
               :kontor.partner-contact-mech/from-date    jan-2025}
              {:kontor.partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :kontor.partner-contact-mech-purpose/contact-mech [:kontor.contact-mech/code "CM-multi-1"]
               :kontor.partner-contact-mech-purpose/purpose-type :primary-email
               :kontor.partner-contact-mech-purpose/from-date    jan-2025}
              {:kontor.partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :kontor.partner-contact-mech-purpose/contact-mech [:kontor.contact-mech/code "CM-multi-1"]
               :kontor.partner-contact-mech-purpose/purpose-type :billing-email
               :kontor.partner-contact-mech-purpose/from-date    jan-2025}
              {:kontor.partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :kontor.partner-contact-mech-purpose/contact-mech [:kontor.contact-mech/code "CM-multi-1"]
               :kontor.partner-contact-mech-purpose/purpose-type :general-correspondence
               :kontor.partner-contact-mech-purpose/from-date    jan-2025}])
  (let [db (d/db *conn*)]
    (testing "Same address resolves for each distinct purpose"
      (is (= "jane@example.com" (p/primary-email db "P-4001" {:as-of jan-2026})))
      (let [billing (p/contact-mech-by-purpose db "P-4001" :billing-email {:as-of jan-2026})
            primary (p/contact-mech-by-purpose db "P-4001" :primary-email {:as-of jan-2026})
            general (p/contact-mech-by-purpose db "P-4001" :general-correspondence {:as-of jan-2026})]
        (is (= billing primary))
        (is (= billing general))
        (is (pos-int? billing))))))

(deftest contact-mechs-of-collects-across-types
  (transact! *conn*
             [{:kontor.partner/external-id "P-5001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Multi-Mech"}
              {:kontor.contact-mech/code "CM-5001-postal"
               :kontor.contact-mech/type :postal}
              {:kontor.postal-address/contact-mech [:kontor.contact-mech/code "CM-5001-postal"]
               :kontor.postal-address/address1     "1 Main St"
               :kontor.postal-address/city         "Anywhere"}
              {:kontor.contact-mech/code "CM-5001-phone"
               :kontor.contact-mech/type :telecom}
              {:kontor.telecom-number/contact-mech   [:kontor.contact-mech/code "CM-5001-phone"]
               :kontor.telecom-number/country-code   "+1"
               :kontor.telecom-number/area-code      "555"
               :kontor.telecom-number/contact-number "0100"}
              {:kontor.contact-mech/code "CM-5001-email"
               :kontor.contact-mech/type :email}
              {:kontor.email-address/contact-mech [:kontor.contact-mech/code "CM-5001-email"]
               :kontor.email-address/address      "test@example.com"}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-5001-postal"]
               :kontor.partner-contact-mech/from-date    jan-2025}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-5001-phone"]
               :kontor.partner-contact-mech/from-date    jan-2025}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-5001-email"]
               :kontor.partner-contact-mech/from-date    jan-2025}])
  (let [db (d/db *conn*)
        mechs (p/contact-mechs-of db "P-5001" {:as-of jan-2026})]
    (is (= 3 (count mechs)))))

;; ============================================================================
;; Temporal junction validity
;; ============================================================================

(deftest partner-contact-mech-respects-thru-date
  (transact! *conn*
             [{:kontor.partner/external-id "P-6001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Moved Person"}
              {:kontor.contact-mech/code "CM-old"
               :kontor.contact-mech/type :postal}
              {:kontor.postal-address/contact-mech [:kontor.contact-mech/code "CM-old"]
               :kontor.postal-address/address1     "Old Address"}
              {:kontor.contact-mech/code "CM-new"
               :kontor.contact-mech/type :postal}
              {:kontor.postal-address/contact-mech [:kontor.contact-mech/code "CM-new"]
               :kontor.postal-address/address1     "New Address"}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-6001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-old"]
               :kontor.partner-contact-mech/from-date    jan-2025
               :kontor.partner-contact-mech/thru-date    jan-2026}
              {:kontor.partner-contact-mech/partner      [:kontor.partner/external-id "P-6001"]
               :kontor.partner-contact-mech/contact-mech [:kontor.contact-mech/code "CM-new"]
               :kontor.partner-contact-mech/from-date    jan-2026}])
  (let [db (d/db *conn*)]
    (testing "Before the move, only the old address is active"
      (let [mechs (p/contact-mechs-of db "P-6001" {:as-of #inst "2025-06-01T00:00:00Z"})]
        (is (= 1 (count mechs)))))
    (testing "At the move boundary, only the new address is active (thru-date exclusive)"
      (let [mechs (p/contact-mechs-of db "P-6001" {:as-of jan-2026})]
        (is (= 1 (count mechs)))))
    (testing "After the move, only the new address is active"
      (let [mechs (p/contact-mechs-of db "P-6001" {:as-of jun-2026})]
        (is (= 1 (count mechs)))))))

(deftest active-as-of-predicate
  (is (true?  (p/active-as-of? jan-2025 nil          jun-2026)))
  (is (true?  (p/active-as-of? jan-2025 jan-2026     #inst "2025-06-15T00:00:00Z")))
  (is (false? (p/active-as-of? jan-2025 jan-2026     jan-2026))     ; thru-date exclusive
      "thru-date is exclusive — equal instant is NOT active")
  (is (false? (p/active-as-of? jan-2026 nil          jan-2025))     ; before from-date
      "before from-date — not active")
  (is (true?  (p/active-as-of? jan-2025 nil          jan-2025))     ; equal to from-date
      "equal to from-date — active (from-date inclusive)"))

;; ============================================================================
;; Roles
;; ============================================================================

(deftest concurrent-roles
  (transact! *conn*
             [{:kontor.partner/external-id "P-7001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Multi-Role"}
              {:kontor.partner-role/partner   [:kontor.partner/external-id "P-7001"]
               :kontor.partner-role/role-type :customer
               :kontor.partner-role/from-date jan-01}
              {:kontor.partner-role/partner   [:kontor.partner/external-id "P-7001"]
               :kontor.partner-role/role-type :employee
               :kontor.partner-role/from-date jun-01}])
  (let [db (d/db *conn*)]
    (testing "Before employment starts, only :customer role active"
      (let [roles (p/roles-of db "P-7001" {:as-of jun-15})]
        (is (= #{:customer :employee} roles))
        (is (true?  (p/has-role? db "P-7001" :customer {:as-of jun-15})))
        (is (true?  (p/has-role? db "P-7001" :employee {:as-of jun-15})))))
    (testing "Before any role's from-date, no role active"
      (let [roles (p/roles-of db "P-7001" {:as-of #inst "2023-01-01T00:00:00Z"})]
        (is (empty? roles))
        (is (false? (p/has-role? db "P-7001" :customer
                                 {:as-of #inst "2023-01-01T00:00:00Z"})))))))

(deftest role-thru-date-ends-role
  (transact! *conn*
             [{:kontor.partner/external-id "P-8001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Former Employee"}
              {:kontor.partner-role/partner   [:kontor.partner/external-id "P-8001"]
               :kontor.partner-role/role-type :employee
               :kontor.partner-role/from-date jan-01
               :kontor.partner-role/thru-date aug-01}])
  (let [db (d/db *conn*)]
    (is (true?  (p/has-role? db "P-8001" :employee {:as-of jun-15})))
    (is (false? (p/has-role? db "P-8001" :employee {:as-of #inst "2024-08-15T00:00:00Z"})))))

(deftest partners-with-role-lookup
  (transact! *conn*
             [{:kontor.partner/external-id "P-9001"
               :kontor.partner/type :person :kontor.partner/status :enabled :kontor.partner/name "A"}
              {:kontor.partner/external-id "P-9002"
               :kontor.partner/type :person :kontor.partner/status :enabled :kontor.partner/name "B"}
              {:kontor.partner/external-id "P-9003"
               :kontor.partner/type :org :kontor.partner/status :enabled :kontor.partner/name "Vendor Co"}
              {:kontor.partner-role/partner [:kontor.partner/external-id "P-9001"]
               :kontor.partner-role/role-type :customer :kontor.partner-role/from-date jan-01}
              {:kontor.partner-role/partner [:kontor.partner/external-id "P-9002"]
               :kontor.partner-role/role-type :customer :kontor.partner-role/from-date jan-01}
              {:kontor.partner-role/partner [:kontor.partner/external-id "P-9003"]
               :kontor.partner-role/role-type :supplier :kontor.partner-role/from-date jan-01}])
  (let [db (d/db *conn*)
        customers (p/partners-with-role db :customer {:as-of jun-15})
        suppliers (p/partners-with-role db :supplier {:as-of jun-15})]
    (is (= 2 (count customers)))
    (is (= 1 (count suppliers)))
    (is (= #{(p/by-external-id db "P-9001")
             (p/by-external-id db "P-9002")}
           customers))))

;; ============================================================================
;; Relationships
;; ============================================================================

(deftest employment-relationship-traversal
  (transact! *conn*
             [{:kontor.partner/external-id "P-employee"
               :kontor.partner/type :person :kontor.partner/status :enabled
               :kontor.partner/name "Jane Doe"}
              {:kontor.partner/external-id "O-acme"
               :kontor.partner/type :org :kontor.partner/status :enabled
               :kontor.partner/name "Acme"}
              {:kontor.partner-role/partner [:kontor.partner/external-id "P-employee"]
               :kontor.partner-role/role-type :employee :kontor.partner-role/from-date jun-01}
              {:kontor.partner-role/partner [:kontor.partner/external-id "O-acme"]
               :kontor.partner-role/role-type :internal-organization
               :kontor.partner-role/from-date jan-01}
              {:kontor.partner-relationship/partner-from      [:kontor.partner/external-id "P-employee"]
               :kontor.partner-relationship/role-type-from    :employee
               :kontor.partner-relationship/partner-to        [:kontor.partner/external-id "O-acme"]
               :kontor.partner-relationship/role-type-to      :internal-organization
               :kontor.partner-relationship/relationship-type :employment
               :kontor.partner-relationship/from-date         jun-01
               :kontor.partner-relationship/position-title    "Senior Engineer"
               :kontor.partner-relationship/status            :active}])
  (let [db (d/db *conn*)
        employees (p/current-employees db "O-acme" {:as-of #inst "2025-01-01T00:00:00Z"})
        employer  (p/current-employer  db "P-employee" {:as-of #inst "2025-01-01T00:00:00Z"})]
    (testing "Employees of the org include the employee"
      (is (contains? employees (p/by-external-id db "P-employee"))))
    (testing "Employer of the employee is the org"
      (is (= (p/by-external-id db "O-acme") employer)))
    (testing "Before the employment from-date, no relationship is active"
      (is (empty? (p/current-employees db "O-acme" {:as-of jan-01})))
      (is (nil?   (p/current-employer  db "P-employee" {:as-of jan-01}))))))

(deftest relationship-thru-date-ends-employment
  (transact! *conn*
             [{:kontor.partner/external-id "P-former"
               :kontor.partner/type :person :kontor.partner/status :enabled :kontor.partner/name "Former"}
              {:kontor.partner/external-id "O-x"
               :kontor.partner/type :org :kontor.partner/status :enabled :kontor.partner/name "X"}
              {:kontor.partner-relationship/partner-from      [:kontor.partner/external-id "P-former"]
               :kontor.partner-relationship/role-type-from    :employee
               :kontor.partner-relationship/partner-to        [:kontor.partner/external-id "O-x"]
               :kontor.partner-relationship/role-type-to      :internal-organization
               :kontor.partner-relationship/relationship-type :employment
               :kontor.partner-relationship/from-date         jan-01
               :kontor.partner-relationship/thru-date         aug-01
               :kontor.partner-relationship/status            :inactive}])
  (let [db (d/db *conn*)]
    (testing "During tenure, relationship is active"
      (is (some? (p/current-employer db "P-former" {:as-of jun-15}))))
    (testing "After thru-date, relationship is no longer active"
      (is (nil? (p/current-employer db "P-former" {:as-of #inst "2024-09-01T00:00:00Z"}))))))

(deftest relationships-of-type-filter
  (transact! *conn*
             [{:kontor.partner/external-id "O-parent"
               :kontor.partner/type :org :kontor.partner/status :enabled :kontor.partner/name "Parent"}
              {:kontor.partner/external-id "O-sub1"
               :kontor.partner/type :org :kontor.partner/status :enabled :kontor.partner/name "Sub 1"}
              {:kontor.partner/external-id "O-sub2"
               :kontor.partner/type :org :kontor.partner/status :enabled :kontor.partner/name "Sub 2"}
              {:kontor.partner-relationship/partner-from [:kontor.partner/external-id "O-parent"]
               :kontor.partner-relationship/role-type-from :internal-organization
               :kontor.partner-relationship/partner-to [:kontor.partner/external-id "O-sub1"]
               :kontor.partner-relationship/role-type-to :internal-organization
               :kontor.partner-relationship/relationship-type :subsidiary
               :kontor.partner-relationship/from-date jan-01
               :kontor.partner-relationship/status :active}
              {:kontor.partner-relationship/partner-from [:kontor.partner/external-id "O-parent"]
               :kontor.partner-relationship/role-type-from :internal-organization
               :kontor.partner-relationship/partner-to [:kontor.partner/external-id "O-sub2"]
               :kontor.partner-relationship/role-type-to :internal-organization
               :kontor.partner-relationship/relationship-type :subsidiary
               :kontor.partner-relationship/from-date jun-01
               :kontor.partner-relationship/status :active}])
  (let [db (d/db *conn*)
        subs (p/relationships-of-type db "O-parent" :subsidiary {:as-of jun-15})]
    (is (= 2 (count subs)))
    (is (every? #(= :subsidiary (:kontor.partner-relationship/relationship-type %)) subs))))

;; ============================================================================
;; ADR-039 — Master-data primitives
;; ============================================================================

(deftest merge-partners-resolves-canonical
  (d/transact *conn*
              [{:kontor.partner/external-id "P-CANONICAL"
                :kontor.partner/type :person :kontor.partner/status :enabled
                :kontor.partner/name "Canonical Customer"}
               {:kontor.partner/external-id "P-DUPLICATE"
                :kontor.partner/type :person :kontor.partner/status :enabled
                :kontor.partner/name "Duplicate Customer"}])
  (let [canonical (p/by-external-id (d/db *conn*) "P-CANONICAL")
        duplicate (p/by-external-id (d/db *conn*) "P-DUPLICATE")]
    (p/merge-partners! *conn* canonical duplicate
                       {:reason :duplicate
                        :reason-note "found duplicate during AR review"
                        :merged-at #inst "2026-05-01"})
    (let [db (d/db *conn*)]
      (testing "resolve-canonical-partner walks the merge link"
        (is (= canonical (p/resolve-canonical-partner db duplicate)))
        (is (= canonical (p/resolve-canonical-partner db canonical))
            "canonical resolves to itself"))
      (testing "superseded partner is archived"
        (is (= :archived (-> (d/pull db [:kontor.partner/status] duplicate)
                             :kontor.partner/status))))
      (testing "duplicate's history preserved (not retracted)"
        (is (= "Duplicate Customer"
               (-> (d/pull db [:kontor.partner/name] duplicate) :kontor.partner/name)))))))

(deftest merge-rejects-self-merge
  (d/transact *conn* [{:kontor.partner/external-id "P-SELF" :kontor.partner/type :person
                       :kontor.partner/status :enabled :kontor.partner/name "Self"}])
  (let [eid (p/by-external-id (d/db *conn*) "P-SELF")]
    (is (thrown? Exception
                 (p/merge-partners! *conn* eid eid {:reason :duplicate})))))

(deftest bank-accounts-temporal-junction
  (d/transact *conn*
              [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:kontor.partner/external-id "P-SUPPLIER"
                :kontor.partner/type :org :kontor.partner/status :enabled
                :kontor.partner/name "Supplier Co"}
               {:kontor.bank-account/code "ACCT-EUR-1"
                :kontor.bank-account/iban "DE89370400440532013000"
                :kontor.bank-account/bic "COBADEFFXXX"
                :kontor.bank-account/bank-name "Commerzbank"
                :kontor.bank-account/commodity [:kontor.commodity/symbol "EUR"]
                :kontor.bank-account/holder-name "Supplier Co GmbH"
                :kontor.bank-account/active true}
               ;; Old account, thru-dated
               {:kontor.partner-bank-account/partner [:kontor.partner/external-id "P-SUPPLIER"]
                :kontor.partner-bank-account/bank-account [:kontor.bank-account/code "ACCT-EUR-1"]
                :kontor.partner-bank-account/from-date #inst "2023-01-01"
                :kontor.partner-bank-account/thru-date #inst "2025-01-01"
                :kontor.partner-bank-account/purpose :disbursement
                :kontor.partner-bank-account/preferred? false}
               ;; New account, current
               {:kontor.partner-bank-account/partner [:kontor.partner/external-id "P-SUPPLIER"]
                :kontor.partner-bank-account/bank-account [:kontor.bank-account/code "ACCT-EUR-1"]
                :kontor.partner-bank-account/from-date #inst "2025-06-01"
                :kontor.partner-bank-account/purpose :disbursement
                :kontor.partner-bank-account/preferred? true}])
  (let [db (d/db *conn*)]
    (testing "during the gap, no active bank account"
      (let [accts (p/bank-accounts-of db "P-SUPPLIER" {:as-of #inst "2025-03-01"})]
        (is (zero? (count accts)))))
    (testing "during the older window, the un-preferred account is active"
      (let [accts (p/bank-accounts-of db "P-SUPPLIER" {:as-of #inst "2024-06-01"})]
        (is (= 1 (count accts)))))
    (testing "after the new from-date, the preferred account is active"
      (let [primary (p/primary-disbursement-account db "P-SUPPLIER"
                                                    {:as-of #inst "2025-09-01"})]
        (is (= "DE89370400440532013000" (:kontor.bank-account/iban primary)))))))

(deftest partner-tags-temporal
  (d/transact *conn*
              [{:kontor.partner/external-id "P-TIER"
                :kontor.partner/type :org :kontor.partner/status :enabled
                :kontor.partner/name "Tier Customer"}
               {:kontor.partner-tag/partner [:kontor.partner/external-id "P-TIER"]
                :kontor.partner-tag/tag-type :gold-tier
                :kontor.partner-tag/from-date #inst "2024-01-01"
                :kontor.partner-tag/thru-date #inst "2025-06-15"}
               {:kontor.partner-tag/partner [:kontor.partner/external-id "P-TIER"]
                :kontor.partner-tag/tag-type :silver-tier
                :kontor.partner-tag/from-date #inst "2025-06-15"}])
  (let [db (d/db *conn*)]
    (testing "tier as-of mid-2024 is gold"
      (is (= #{:gold-tier} (p/tags-of db "P-TIER" {:as-of #inst "2024-06-15"}))))
    (testing "tier post-downgrade is silver"
      (is (= #{:silver-tier} (p/tags-of db "P-TIER" {:as-of #inst "2026-01-15"}))))
    (testing "partners-with-tag :gold-tier as-of-2024 includes P-TIER"
      (is (contains? (p/partners-with-tag db :gold-tier {:as-of #inst "2024-06-15"})
                     (p/by-external-id db "P-TIER"))))))

(deftest credit-limit-and-kyc-attrs-round-trip
  (d/transact *conn*
              [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:kontor.partner/external-id "P-CREDIT"
                :kontor.partner/type :org :kontor.partner/status :enabled
                :kontor.partner/name "Credit Customer"
                :kontor.partner/credit-limit 50000M
                :kontor.partner/credit-commodity [:kontor.commodity/symbol "EUR"]
                :kontor.partner/credit-status :open
                :kontor.partner/kyc-status :cleared
                :kontor.partner/kyc-source "Manual"
                :kontor.partner/kyc-checked-at #inst "2026-04-15"}])
  (let [db (d/db *conn*)
        p (d/pull db '[*] (p/by-external-id db "P-CREDIT"))]
    (is (= 50000M (:kontor.partner/credit-limit p)))
    (is (= :open (:kontor.partner/credit-status p)))
    (is (= :cleared (:kontor.partner/kyc-status p)))
    (is (= "Manual" (:kontor.partner/kyc-source p)))))

;; ============================================================================
;; ADR-040 — Multi-tax-id-per-jurisdiction
;; ============================================================================

(deftest partner-tax-id-multi-jurisdiction
  (d/transact *conn*
              [{:kontor.country/code "DE" :kontor.country/name "Germany"}
               {:kontor.country/code "AT" :kontor.country/name "Austria"}
               {:kontor.partner/external-id "P-MULTI-VAT"
                :kontor.partner/type :org :kontor.partner/status :enabled
                :kontor.partner/name "Multi-VAT Inc"
                :kontor.partner/tax-id "DE123456789"}
               ;; DE VAT
               {:kontor.partner-tax-id/partner [:kontor.partner/external-id "P-MULTI-VAT"]
                :kontor.partner-tax-id/country [:kontor.country/code "DE"]
                :kontor.partner-tax-id/tax-id-type :vat-eu
                :kontor.partner-tax-id/tax-id "DE123456789"
                :kontor.partner-tax-id/from-date #inst "2024-01-01"
                :kontor.partner-tax-id/verified? true}
               ;; AT VAT — separate jurisdiction
               {:kontor.partner-tax-id/partner [:kontor.partner/external-id "P-MULTI-VAT"]
                :kontor.partner-tax-id/country [:kontor.country/code "AT"]
                :kontor.partner-tax-id/tax-id-type :vat-eu
                :kontor.partner-tax-id/tax-id "ATU12345678"
                :kontor.partner-tax-id/from-date #inst "2024-06-01"
                :kontor.partner-tax-id/verified? true}])
  (let [db (d/db *conn*)]
    (testing "tax-id-for-country DE returns DE VAT"
      (is (= "DE123456789"
             (p/tax-id-for-country db "P-MULTI-VAT" "DE"))))
    (testing "tax-id-for-country AT returns AT VAT"
      (is (= "ATU12345678"
             (p/tax-id-for-country db "P-MULTI-VAT" "AT"))))
    (testing "tax-id-for-country before AT from-date returns nil"
      (is (nil? (p/tax-id-for-country db "P-MULTI-VAT" "AT"
                                      {:as-of #inst "2024-03-01"}))))
    (testing "tax-ids-of returns all active"
      (let [hits (p/tax-ids-of db "P-MULTI-VAT" {:as-of #inst "2025-01-01"})]
        (is (= 2 (count hits)))))))
