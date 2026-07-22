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
            [kontor.payroll-at.chart :as payroll-chart]
            [kontor.payroll-at.compute :as compute]
            [kontor.payroll-at.posting-builder :as pb]
            [kontor.validation :as v]))

(def jan-31 #inst "2026-01-31T00:00:00Z")
(def jun-30 #inst "2026-06-30T00:00:00Z")

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    ;; The payroll accounts come from the module's own starter chart now.
    ;; This fixture used to hand-roll them, which is exactly the documented
    ;; workaround that put Lohnsteuer on 3500 — the l10n-at output-VAT
    ;; account — and inflated the filed UVA. Note 194 §1 P0-4.
    (payroll-chart/install! conn)
    (d/transact conn [{:kontor.journal/code "PAYROLL"
                       :kontor.journal/name "Lohn- und Gehaltsabrechnung"
                       :kontor.journal/type :general
                       :kontor.journal/active true}])
    conn))

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest jan-payroll-posts-and-balances
  (let [conn (bootstrap)
        db0 (d/db conn)
        jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
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
                               :where [?t :kontor.transaction/external-id ?ext]]
                             db "payroll-at-2026-01"))]
      (is (some? tx-id) "transaction landed with the expected external-id")
      (let [postings (d/q '[:find ?p
                            :in $ ?t
                            :where [?p :kontor.posting/transaction ?t]]
                          db tx-id)]
        (is (<= 6 (count postings))
            (str "got " (count postings) " postings"))))))

(deftest balance-by-account
  (testing "the GL transaction lands the expected per-account balances"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
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
                                             [?p :kontor.posting/account ?a]
                                             [?p :kontor.posting/amount ?amt]]
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
      ;; LSt 880 → -880. On 3540 (Verbindlichkeiten aus Steuern), NOT on
      ;; 3500, which the l10n-at chart ships as Umsatzsteuer 20 % — see
      ;; kontor.payroll-at.chart-test. Note 194 §1 P0-4.
      (is (= 0 (.compareTo (bigdec "-880.00") (balance-on "3540"))))
      (is (= 0 (.compareTo (bigdec "0") (balance-on "3500"))))
      ;; SV-Verbindl: -996.60 (AN) + -1167.65 (AG) = -2164.25, on 3600
      ;; (Verbindlichkeiten im Rahmen der sozialen Sicherheit)
      (is (= 0 (.compareTo (bigdec "-2164.25") (balance-on "3600"))))
      ;; Net pay payable: -3623.40
      (is (= 0 (.compareTo (bigdec "-3623.40") (balance-on "3700")))))))

(deftest june-payroll-with-sonderzahlung
  (testing "June period posts the 13th Sonderzahlung onto 6400"
    (let [conn (bootstrap)
          db0 (d/db conn)
          jnl (:db/id (d/entity db0 [:kontor.journal/code "PAYROLL"]))
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
                                             [?p :kontor.posting/account ?a]
                                             [?p :kontor.posting/amount ?amt]]
                                           db a)]
                         (reduce (fn [^java.math.BigDecimal acc
                                      ^java.math.BigDecimal x]
                                   (.add acc x))
                                 0M postings)))]
      ;; Urlaubsremuneration sum 5500
      (is (= 0 (.compareTo (bigdec "5500.00") (balance-on "6400"))))
      ;; Grundgehalt regular still 5500
      (is (= 0 (.compareTo (bigdec "5500.00") (balance-on "6000")))))))
