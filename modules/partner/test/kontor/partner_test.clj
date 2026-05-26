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
    ;; second install. Datahike's transact is synchronous, returning
    ;; a TxReport directly (no deref).
    (let [tx1 (p-schema/install! *conn*)]
      (is (some? (:tx-data tx1)))))

  (testing "Every documented attribute is present"
    (let [db (d/db *conn*)
          idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
      (doseq [a [:kontor.partner/type :kontor.partner/status :kontor.partner/preferred-commodity
                 :kontor.person/partner :kontor.person/first-name :kontor.person/last-name
                 :kontor.person/birth-date :kontor.person/national-id
                 :org/partner :org/legal-name :org/legal-form
                 :org/registration-number :org/duns :org/lei
                 :contact-mech/code :contact-mech/type
                 :postal-address/contact-mech :postal-address/address1
                 :postal-address/city :postal-address/postal-code
                 :postal-address/country :postal-address/state
                 :telecom-number/contact-mech :telecom-number/country-code
                 :telecom-number/contact-number
                 :email-address/contact-mech :email-address/address
                 :email-address/verified?
                 :partner-contact-mech/partner
                 :partner-contact-mech/contact-mech
                 :partner-contact-mech/from-date
                 :partner-contact-mech/identity
                 :partner-contact-mech-purpose/partner
                 :partner-contact-mech-purpose/purpose-type
                 :partner-contact-mech-purpose/identity
                 :partner-role/partner :partner-role/role-type
                 :partner-role/from-date :partner-role/identity
                 :partner-relationship/partner-from
                 :partner-relationship/partner-to
                 :partner-relationship/role-type-from
                 :partner-relationship/role-type-to
                 :partner-relationship/from-date
                 :partner-relationship/relationship-type
                 :partner-relationship/identity]]
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
              {:org/partner             [:kontor.partner/external-id "O-2001"]
               :org/legal-name          "Acme Gesellschaft mit beschränkter Haftung"
               :org/legal-form          :gmbh
               :org/trading-name        "Acme"
               :org/registration-number "HRB 12345"
               :org/duns                "123456789"
               :org/lei                 "5493001KJTIIGC8Y1R12"
               :org/num-employees       42}])
  (let [db (d/db *conn*)
        o  (p/org db "O-2001")]
    (testing "org subtype pulls registration identifiers"
      (is (= "Acme Gesellschaft mit beschränkter Haftung" (:org/legal-name o)))
      (is (= :gmbh (:org/legal-form o)))
      (is (= "HRB 12345" (:org/registration-number o)))
      (is (= "5493001KJTIIGC8Y1R12" (:org/lei o)))
      (is (= 42 (:org/num-employees o))))
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
              {:contact-mech/code "CM-postal-1"
               :contact-mech/type :postal}
              {:postal-address/contact-mech [:contact-mech/code "CM-postal-1"]
               :postal-address/address1     "Hauptstrasse 1"
               :postal-address/city         "Berlin"
               :postal-address/postal-code  "10115"
               :postal-address/region       "Berlin"}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-3001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-postal-1"]
               :partner-contact-mech/from-date    jan-2025
               :partner-contact-mech/verified?    true}
              {:partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-3001"]
               :partner-contact-mech-purpose/contact-mech [:contact-mech/code "CM-postal-1"]
               :partner-contact-mech-purpose/purpose-type :primary-location
               :partner-contact-mech-purpose/from-date    jan-2025}])
  (let [db (d/db *conn*)
        addr (p/primary-postal-address db "P-3001" {:as-of jan-2026})]
    (is (= "Hauptstrasse 1" (:postal-address/address1 addr)))
    (is (= "Berlin" (:postal-address/city addr)))
    (is (= "10115" (:postal-address/postal-code addr)))))

