(ns kontor.payroll-at.accrual-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.payroll-at.accrual :as accrual]
            [kontor.validation :as v]))

(def jan-31 #inst "2026-01-31T00:00:00Z")

(def accrual-rueck-accounts
  [{:kontor.account/code "3710" :kontor.account/path "Verbindlichkeiten:Rueckstellung:Urlaub"
    :kontor.account/type :liability :kontor.account/name "Urlaubsrückstellung"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "3720" :kontor.account/path "Verbindlichkeiten:Rueckstellung:Sonder"
    :kontor.account/type :liability :kontor.account/name "Rückstellung 13./14."
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6000" :kontor.account/path "Aufwendungen:Personal:Gehälter"
    :kontor.account/type :expense :kontor.account/name "Gehälter"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}])

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn accrual-rueck-accounts)
    (d/transact conn [{:kontor.journal/code "PAYROLL"
                       :kontor.journal/name "Lohn"
                       :kontor.journal/type :general
                       :kontor.journal/active true}])
    conn))

(deftest urlaubs-formula
  (testing "Urlaubsrueckstellung amount = daily-base × days × (1 + er-sv)"
    (let [;; 150 EUR/day × 2.083 days × 1.2123 = 312.45 × 1.2123 = 378.7831…
          ;; → 378.78 at HALF-EVEN 2dp
          amt (accrual/urlaubsrueckstellung-amount
               (bigdec "150.00") (bigdec "2.083") (bigdec "0.2123"))]
      (is (= 0 (.compareTo (bigdec "378.78") amt))))))

(deftest sonder-monthly
  (testing "Sonderzahlung monthly = (annual × (1 + sv-rate)) / 12"
    (let [;; (6000 × 1.2123) / 12 = 606.15
          amt (accrual/sonderzahlung-monthly-amount
               (bigdec "6000") (bigdec "0.2123"))]
      (is (= 0 (.compareTo (bigdec "606.15") amt))))))

(deftest urlaubs-posts
  (testing "accrue-urlaubsrueckstellung! produces a balanced 2-line tx"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          report (accrual/accrue-urlaubsrueckstellung!
                  conn
                  {:amount (bigdec "500.00")
                   :journal jnl
                   :commodity eur
                   :effective-date jan-31})
          db (d/db conn)
          balance-on (fn [code]
                       (let [a (d/q '[:find ?a . :in $ ?c
                                      :where [?a :kontor.account/code ?c]]
                                    db code)
                             postings (d/q '[:find [?amt ...]
                                             :in $ ?a
                                             :where
                                             [?p :kontor.posting/account ?a]
                                             [?p :kontor.posting/amount ?amt]]
                                           db a)]
                         (reduce (fn [^java.math.BigDecimal acc
                                      ^java.math.BigDecimal x]
                                   (.add acc x))
                                 0M postings)))]
      (is (some? report))
      (is (= 0 (.compareTo (bigdec "500.00") (balance-on "6000"))))
      (is (= 0 (.compareTo (bigdec "-500.00") (balance-on "3710")))))))

(deftest sonder-posts
  (testing "accrue-sonderzahlung! produces a balanced 2-line tx"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          _ (accrual/accrue-sonderzahlung!
             conn
             {:amount (bigdec "600.00")
              :journal jnl
              :commodity eur
              :effective-date jan-31})
          db (d/db conn)
          balance-on (fn [code]
                       (let [a (d/q '[:find ?a . :in $ ?c
                                      :where [?a :kontor.account/code ?c]]
                                    db code)
                             postings (d/q '[:find [?amt ...]
                                             :in $ ?a
                                             :where
                                             [?p :kontor.posting/account ?a]
                                             [?p :kontor.posting/amount ?amt]]
                                           db a)]
                         (reduce (fn [^java.math.BigDecimal acc
                                      ^java.math.BigDecimal x]
                                   (.add acc x))
                                 0M postings)))]
      (is (= 0 (.compareTo (bigdec "600.00") (balance-on "6000"))))
      (is (= 0 (.compareTo (bigdec "-600.00") (balance-on "3720")))))))
