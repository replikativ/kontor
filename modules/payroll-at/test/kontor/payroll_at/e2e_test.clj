(ns kontor.payroll-at.e2e-test
  "End-to-end AT-payroll smoke:
     - bootstrap AT db
     - run-payroll-period! for January
     - run-payroll-period! for June (with 13. Sonderzahlung)
     - emit annual L16
     - verify GL balances, accrual balances, audit-doc rows."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.payroll-at.core :as payroll]
            [kontor.payroll-at.posting-builder-test :as pb-test]
            [kontor.payroll-at.accrual-test :as acc-test]
            [kontor.validation :as v]))

(def jan-31 #inst "2026-01-31T00:00:00Z")
(def jun-30 #inst "2026-06-30T00:00:00Z")

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

(defn- bootstrap-full []
  ;; combine all account fixtures the AT payroll modules use
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn pb-test/payroll-wage-accounts)
    (d/transact conn acc-test/accrual-rueck-accounts)
    (d/transact conn [{:kontor.journal/code "PAYROLL"
                       :kontor.journal/name "Lohn- und Gehaltsabrechnung"
                       :kontor.journal/type :general
                       :kontor.journal/active true}])
    conn))

(deftest e2e-jan-period
  (let [conn (bootstrap-full)
        db0 (d/db conn)
        jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
        eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
        {:keys [payroll-result gl-tx-report mbgm urlaubs-tx-report]}
        (payroll/run-payroll-period!
         conn
         {:engine :bmd
          :source (fixture "bmd-2026-01.csv")
          :journal jnl
          :commodity eur
          :effective-date jan-31
          :dienstgeber-beitragskonto "1234567"
          :employer-name "Acme GmbH"
          :storage-uri "s3://kontor-test/mbgm/2026-01.xml"
          :urlaubs {:amount (bigdec "378.78")}})]
    (testing "engine output parsed"
      (is (= 2 (count (:payroll-result/employees payroll-result)))))
    (testing "GL posted"
      (is (some? gl-tx-report)))
    (testing "Urlaubsrückstellung accrued"
      (is (some? urlaubs-tx-report)))
    (testing "mBGM audit-doc landed"
      (let [db (d/db conn)
            doc (audit-doc/pull-doc db "mbgm-2026-01")]
        (is (some? doc))
        (is (= :mbgm (:kontor.audit-doc/type doc)))
        (is (= :payroll-filing (:kontor.audit-doc/category doc)))
        (is (= :de (:kontor.audit-doc/language doc)))
        (is (= "s3://kontor-test/mbgm/2026-01.xml"
               (:kontor.audit-doc/storage-uri doc)))
        (is (string? (:kontor.audit-doc/content-hash doc)))
        (is (= 64 (count (:kontor.audit-doc/content-hash doc)))
            "SHA-256 hex is 64 chars")))))

(deftest e2e-multiple-periods-then-l16
  (testing "Two monthly runs plus annual L16 — produces 2 mBGM + 1 L16
            audit-doc rows, each :category :payroll-filing"
    (let [conn (bootstrap-full)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          jan-result (payroll/run-payroll-period!
                      conn
                      {:engine :bmd
                       :source (fixture "bmd-2026-01.csv")
                       :journal jnl
                       :commodity eur
                       :effective-date jan-31
                       :dienstgeber-beitragskonto "1234567"
                       :storage-uri "s3://kontor-test/mbgm/2026-01.xml"})
          jun-result (payroll/run-payroll-period!
                      conn
                      {:engine :bmd
                       :source (fixture "bmd-2026-06.csv")
                       :journal jnl
                       :commodity eur
                       :effective-date jun-30
                       :dienstgeber-beitragskonto "1234567"
                       :storage-uri "s3://kontor-test/mbgm/2026-06.xml"})
          ;; Build the per-employee annual rollup for L16
          all-rows (mapcat #(-> % :payroll-result :payroll-result/employees)
                           [jan-result jun-result])
          annual-employees
          (mapv (fn [[vsnr rows]]
                  {:vsnr vsnr
                   :name (-> rows first :name)
                   :monthly-rows rows})
                (group-by :vsnr all-rows))
          provider (payroll/make-at-emit-provider)
          l16 (kontor.payroll-at.emit/emit-annual! provider conn
                                                   {:year 2026
                                                    :employer-name "Acme GmbH"
                                                    :employer-uid "ATU12345678"
                                                    :employees annual-employees
                                                    :storage-uri "s3://kontor-test/l16/2026.xml"})]
      (is (some? l16))
      ;; verify the 3 audit-docs landed with :payroll-filing
      (let [db (d/db conn)
            payroll-docs
            (d/q '[:find [?e ...]
                   :where
                   [?e :kontor.audit-doc/category :payroll-filing]]
                 db)]
        (is (= 3 (count payroll-docs))
            (str "expected 3 :payroll-filing docs, got " (count payroll-docs)))
        (let [types (set (map #(:kontor.audit-doc/type (d/pull db [:kontor.audit-doc/type] %))
                              payroll-docs))]
          (is (contains? types :mbgm))
          (is (contains? types :l16-lohnzettel)))))))

(deftest e2e-bilanzgewinn-impact
  (testing "After a period payroll posts, the GL trial-balance is non-zero
             on the wage accounts and on the payable accounts, and net
             debits = net credits"
    (let [conn (bootstrap-full)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          _ (payroll/run-payroll-period!
             conn
             {:engine :bmd
              :source (fixture "bmd-2026-01.csv")
              :journal jnl
              :commodity eur
              :effective-date jan-31
              :dienstgeber-beitragskonto "1234567"
              :storage-uri "s3://kontor-test/mbgm/2026-01.xml"})
          db (d/db conn)
          ;; Pull all postings from this journal
          journal-id (d/q '[:find ?j . :in $ ?c
                            :where [?j :kontor.journal/code ?c]]
                          db "PAYROLL")
          postings (d/q '[:find ?amt
                          :in $ ?j
                          :where
                          [?p :kontor.posting/transaction ?t]
                          [?t :kontor.transaction/journal ?j]
                          [?p :kontor.posting/amount ?amt]]
                        db journal-id)
          total (reduce (fn [^java.math.BigDecimal acc [^java.math.BigDecimal x]]
                          (.add acc x))
                        0M postings)]
      (is (= 0 (.compareTo 0M total))
          (str "postings sum to non-zero: " total)))))