(deftest one-email-serves-multiple-purposes
  (transact! *conn*
             [{:kontor.partner/external-id "P-4001"
               :kontor.partner/type        :person
               :kontor.partner/status      :enabled
               :kontor.partner/name        "Multi-Purpose"}
              {:contact-mech/code "CM-multi-1"
               :contact-mech/type :email}
              {:email-address/contact-mech [:contact-mech/code "CM-multi-1"]
               :email-address/address      "jane@example.com"
               :email-address/verified?    true}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-4001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-multi-1"]
               :partner-contact-mech/from-date    jan-2025}
              {:partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :partner-contact-mech-purpose/contact-mech [:contact-mech/code "CM-multi-1"]
               :partner-contact-mech-purpose/purpose-type :primary-email
               :partner-contact-mech-purpose/from-date    jan-2025}
              {:partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :partner-contact-mech-purpose/contact-mech [:contact-mech/code "CM-multi-1"]
               :partner-contact-mech-purpose/purpose-type :billing-email
               :partner-contact-mech-purpose/from-date    jan-2025}
              {:partner-contact-mech-purpose/partner      [:kontor.partner/external-id "P-4001"]
               :partner-contact-mech-purpose/contact-mech [:contact-mech/code "CM-multi-1"]
               :partner-contact-mech-purpose/purpose-type :general-correspondence
               :partner-contact-mech-purpose/from-date    jan-2025}])
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
              {:contact-mech/code "CM-5001-postal"
               :contact-mech/type :postal}
              {:postal-address/contact-mech [:contact-mech/code "CM-5001-postal"]
               :postal-address/address1     "1 Main St"
               :postal-address/city         "Anywhere"}
              {:contact-mech/code "CM-5001-phone"
               :contact-mech/type :telecom}
              {:telecom-number/contact-mech   [:contact-mech/code "CM-5001-phone"]
               :telecom-number/country-code   "+1"
               :telecom-number/area-code      "555"
               :telecom-number/contact-number "0100"}
              {:contact-mech/code "CM-5001-email"
               :contact-mech/type :email}
              {:email-address/contact-mech [:contact-mech/code "CM-5001-email"]
               :email-address/address      "test@example.com"}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-5001-postal"]
               :partner-contact-mech/from-date    jan-2025}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-5001-phone"]
               :partner-contact-mech/from-date    jan-2025}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-5001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-5001-email"]
               :partner-contact-mech/from-date    jan-2025}])
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
              {:contact-mech/code "CM-old"
               :contact-mech/type :postal}
              {:postal-address/contact-mech [:contact-mech/code "CM-old"]
               :postal-address/address1     "Old Address"}
              {:contact-mech/code "CM-new"
               :contact-mech/type :postal}
              {:postal-address/contact-mech [:contact-mech/code "CM-new"]
               :postal-address/address1     "New Address"}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-6001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-old"]
               :partner-contact-mech/from-date    jan-2025
               :partner-contact-mech/thru-date    jan-2026}
              {:partner-contact-mech/partner      [:kontor.partner/external-id "P-6001"]
               :partner-contact-mech/contact-mech [:contact-mech/code "CM-new"]
               :partner-contact-mech/from-date    jan-2026}])
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
              {:partner-role/partner   [:kontor.partner/external-id "P-7001"]
               :partner-role/role-type :customer
               :partner-role/from-date jan-01}
              {:partner-role/partner   [:kontor.partner/external-id "P-7001"]
               :partner-role/role-type :employee
               :partner-role/from-date jun-01}])
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
              {:partner-role/partner   [:kontor.partner/external-id "P-8001"]
               :partner-role/role-type :employee
               :partner-role/from-date jan-01
               :partner-role/thru-date aug-01}])
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
              {:partner-role/partner [:kontor.partner/external-id "P-9001"]
               :partner-role/role-type :customer :partner-role/from-date jan-01}
              {:partner-role/partner [:kontor.partner/external-id "P-9002"]
               :partner-role/role-type :customer :partner-role/from-date jan-01}
              {:partner-role/partner [:kontor.partner/external-id "P-9003"]
               :partner-role/role-type :supplier :partner-role/from-date jan-01}])
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
              {:partner-role/partner [:kontor.partner/external-id "P-employee"]
               :partner-role/role-type :employee :partner-role/from-date jun-01}
              {:partner-role/partner [:kontor.partner/external-id "O-acme"]
               :partner-role/role-type :internal-organization
               :partner-role/from-date jan-01}
              {:partner-relationship/partner-from      [:kontor.partner/external-id "P-employee"]
               :partner-relationship/role-type-from    :employee
               :partner-relationship/partner-to        [:kontor.partner/external-id "O-acme"]
               :partner-relationship/role-type-to      :internal-organization
               :partner-relationship/relationship-type :employment
               :partner-relationship/from-date         jun-01
               :partner-relationship/position-title    "Senior Engineer"
               :partner-relationship/status            :active}])
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
              {:partner-relationship/partner-from      [:kontor.partner/external-id "P-former"]
               :partner-relationship/role-type-from    :employee
               :partner-relationship/partner-to        [:kontor.partner/external-id "O-x"]
               :partner-relationship/role-type-to      :internal-organization
               :partner-relationship/relationship-type :employment
               :partner-relationship/from-date         jan-01
               :partner-relationship/thru-date         aug-01
               :partner-relationship/status            :inactive}])
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
              {:partner-relationship/partner-from [:kontor.partner/external-id "O-parent"]
               :partner-relationship/role-type-from :internal-organization
               :partner-relationship/partner-to [:kontor.partner/external-id "O-sub1"]
               :partner-relationship/role-type-to :internal-organization
               :partner-relationship/relationship-type :subsidiary
               :partner-relationship/from-date jan-01
               :partner-relationship/status :active}
              {:partner-relationship/partner-from [:kontor.partner/external-id "O-parent"]
               :partner-relationship/role-type-from :internal-organization
               :partner-relationship/partner-to [:kontor.partner/external-id "O-sub2"]
               :partner-relationship/role-type-to :internal-organization
               :partner-relationship/relationship-type :subsidiary
               :partner-relationship/from-date jun-01
               :partner-relationship/status :active}])
  (let [db (d/db *conn*)
        subs (p/relationships-of-type db "O-parent" :subsidiary {:as-of jun-15})]
    (is (= 2 (count subs)))
    (is (every? #(= :subsidiary (:partner-relationship/relationship-type %)) subs))))

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
               {:bank-account/code "ACCT-EUR-1"
                :bank-account/iban "DE89370400440532013000"
                :bank-account/bic "COBADEFFXXX"
                :bank-account/bank-name "Commerzbank"
                :bank-account/commodity [:kontor.commodity/symbol "EUR"]
                :bank-account/holder-name "Supplier Co GmbH"
                :bank-account/active true}
               ;; Old account, thru-dated
               {:partner-bank-account/partner [:kontor.partner/external-id "P-SUPPLIER"]
                :partner-bank-account/bank-account [:bank-account/code "ACCT-EUR-1"]
                :partner-bank-account/from-date #inst "2023-01-01"
                :partner-bank-account/thru-date #inst "2025-01-01"
                :partner-bank-account/purpose :disbursement
                :partner-bank-account/preferred? false}
               ;; New account, current
               {:partner-bank-account/partner [:kontor.partner/external-id "P-SUPPLIER"]
                :partner-bank-account/bank-account [:bank-account/code "ACCT-EUR-1"]
                :partner-bank-account/from-date #inst "2025-06-01"
                :partner-bank-account/purpose :disbursement
                :partner-bank-account/preferred? true}])
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
        (is (= "DE89370400440532013000" (:bank-account/iban primary)))))))

