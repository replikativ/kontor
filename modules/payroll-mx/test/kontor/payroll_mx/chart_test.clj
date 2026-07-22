(ns kontor.payroll-mx.chart-test
  "The payroll-mx starter chart (ADR-119). Three things must hold: after
   install! every SAT Código Agrupador the posting + accrual builders
   reference resolves; installing twice is a no-op; and the payroll codes
   are DISJOINT from the l10n-mx base chart — the collision that made
   code-keyed resolution unsafe (`:kontor.account/code` is not
   `:db/unique`).

   Note 194 §2 PR 7."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.chart :as mx-chart]
            [kontor.payroll-mx.chart :as chart]
            [kontor.payroll-mx.core :as pmx-core]))

;; Every código the module posts to: the nine from posting-builder
;; (five 601 expenses + four 206 payables) plus 206.07 from accrual.
(def ^:private required-codigos
  ["601.01" "601.02" "601.05" "601.06" "601.84"
   "206.01" "206.04" "206.05" "206.06" "206.07"])

(deftest install-makes-every-required-account-resolve
  (let [conn (core/create-test-db)]
    (chart/install! conn)
    (let [db (d/db conn)]
      (testing "every SAT Código Agrupador the builders reference resolves"
        (doseq [code required-codigos]
          (is (some? (pmx-core/account-by-codigo-agrupador db code))
              (str "código " code " must resolve after install!"))))
      (testing "the ten accounts are ten distinct eids"
        (is (= (count required-codigos)
               (count (into #{} (map #(pmx-core/account-by-codigo-agrupador db %))
                            required-codigos))))))))

(deftest install-is-idempotent
  (let [conn (core/create-test-db)]
    (chart/install! conn)
    (chart/install! conn)
    (let [db (d/db conn)]
      (testing "re-installing does not duplicate — path is :db.unique/identity"
        (doseq [code required-codigos]
          (is (= 1 (count (d/q '[:find [?e ...] :in $ ?c :where
                                 [?e :kontor.account/code ?c]]
                               db code)))
              (str "exactly one account for código " code)))))))

(deftest payroll-mx-codes-are-disjoint-from-l10n-mx
  ;; This is the guard the whole fix rests on. Because :kontor.account/code
  ;; is not :db/unique, if the payroll starter and the l10n-mx base ever
  ;; shipped the same code, exact-match resolution would return an
  ;; arbitrary one — a silent mis-post. Install BOTH and prove no code is
  ;; shared. The base ships 601.05.001 / 601.06.001 (three-level); the
  ;; payroll starter ships bare 601.05 / 601.06 — deliberately disjoint.
  (let [base-codes    (into #{} (map :code) (mx-chart/load-chart))
        payroll-codes (into #{} (map :code) (chart/load-starter))
        overlap       (set/intersection base-codes payroll-codes)]
    (is (empty? overlap)
        (str "payroll-mx and l10n-mx must not share an account code; shared: " overlap))
    (testing "and the pair install without either clobbering the other"
      (let [conn (core/create-test-db)]
        (mx-chart/install! conn)
        (chart/install! conn)
        (let [db (d/db conn)]
          ;; the payroll IMSS-patronal account resolves to the payroll
          ;; account, not the base 601.05.001 Telecomunicaciones
          (is (= "Gastos:IMSS-Patron"
                 (:kontor.account/path
                  (d/pull db [:kontor.account/path]
                          (pmx-core/account-by-codigo-agrupador db "601.05")))))
          (is (some? (pmx-core/account-by-codigo-agrupador db "601.05.001"))
              "the base Telecomunicaciones account still resolves under its own code"))))))
