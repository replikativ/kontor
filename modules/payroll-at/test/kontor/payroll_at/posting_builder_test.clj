(ns kontor.payroll-at.posting-builder-test
  "AT-payroll posting-builder end-to-end:
     - install the AT Kontenrahmen
     - add the payroll wage accounts (the chart doesn't ship them)
     - parse a BMD fixture
     - post the GL transaction
     - verify balanced + state."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.payroll-at.compute :as compute]
            [kontor.payroll-at.posting-builder :as pb]
            [kontor.validation :as v]))

(def jan-31 #inst "2026-01-31T00:00:00Z")
(def jun-30 #inst "2026-06-30T00:00:00Z")

;; ============================================================================
;; Fixtures
;; ============================================================================

(def payroll-wage-accounts
  "The wage / payable accounts the posting builder routes to. NOT in
   the l10n-at Kontenrahmen (which is a UVA-focused subset); test
   fixture installs them on top."
  [;; Aufwendungen — class 6
   {:kontor.account/code "6000" :kontor.account/path "Aufwendungen:Personal:Gehälter"
    :kontor.account/type :expense :kontor.account/name "Gehälter"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6400" :kontor.account/path "Aufwendungen:Personal:Urlaubsremuneration"
    :kontor.account/type :expense :kontor.account/name "Urlaubsremuneration (13.)"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6410" :kontor.account/path "Aufwendungen:Personal:Weihnachtsremuneration"
    :kontor.account/type :expense :kontor.account/name "Weihnachtsremuneration (14.)"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6500" :kontor.account/path "Aufwendungen:Personal:SV-AG"
    :kontor.account/type :expense :kontor.account/name "Sozialaufwand-Arbeitgeber"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6510" :kontor.account/path "Aufwendungen:Personal:DB-FLAG"
    :kontor.account/type :expense :kontor.account/name "DB FLAG (4.1%)"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6520" :kontor.account/path "Aufwendungen:Personal:KomSt"
    :kontor.account/type :expense :kontor.account/name "Kommunalsteuer (3%)"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6530" :kontor.account/path "Aufwendungen:Personal:DZ"
    :kontor.account/type :expense :kontor.account/name "Zuschlag zum DB"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "6800" :kontor.account/path "Aufwendungen:Personal:Sachbezüge"
    :kontor.account/type :expense :kontor.account/name "Sachbezugsaufwand"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}

   ;; Verbindlichkeiten — class 3
   {:kontor.account/code "3540" :kontor.account/path "Verbindlichkeiten:SV"
    :kontor.account/type :liability :kontor.account/name "SV-Verbindlichkeit"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "3550" :kontor.account/path "Verbindlichkeiten:DB"
    :kontor.account/type :liability :kontor.account/name "DB+DZ-Verbindlichkeit"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "3560" :kontor.account/path "Verbindlichkeiten:KomSt"
    :kontor.account/type :liability :kontor.account/name "Kommunalsteuer-Verbindlichkeit"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   {:kontor.account/code "3590" :kontor.account/path "Verbindlichkeiten:Sachbezug-Clearing"
    :kontor.account/type :liability :kontor.account/name "Sachbezug-Clearing"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
   ;; 3700 the net pay payable
   {:kontor.account/code "3700" :kontor.account/path "Verbindlichkeiten:Lohn"
    :kontor.account/type :liability :kontor.account/name "Verbindlichkeit Lohn"
    :kontor.account/active true
    :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}])

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    ;; install the payroll wage accounts on top
    (d/transact conn payroll-wage-accounts)
    (d/transact conn [{:journal/code "PAYROLL"
                       :journal/name "Lohn- und Gehaltsabrechnung"
                       :journal/type :general
                       :journal/active true}])
    conn))

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest jan-payroll-posts-and-balances
  (let [conn (bootstrap)
        db0 (d/db conn)
        jnl (:db/id (d/entity db0 [:journal/code "PAYROLL"]))
        eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
        result (compute/parse :bmd (fixture "bmd-2026-01.csv"))
        report (pb/post! conn
                         {:payroll-result result
                          :journal jnl
                          :commodity eur
                          :effective-date jan-31})]
    (is (some? (:tx-data report)))
    ;; the kernel's posting validator threw if unbalanced — successful
    ;; transact means the postings sum to zero in EUR.
    (let [db (d/db conn)
          tx-id (some-> (d/q '[:find ?t .
                               :in $ ?ext
                               :where [?t :transaction/external-id ?ext]]
                             db "payroll-at-2026-01"))]
      (is (some? tx-id) "transaction landed with the expected external-id")
      (let [postings (d/q '[:find ?p
                            :in $ ?t
                            :where [?p :posting/transaction ?t]]
                          db tx-id)]
        (is (<= 6 (count postings))
            (str "got " (count postings) " postings"))))))

(deftest balance-by-account
  (testing "the GL transaction lands the expected per-account balances"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          result (compute/parse :bmd (fixture "bmd-2026-01.csv"))
          _ (pb/post! conn
                      {:payroll-result result
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
                                             [?p :posting/account ?a]
                                             [?p :posting/amount ?amt]]
                                           db a)]
                         (reduce (fn [^java.math.BigDecimal acc
                                      ^java.math.BigDecimal x]
                                   (.add acc x))
                                 0M postings)))]
      ;; Aufwendungen: Grundgehalt sum 5500
      (is (= 0 (.compareTo (bigdec "5500.00") (balance-on "6000"))))
      ;; SV-AG sum 1167.65
      (is (= 0 (.compareTo (bigdec "1167.65") (balance-on "6500"))))
      ;; KomSt sum 165.00
      (is (= 0 (.compareTo (bigdec "165.00") (balance-on "6520"))))
      ;; Liabilities (Cr → negative on the account)
      ;; LSt 880 → -880
      (is (= 0 (.compareTo (bigdec "-880.00") (balance-on "3500"))))
      ;; SV-Verbindl: -996.60 (AN) + -1167.65 (AG) = -2164.25
      (is (= 0 (.compareTo (bigdec "-2164.25") (balance-on "3540"))))
      ;; Net pay payable: -3623.40
      (is (= 0 (.compareTo (bigdec "-3623.40") (balance-on "3700")))))))

(deftest june-payroll-with-sonderzahlung
  (testing "June period posts the 13th Sonderzahlung onto 6400"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:journal/code "PAYROLL"]))
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          result (compute/parse :bmd (fixture "bmd-2026-06.csv"))
          _ (pb/post! conn
                      {:payroll-result result
                       :journal jnl
                       :commodity eur
                       :effective-date jun-30})
          db (d/db conn)
          balance-on (fn [code]
                       (let [a (d/q '[:find ?a . :in $ ?c
                                      :where [?a :kontor.account/code ?c]]
                                    db code)
                             postings (d/q '[:find [?amt ...]
                                             :in $ ?a
                                             :where
                                             [?p :posting/account ?a]
                                             [?p :posting/amount ?amt]]
                                           db a)]
                         (reduce (fn [^java.math.BigDecimal acc
                                      ^java.math.BigDecimal x]
                                   (.add acc x))
                                 0M postings)))]
      ;; Urlaubsremuneration sum 5500
      (is (= 0 (.compareTo (bigdec "5500.00") (balance-on "6400"))))
      ;; Grundgehalt regular still 5500
      (is (= 0 (.compareTo (bigdec "5500.00") (balance-on "6000")))))))
