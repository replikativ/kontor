(ns kontor.payroll-de-datev.e2e-test
  "End-to-end Stage R C2 — DE GmbH monthly payroll round-trip.

   Bootstraps kontor + kontor-hr + the DE-DATEV-LODAS module, seeds
   the SKR04 payroll account subset (the 10 load-bearing accounts
   per note 82 §4.1), creates a :person + :employment + :pay-period,
   then runs `kontor.hr.payroll/run-payroll!` with the DE-DATEV
   provider trio against the Buchungsbeleg fixture
   (`resources/.../fixtures/buchungsbeleg-2025-11.csv`).

   Asserts:
     - the run lands :computed with the correct control totals
     - the :transaction has postings sum-zero per (ledger, commodity)
     - each load-bearing SKR04 account (6020 / 6110 / 3720 / 3730 /
       3740 / 3790) carries the expected amount
     - the EmitProvider produces a LODAS Importdatei audit-doc
       carrying [Allgemein] + [Bewegungsdaten]"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.payroll-de-datev.compute :as datev-compute]
            [kontor.payroll-de-datev.core :as datev]
            [kontor.payroll-de-datev.emit :as datev-emit]
            [kontor.payroll-de-datev.posting-builder :as datev-pb]
            [kontor.payroll-de-datev.wage-types :as wt]))

(def ^:private fixture-buchungsbeleg
  (slurp (io/resource "kontor/payroll_de_datev/fixtures/buchungsbeleg-2025-11.csv")
         :encoding "ISO-8859-1"))