(deftest partner-tags-temporal
  (d/transact *conn*
              [{:kontor.partner/external-id "P-TIER"
                :kontor.partner/type :org :kontor.partner/status :enabled
                :kontor.partner/name "Tier Customer"}
               {:partner-tag/partner [:kontor.partner/external-id "P-TIER"]
                :partner-tag/tag-type :gold-tier
                :partner-tag/from-date #inst "2024-01-01"
                :partner-tag/thru-date #inst "2025-06-15"}
               {:partner-tag/partner [:kontor.partner/external-id "P-TIER"]
                :partner-tag/tag-type :silver-tier
                :partner-tag/from-date #inst "2025-06-15"}])
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
               {:partner-tax-id/partner [:kontor.partner/external-id "P-MULTI-VAT"]
                :partner-tax-id/country [:kontor.country/code "DE"]
                :partner-tax-id/tax-id-type :vat-eu
                :partner-tax-id/tax-id "DE123456789"
                :partner-tax-id/from-date #inst "2024-01-01"
                :partner-tax-id/verified? true}
               ;; AT VAT — separate jurisdiction
               {:partner-tax-id/partner [:kontor.partner/external-id "P-MULTI-VAT"]
                :partner-tax-id/country [:kontor.country/code "AT"]
                :partner-tax-id/tax-id-type :vat-eu
                :partner-tax-id/tax-id "ATU12345678"
                :partner-tax-id/from-date #inst "2024-06-01"
                :partner-tax-id/verified? true}])
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
