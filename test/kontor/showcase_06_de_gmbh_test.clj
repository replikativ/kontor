(ns kontor.showcase-06-de-gmbh-test
  "Integration test backing doc/showcases/06_de_gmbh_multi_year.clj.

   The Clay showcase is the narrative artifact; this test asserts
   the load-bearing invariants of the same scenario so a regression
   in any substrate primitive surfaces here rather than as a broken
   showcase render.

   Substrate exercised end-to-end:
     - kontor-hr                — person + employment + compensation
     - kontor.hr.consent        — ADR-094 :consent/* grant + active-at?
     - kontor.audit-doc         — canonical category vocabulary
     - kontor-people-record     — :position-held + :promotion
     - kontor.l10n-de.retention — DE retention seeds + sweeper
     - kontor.bitemporal        — close-validity! for the backdated
                                  correction story
     - kontor.dsar              — partner walker + :extensions :hr
     - kontor-payroll-de-datev  — real DATEV-LODAS provider trio
                                  (smoke-tested only; full assertion
                                  in modules/payroll-de-datev/test/)"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.bitemporal :as kbt]
            [kontor.core :as core]
            [kontor.dsar :as dsar]
            [kontor.hr.compensation :as comp]
            [kontor.hr.consent :as consent]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.person :as person]
            [kontor.l10n-de.retention :as de-retention]
            [kontor.payroll-de-datev.core :as datev]
            [kontor.payroll-de-datev.wage-types :as datev-wt]
            [kontor.people-record.core :as pr]
            [kontor.people-record.schema :as pr-schema]
            [kontor.posting :as posting]
            [kontor.retention :as retention]
            [kontor.validation :as validation]))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (datev/install! conn)
    (pr/install! conn)
    (de-retention/install! conn)
    (d/transact
     conn
     [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
      {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme Manufacturing GmbH"
       :kontor.entity/active true}
      {:journal/code "PAY-DE" :journal/name "Payroll DE" :journal/type :general}
      {:journal/code "GEN-DE" :journal/name "General DE" :journal/type :general}
      {:period/name "2026" :period/start #inst "2026-01-01"
       :period/end #inst "2027-01-01"}
      ;; SKR04 payroll accounts (matching the fixture)
      {:account/code "6010" :account/name "Löhne" :account/type :expense :account/active true}
      {:account/code "6020" :account/name "Gehälter" :account/type :expense :account/active true}
      {:account/code "6035" :account/name "Urlaubsrück-Aufw" :account/type :expense :account/active true}
      {:account/code "6060" :account/name "Soziale Aufw (freiwillig)" :account/type :expense :account/active true}
      {:account/code "6110" :account/name "Soziale Aufw (gesetzlich)" :account/type :expense :account/active true}
      {:account/code "3066" :account/name "Urlaubsrückstellung" :account/type :liability :account/active true}
      {:account/code "3720" :account/name "Verb LuG" :account/type :liability :account/active true}
      {:account/code "3730" :account/name "Verb LSt" :account/type :liability :account/active true}
      {:account/code "3740" :account/name "Verb SV" :account/type :liability :account/active true}
      {:account/code "3790" :account/name "Verrechnung" :account/type :liability :account/active true}
      ;; Operating accounts for the misclassification story
      {:account/code "4660" :account/name "Reisekosten AN" :account/type :expense :account/active true}
      {:account/code "4650" :account/name "Bewirtungskosten 70%" :account/type :expense :account/active true}
      {:account/code "1000" :account/name "Kasse" :account/type :asset :account/active true}])
    conn))

(deftest multi-year-de-gmbh-end-to-end
  (let [conn (bootstrap)
        eur (ref-eid (d/db conn) :kontor.commodity/symbol "EUR")
        ent (ref-eid (d/db conn) :kontor.entity/code "ACME-DE")
        j-gen (ref-eid (d/db conn) :journal/code "GEN-DE")]

    ;; ===== Year 1 setup =====
    (person/create-person! conn {:external-id "P-mueller"
                                 :given-name "Franz" :family-name "Müller"
                                 :birth-date #inst "1968-05-04"})
    (person/create-person! conn {:external-id "P-schmidt"
                                 :given-name "Anna" :family-name "Schmidt"
                                 :birth-date #inst "1985-09-22"})
    (let [mueller (hr/person-by-external-id (d/db conn) "P-mueller")
          schmidt (hr/person-by-external-id (d/db conn) "P-schmidt")
          _ (audit-doc/create-doc!
             conn {:code "DPIA-acme-2026"
                   :type :dpia
                   :storage-uri "s3://test/dpia.pdf"
                   :category :hr-monitoring-consent})
          dpia (ref-eid (d/db conn) :audit-doc/code "DPIA-acme-2026")
          _ (doseq [[code subj] [["CONS-mueller" mueller]
                                 ["CONS-schmidt" schmidt]]]
              (consent/grant!
               conn {:code code :subject subj :scope :hr-track-record
                     :legal-basis :bdsg-26-1-employment
                     :granted-at #inst "2026-01-15"
                     :supporting-doc dpia}))
          _ (employment/hire! conn {:code "EMP-mueller" :person mueller
                                    :entity ent :start-date #inst "2026-01-15"
                                    :job-title "Geschäftsführer"})
          _ (employment/hire! conn {:code "EMP-schmidt" :person schmidt
                                    :entity ent :start-date #inst "2026-01-15"
                                    :job-title "Vertriebsmitarbeiterin"})
          emp-schmidt (hr/employment-by-code (d/db conn) "EMP-schmidt")
          _ (comp/set-compensation!
             conn {:employment emp-schmidt :effective-from #inst "2026-01-15"
                   :commodity eur
                   :components [{:kind :base-wage :amount 4200M :period :monthly}]})
          _ (pr/record-position!
             conn {:code "POS-schmidt-vs-1" :person schmidt :employment emp-schmidt
                   :title "Vertriebsmitarbeiterin" :level :ic-2
                   :start-date #inst "2026-01-15" :at #inst "2026-01-15"})]

      (testing "Year 1 — consents active, positions recorded"
        (is (true? (consent/active-at? (d/db conn) schmidt :hr-track-record
                                       #inst "2026-06-01")))
        (is (some? (ref-eid (d/db conn) :position-held/external-id "POS-schmidt-vs-1"))))

      ;; ===== Year 1 misclassified expense =====
      ;; Capture the datahike commit-tx eid via the tx-report (per
      ;; the bitemporal_test/post-tx-with-vt! pattern). The kontor
      ;; :transaction entity is the BUSINESS row; close-validity
      ;; needs the underlying datahike commit-tx instead.
      (let [tx-report (validation/transact-with-validation
                       conn
                       (kbt/with-vt
                         (posting/post-transaction-tx-data
                          {:transaction {:transaction/external-id "TX-DINNER-2026"
                                         :transaction/journal j-gen
                                         :transaction/effective-date #inst "2026-11-22"
                                         :transaction/narration "Misclassified business dinner"}
                           :postings
                           [{:posting/account (ref-eid (d/db conn) :account/code "4660")
                             :posting/amount 1200.00M
                             :posting/commodity eur}
                            {:posting/account (ref-eid (d/db conn) :account/code "1000")
                             :posting/amount -1200.00M
                             :posting/commodity eur}]})
                         #inst "2026-11-22"))
            misclassified-tx-eid (kbt/commit-tx-eid tx-report)]

        (testing "Y1 misclassified posting visible at Y1 end"
          (let [db (d/valid-at (d/db conn) #inst "2026-12-31")
                postings
                (d/q '[:find ?amt
                       :in $ ?a
                       :where
                       [?p :posting/account ?a]
                       [?p :posting/amount ?amt]]
                     db (ref-eid (d/db conn) :account/code "4660"))]
            (is (= #{[1200.00M]} postings)
                "original Reisekosten posting visible at 2026-12-31")))

        ;; ===== Year 2 promotion + comp supersession =====
        (let [_ (comp/set-compensation!
                 conn {:employment emp-schmidt
                       :effective-from #inst "2027-03-01"
                       :commodity eur
                       :components [{:kind :base-wage :amount 5400M :period :monthly}]})
              _ (pr/record-position!
                 conn {:code "POS-schmidt-leiter-1"
                       :person schmidt :employment emp-schmidt
                       :title "Vertriebsleiterin" :level :manager
                       :start-date #inst "2027-03-01"
                       :at #inst "2027-03-01"})
              pos-old (ref-eid (d/db conn) :position-held/external-id "POS-schmidt-vs-1")
              pos-new (ref-eid (d/db conn) :position-held/external-id "POS-schmidt-leiter-1")
              _ (pr/record-promotion!
                 conn {:code "PROMO-schmidt-2027"
                       :person schmidt
                       :from-position pos-old :to-position pos-new
                       :effective-date #inst "2027-03-01"
                       :at #inst "2027-03-01"})]
          (testing "Y2 promotion recorded"
            (is (some? (ref-eid (d/db conn) :promotion/external-id "PROMO-schmidt-2027")))
            (is (= 2 (count (d/q '[:find [?p ...]
                                   :in $ ?s
                                   :where [?p :position-held/person ?s]]
                                 (d/db conn) schmidt))))))

        ;; ===== Year 2 Q4 backdated correction =====
        (kbt/close-validity! conn misclassified-tx-eid #inst "2027-10-15")

        (validation/transact-with-validation
         conn
         (kbt/with-vt
           (posting/post-transaction-tx-data
            {:transaction {:transaction/external-id "TX-DINNER-2026-CORR"
                           :transaction/journal j-gen
                           :transaction/effective-date #inst "2026-11-22"
                           :transaction/narration "Bewirtungskosten correction Oct 2027"}
             :postings
             [{:posting/account (ref-eid (d/db conn) :account/code "4650")
               :posting/amount 1200.00M
               :posting/commodity eur}
              {:posting/account (ref-eid (d/db conn) :account/code "1000")
               :posting/amount -1200.00M
               :posting/commodity eur}]})
           #inst "2027-10-15"))

        (testing "Bitemporal correction story end-to-end"
          (testing "AT 2026-12-31 (pre-correction): original Reisekosten posting visible"
            (let [db (d/valid-at (d/db conn) #inst "2026-12-31")]
              (is (= #{[1200.00M]}
                     (d/q '[:find ?amt :in $ ?a
                            :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
                          db (ref-eid (d/db conn) :account/code "4660"))))
              (is (= #{}
                     (d/q '[:find ?amt :in $ ?a
                            :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
                          db (ref-eid (d/db conn) :account/code "4650")))
                  "the Bewirtungskosten correction wasn't recorded yet at Y1 end")))

          (testing "AT 2027-11-01 (post-correction): restated Bewirtungskosten visible"
            (let [db (d/valid-at (d/db conn) #inst "2027-11-01")]
              (is (= #{}
                     (d/q '[:find ?amt :in $ ?a
                            :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
                          db (ref-eid (d/db conn) :account/code "4660")))
                  "the original misclassified posting is no longer authoritative")
              (is (= #{[1200.00M]}
                     (d/q '[:find ?amt :in $ ?a
                            :where [?p :posting/account ?a] [?p :posting/amount ?amt]]
                          db (ref-eid (d/db conn) :account/code "4650")))
                  "the corrected posting IS authoritative"))))

        ;; ===== Year 3 DSAR + retention sweep =====
        (d/transact conn
                    [{:kontor.partner/external-id "PARTNER-schmidt"
                      :kontor.partner/name "Anna Schmidt"
                      :kontor.partner/kind :employee
                      :kontor.partner/person schmidt}])
        (let [schmidt-partner (ref-eid (d/db conn) :kontor.partner/external-id "PARTNER-schmidt")
              kernel-bundle (dsar/collect (d/db conn) schmidt-partner {})
              pr-bundle (pr/dsar-bundle (d/db conn) schmidt)]

          (testing "Kernel DSAR walker reaches HR via :extensions :hr (note 86 P1-86-5 fix)"
            (let [hr-ext (get-in kernel-bundle [:extensions :hr])]
              (is (some? hr-ext))
              (is (pos? (count (:employments hr-ext))))
              (is (pos? (count (:compensations hr-ext))))))

          (testing "people-record DSAR bundle reaches Schmidt's track record"
            (is (= 2 (count (:positions pr-bundle))))
            (is (= 1 (count (:promotions pr-bundle)))))

          (testing "Kernel DSAR walker ALSO reaches people-record via :extensions :people-record
                    (closes the ADR-094 silent compliance gap flagged in note 95)"
            (let [pr-ext (get-in kernel-bundle [:extensions :people-record])]
              (is (some? pr-ext)
                  "kontor.people-record.core/install! registers the extension collector")
              (is (= 2 (count (:positions pr-ext))))
              (is (= 1 (count (:promotions pr-ext))))))

          (testing "DPIA retention — not yet eligible at Y3 (10-year floor)"
            (let [dpia (ref-eid (d/db conn) :audit-doc/code "DPIA-acme-2026")
                  policy (ref-eid (d/db conn) :retention-policy/code
                                  "DE-DSGVO-hr-monitoring-consent")]
              (is (= false
                     (retention/eligible? (d/db conn) dpia policy
                                          {:as-of #inst "2028-09-15"}))))))))))