(def ^:private skr04-payroll-accounts
  "The 10 load-bearing SKR04 payroll accounts (note 82 §4.1). We seed
   only what the e2e flow uses to keep the fixture small."
  [{:db/id "acct-6020" :kontor.account/code "6020"
    :kontor.account/name "Gehälter"
    :kontor.account/type :expense :kontor.account/active true}
   {:db/id "acct-6010" :kontor.account/code "6010"
    :kontor.account/name "Löhne"
    :kontor.account/type :expense :kontor.account/active true}
   {:db/id "acct-6035" :kontor.account/code "6035"
    :kontor.account/name "Aufwendungen Urlaubsrückstellung"
    :kontor.account/type :expense :kontor.account/active true}
   {:db/id "acct-6060" :kontor.account/code "6060"
    :kontor.account/name "Freiwillige soziale Aufwendungen, lohnsteuerpflichtig"
    :kontor.account/type :expense :kontor.account/active true}
   {:db/id "acct-6110" :kontor.account/code "6110"
    :kontor.account/name "Gesetzliche soziale Aufwendungen"
    :kontor.account/type :expense :kontor.account/active true}
   {:db/id "acct-3066" :kontor.account/code "3066"
    :kontor.account/name "Urlaubsrückstellung"
    :kontor.account/type :liability :kontor.account/active true}
   {:db/id "acct-3720" :kontor.account/code "3720"
    :kontor.account/name "Verbindlichkeiten aus Löhnen und Gehältern"
    :kontor.account/type :liability :kontor.account/active true}
   {:db/id "acct-3730" :kontor.account/code "3730"
    :kontor.account/name "Verbindlichkeiten aus Lohn- und Kirchensteuer"
    :kontor.account/type :liability :kontor.account/active true}
   {:db/id "acct-3740" :kontor.account/code "3740"
    :kontor.account/name "Verbindlichkeiten im Rahmen der sozialen Sicherheit"
    :kontor.account/type :liability :kontor.account/active true}
   {:db/id "acct-3790" :kontor.account/code "3790"
    :kontor.account/name "Lohn- und Gehaltsverrechnungskonto"
    :kontor.account/type :liability :kontor.account/active true}])

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (datev/install! conn)
    (d/transact conn
                (concat
                 [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                  {:db/id "ent-de" :kontor.entity/code "DE-GMBH" :kontor.entity/name "Acme DE GmbH"
                   :kontor.entity/kind :operating}
                  {:db/id "journal-payroll" :kontor.journal/code "PAY-DE"
                   :kontor.journal/name "Payroll (DE)" :kontor.journal/type :general}
                  {:db/id "period-2025-11" :kontor.period/name "2025-11"
                   :kontor.period/start #inst "2025-11-01"
                   :kontor.period/end   #inst "2025-12-01"}]
                 skr04-payroll-accounts))
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

;; ============================================================================
;; The big one — DE GmbH monthly payroll, parsed Buchungsbeleg, full posting
;; ============================================================================

(deftest e2e-de-gmbh-monthly-payroll-round-trip
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-mueller"
                                       :given-name "Franz" :family-name "Müller"
                                       :birth-date #inst "1958-02-23"
                                       :citizenship ["DE"]})
        db (d/db conn)
        person (hr/person-by-external-id db "P-mueller")
        ent (ref-eid db :kontor.entity/code "DE-GMBH")
        eur (ref-eid db :kontor.commodity/symbol "EUR")
        period (ref-eid db :kontor.period/name "2025-11")
        journal (ref-eid db :kontor.journal/code "PAY-DE")
        _ (employment/hire! conn {:code "EMP-DE-mueller"
                                  :person person
                                  :entity ent
                                  :start-date #inst "2025-01-01"
                                  :job-title "Software Engineer"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-mueller")
        _ (pp/create-pay-period! conn {:code "DE-2025-11"
                                       :entity ent
                                       :start-date #inst "2025-11-01"
                                       :end-date #inst "2025-11-30"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "DE-2025-11")
        ;; Build the DE-DATEV provider trio.
        catalog (wt/validate-catalog
                 {:catalog/version 1
                  :catalog/mandant "99999"
                  :catalog/berater "1234"
                  :catalog/coa     :skr04
                  :catalog/wage-types
                  {100 {:kind :base-salary :account-hint :gehalt}}})
        compute (datev/make-compute-provider
                 {:coa :skr04
                  :employment-pnr->eid {"3011" emp-eid}})
        builder (datev/make-posting-builder
                 {:catalog catalog :commodity eur})
        emitter (datev/make-emit-provider
                 {:catalog catalog
                  :allgemein {:berater-nr "1234"
                              :mandant-nr "99999"
                              :stammdaten-gueltig-ab #inst "2025-11-01"}
                  :pay-period-date #inst "2025-11-01"
                  :pay-period-code "DE-2025-11"})
        ;; Pre-resolve the account-hint → eid map (since :kontor.account/code
        ;; is not :db.unique/identity in the kernel, we cannot rely on
        ;; lookup-ref resolution at transact time — note 82 §9.4 gotcha).
        accounts-map (->> [[:lohn "6010"] [:gehalt "6020"]
                           [:freiwillig-st-pflichtig "6060"]
                           [:soziale-aufwendungen "6110"]
                           [:urlaubsrueckstellung-aufw "6035"]
                           [:urlaubsrueckstellung "3066"]
                           [:verb-lohn "3720"]
                           [:verb-lohnsteuer "3730"]
                           [:verb-sozialversicherung "3740"]
                           [:verrechnung "3790"]]
                          (reduce (fn [m [hint code]]
                                    (if-some [eid (ref-eid (d/db conn)
                                                           :kontor.account/code code)]
                                      (assoc m hint eid)
                                      m))
                                  {}))
        ;; Run.
        report (payroll/run-payroll!
                conn
                {:pay-period pp-eid
                 :entity ent
                 :employments [emp-eid]
                 :compute-provider compute
                 :posting-builder builder
                 :emit-provider emitter
                 :accounts accounts-map
                 :variable-inputs {:buchungsbeleg-content fixture-buchungsbeleg}
                 :run-code "RUN-DE-2025-11-001"
                 :tx-code  "TX-PAYROLL-DE-2025-11"
                 :journal journal
                 :commodity eur})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :kontor.payroll-run/code ?c]]
                     db "RUN-DE-2025-11-001")
        run (d/pull db
                    '[* {:kontor.payroll-run/payroll-transaction
                         [:kontor.transaction/external-id
                          {:kontor.posting/_transaction
                           [:kontor.posting/amount
                            {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)
        ;; Note 86 P0-86-1 fix: substrate orchestrator now links
        ;; emit-docs via :kontor.payroll-run/emit-docs. Query through the
        ;; run row's reverse-walk; verify the substrate contract.
        emit-doc-eids (mapv :db/id
                            (:kontor.payroll-run/emit-docs
                             (d/pull db [{:kontor.payroll-run/emit-docs [:db/id]}]
                                     run-eid)))
        emit-doc (when (seq emit-doc-eids)
                   (d/pull db '[*] (first emit-doc-eids)))]
    (testing "payroll-run row + control totals"
      (is (= 4000.00M (:kontor.payroll-run/control-total-gross run)))
      (is (= 2500.00M (:kontor.payroll-run/control-total-net   run)))
      (is (= :datev-lodas (:kontor.payroll-run/provider-id run))))
    (testing "transaction posted with the 10-leg Bruttomethode shape"
      (let [tx (:kontor.payroll-run/payroll-transaction run)
            postings (:kontor.posting/_transaction tx)]
        (is (= "TX-PAYROLL-DE-2025-11" (:kontor.transaction/external-id tx)))
        (is (= 10 (count postings)))
        (let [sum (reduce (fn [^java.math.BigDecimal a {:kontor.posting/keys [amount]}]
                            (.add a ^java.math.BigDecimal amount))
                          0M postings)]
          (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))
        (testing "expected amounts on each SKR04 account"
          (let [by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
                amounts (fn [code]
                          (sort (map :kontor.posting/amount (get by-code code []))))]
            (is (= [4000.00M]               (amounts "6020")))   ; gross expense
            (is (= [800.00M]                (amounts "6110")))   ; AG-SV
            (is (= [-2500.00M]              (amounts "3720")))   ; Verb. Lohn
            (is (= [-700.00M]               (amounts "3730")))   ; Verb. LSt
            (is (= [-800.00M -800.00M]      (amounts "3740")))   ; Verb. SV (AN + AG)
            ;; Verrechnung carries 5 legs that net to zero:
            ;; +700 (LSt), +800 (AN-SV), +2500 (Net), and -4000 (against gross expense).
            ;; Sum = -4000 + 700 + 800 + 2500 = 0.
            (let [v (->> (get by-code "3790")
                         (map :kontor.posting/amount)
                         (reduce (fn [^java.math.BigDecimal a ^java.math.BigDecimal x]
                                   (.add a x))
                                 0M))]
              (is (zero? (.compareTo ^java.math.BigDecimal v 0M))))))))
    (testing "EmitProvider produced one LODAS Importdatei audit-doc"
      (is (some? emit-doc))
      (is (= :payroll-filing (:kontor.audit-doc/category emit-doc)))
      (is (= "LODAS-DE-2025-11" (:kontor.audit-doc/code emit-doc)))
      (is (str/includes? (:kontor.audit-doc/inline-payload emit-doc) "[Allgemein]"))
      (is (str/includes? (:kontor.audit-doc/inline-payload emit-doc) "Ziel=LODAS"))
      (is (str/includes? (:kontor.audit-doc/inline-payload emit-doc) "[Bewegungsdaten]")))))
