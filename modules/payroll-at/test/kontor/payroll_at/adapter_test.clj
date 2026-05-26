(ns kontor.payroll-at.adapter-test
  "End-to-end smoke for the AT bridge into the kernel
   `kontor.payroll-provider` trio. Mirrors the structural template of
   `modules/payroll-ca/test/kontor/payroll_ca/e2e_test.clj`:

     - bootstrap an AT db (kernel + l10n-at chart + HR substrate)
     - install the AT payroll wage accounts on top
     - hire two people and create a single :pay-period
     - thread `kontor.payroll-at.adapter` providers through
       `kontor.hr.payroll/run-payroll!` with a BMD CSV fixture
     - assert the :payroll-run row, the balanced GL transaction,
       and the mBGM :audit-doc with :payroll-filing + :de language."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-at.chart :as at-chart]
            [kontor.payroll-at.adapter :as adapter]
            [kontor.payroll-at.posting-builder-test :as pb-test]
            [kontor.validation :as v])
  (:import [java.math BigDecimal]))

(def jan-31 #inst "2026-01-31T00:00:00Z")

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (hr/install! conn)
    (at-chart/install! conn)
    (d/transact conn pb-test/payroll-wage-accounts)
    (d/transact conn [{:db/id "ent-acme"
                       :kontor.entity/code "ACME-AT"
                       :kontor.entity/name "Acme GmbH"
                       :kontor.entity/kind :operating}
                      {:db/id "journal-pay"
                       :kontor.journal/code "PAYROLL-AT"
                       :kontor.journal/name "Lohn- und Gehaltsabrechnung"
                       :kontor.journal/type :general
                       :kontor.journal/active true}
                      {:db/id "period-2026-01"
                       :kontor.period/name "2026-01"
                       :kontor.period/start #inst "2026-01-01"
                       :kontor.period/end #inst "2026-02-01"}])
    conn))

;; ============================================================================
;; The end-to-end smoke — one transaction through the kernel orchestrator
;; ============================================================================

(deftest at-bridge-end-to-end-through-run-payroll!
  (let [conn (bootstrap)
        db (d/db conn)
        eur (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "EUR"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-AT"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAYROLL-AT"]] db)
        period (d/q '[:find ?e . :where [?e :kontor.period/name "2026-01"]] db)
        _ (person/create-person!
           conn {:external-id "P-max" :given-name "Max" :family-name "Mustermann"})
        _ (person/create-person!
           conn {:external-id "P-erika" :given-name "Erika" :family-name "Beispiel"})
        db (d/db conn)
        max (hr/person-by-external-id db "P-max")
        erika (hr/person-by-external-id db "P-erika")
        _ (employment/hire! conn {:code "EMP-max"
                                  :person max :entity ent
                                  :start-date #inst "2026-01-01"
                                  :job-title "Engineer"})
        _ (employment/hire! conn {:code "EMP-erika"
                                  :person erika :entity ent
                                  :start-date #inst "2026-01-01"
                                  :job-title "Designer"})
        db (d/db conn)
        max-emp (hr/employment-by-code db "EMP-max")
        erika-emp (hr/employment-by-code db "EMP-erika")
        _ (comp/set-compensation!
           conn {:employment max-emp
                 :effective-from #inst "2026-01-01"
                 :commodity eur
                 :components [{:kind :base-wage :amount 36000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment erika-emp
                 :effective-from #inst "2026-01-01"
                 :commodity eur
                 :components [{:kind :base-wage :amount 30000M :period :annual}]})
        _ (pp/create-pay-period!
           conn {:code "ACME-2026-01" :entity ent
                 :start-date #inst "2026-01-01"
                 :end-date #inst "2026-01-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-2026-01")
        ;; Build the bridge trio
        providers (adapter/make-at-kontor-providers
                   {:engine :bmd
                    :db (d/db conn)
                    :commodity eur
                    :use-default-rlg-1? true
                    :dienstgeber-beitragskonto "1234567"
                    :employer-name "Acme GmbH"
                    :storage-uri-template "s3://kontor-test/mbgm/%s.xml"})
        ;; CSV fixture maps VSNR strings to employment eids
        emp-by-vsnr {"1234567890" max-emp
                     "9876543210" erika-emp}
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [max-emp erika-emp]
                      :compute-provider (:compute-provider providers)
                      :posting-builder  (:posting-builder providers)
                      :emit-provider    (:emit-provider providers)
                      :accounts {} ; default RLG-1 codes
                      :variable-inputs {:csv-source (fixture "bmd-2026-01.csv")
                                        :employment-by-vsnr emp-by-vsnr
                                        :commodity-eid eur}
                      :run-code "ACME-2026-01-001"
                      :tx-code "TX-ACME-2026-01"
                      :journal journal
                      :commodity eur
                      :vt-from jan-31})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "ACME-2026-01-001")
        run (d/pull db '[* {:payroll-run/payroll-transaction
                            [:kontor.transaction/external-id
                             {:kontor.posting/_transaction
                              [:kontor.posting/amount
                               {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)]
    (testing ":payroll-run row was created and tagged with :at/bmd"
      (is (some? run-eid))
      (is (= :computed (:payroll-run/state run)))
      (is (= :at/bmd (:payroll-run/provider-id run))))
    (testing "Control totals match both employees (5500 gross — 3000 + 2500)"
      (is (= 0 (.compareTo ^BigDecimal (bigdec 5500)
                           ^BigDecimal (:payroll-run/control-total-gross run)))))
    (testing "Posting legs sum to zero per ledger × commodity"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            sum (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M))
            (str "expected balanced postings, got sum=" sum))))
    (testing "Per-account balances match the AT RLG-1 fixture totals"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            sum-of (fn [code]
                     (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                               (.add a ^BigDecimal amount))
                             0M (get by-code code [])))]
        ;; Grundgehalt expense: 3000 + 2500 = 5500
        (is (= 0 (.compareTo (bigdec 5500) ^BigDecimal (sum-of "6000"))))
        ;; SV-Arbeitgeber expense: 636.90 + 530.75 = 1167.65
        (is (= 0 (.compareTo (bigdec "1167.65") ^BigDecimal (sum-of "6500"))))
        ;; KomSt expense: 90 + 75 = 165
        (is (= 0 (.compareTo (bigdec 165) ^BigDecimal (sum-of "6520"))))
        ;; LSt-Verbindlichkeit (Cr): -500 + -380 = -880
        (is (= 0 (.compareTo (bigdec -880) ^BigDecimal (sum-of "3500"))))
        ;; SV-Verbindl. (AN 543.60 + 453 = 996.60 ; AG 636.90 + 530.75 = 1167.65 → sum -2164.25)
        (is (= 0 (.compareTo (bigdec "-2164.25") ^BigDecimal (sum-of "3540"))))
        ;; Nettogehalt payable: -1956.40 + -1667 = -3623.40
        (is (= 0 (.compareTo (bigdec "-3623.40") ^BigDecimal (sum-of "3700"))))))
    (testing "mBGM :audit-doc landed with :payroll-filing category + :de language"
      (let [docs (d/q '[:find [?e ...]
                        :where
                        [?e :audit-doc/category :payroll-filing]
                        [?e :audit-doc/language :de]
                        [?e :audit-doc/type :mbgm]]
                      db)]
        (is (= 1 (count docs)) "exactly one mBGM audit-doc for the run")
        (let [doc (d/pull db '[*] (first docs))]
          (is (string? (:audit-doc/content-hash doc)))
          (is (= 64 (count (:audit-doc/content-hash doc)))
              "SHA-256 hex is 64 chars")
          (is (= "s3://kontor-test/mbgm/2026-01.xml"
                 (:audit-doc/storage-uri doc)))
          (is (re-find #"mbgm-2026-01-\d+" (:audit-doc/code doc))
              ":audit-doc/code includes the yyyy-MM stamp"))))
    (testing "Run row links to the audit-doc via :payroll-run/emit-docs"
      (let [linked (d/q '[:find [?d ...]
                          :in $ ?r
                          :where [?r :payroll-run/emit-docs ?d]]
                        db run-eid)]
        (is (= 1 (count linked)))))))
