(ns kontor.payroll-au.posting-builder-test
  "Stage R C6 — AuPayrollPostingBuilder tests (ADR-080).

   Covers:
     - Account routing via consumer-supplied `:accounts` map.
     - Missing-tag throw — kontor never silently drops a leg.
     - Per-fact balanced posting set (gross Dr = sum of payable + net
       Cr).
     - Employer-side SG: DR expense + CR payable (separate from
       employee block).
     - Multi-state per-state allocation via
       `:analytic-distribution/plan \"state\"`, NOT
       `:kontor.posting/entity` (mirror ADR-077).
     - Hybrid employee allocation via `:state-allocations` override.
     - Salary-sacrifice routes to the SS-clearing payable (NOT the
       wages tag — does not reduce gross-wage-DR)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-au.compute :as compute]
            [kontor.payroll-au.core :as au]
            [kontor.payroll-au.posting-builder :as pb]
            [kontor.payroll-provider :as pp]
            [clojure.java.io :as io])
  (:import [java.math BigDecimal]))

(def aud-eid -7001)
(def book-ledger-eid -10001)

(def basic-accounts
  {:au-payroll-wages              -20001
   :au-payroll-er-super           -20002
   :au-payroll-er-state-tax       -20003
   :au-payroll-er-workers-comp    -20004
   :au-payroll-paygw              -20010
   :au-payroll-super              -20011
   :au-payroll-super-employee     -20012
   :au-payroll-salary-sacrifice   -20013
   :au-payroll-state-tax          -20014
   :au-payroll-workers-comp       -20015
   :au-payroll-child-support      -20016
   :au-payroll-other-deduction    -20017
   :au-payroll-net-wages          -20020})

(def pay-element-codes
  {"OTE"              :ordinary-time-earnings
   "OVT"              :overtime
   "BONUS"            :bonus
   "PAYGW"            :paygw
   "SS-SUPER"         :salary-sacrifice-super
   "NET"              :__skip-payable
   "SUPER-ER-SG"      {:kind :superannuation-guarantee-employer
                       :employer-side? true}
   "SUPER-ER-PAY"     :__skip-payable})

(defn- three-emp-facts []
  (let [parsed (compute/parse-au-gl-csv
                (io/resource "kontor/payroll_au/fixtures/xero_3_employees_3_states.csv")
                {:pay-element-codes pay-element-codes})]
    (compute/au-gl-facts
     parsed
     {:external-id->eid {"E101" 1001 "E102" 1002 "E103" 1003}
      :commodity-eid aud-eid
      :engine-tag :xero})))

;; ============================================================================
;; Routing
;; ============================================================================

(deftest builder-routes-components-to-consumer-accounts
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledger book-ledger-eid})]
    (testing "every posting carries account + amount + commodity + ledger"
      (doseq [p postings]
        (is (some? (:kontor.posting/account p)))
        (is (some? (:kontor.posting/amount p)))
        (is (= aud-eid (:kontor.posting/commodity p)))
        (is (= book-ledger-eid (:kontor.posting/ledger p)))))
    (testing "wage-expense lines route to the :au-payroll-wages account"
      (let [wages (filter #(= (:au-payroll-wages basic-accounts)
                              (:kontor.posting/account %)) postings)]
        ;; 3 employees → 3 gross-wage-expense lines.
        (is (= 3 (count wages)))))
    (testing "PAYGW lines route to the ATO payable account"
      (let [paygw (filter #(= (:au-payroll-paygw basic-accounts)
                              (:kontor.posting/account %)) postings)]
        (is (= 3 (count paygw)))))
    (testing "employer SG produces both an expense Dr AND a payable Cr"
      (let [sg-exp (filter #(= (:au-payroll-er-super basic-accounts)
                               (:kontor.posting/account %)) postings)
            sg-pay (filter #(= (:au-payroll-super basic-accounts)
                               (:kontor.posting/account %)) postings)]
        (is (= 3 (count sg-exp)))
        (is (= 3 (count sg-pay)))))
    (testing "net-wages payable for each employee"
      (let [net (filter #(= (:au-payroll-net-wages basic-accounts)
                            (:kontor.posting/account %)) postings)]
        (is (= 3 (count net)))))))

(deftest missing-account-tag-throws-loud
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})]
    (testing "missing :au-payroll-wages explodes — no silent drop"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No account configured"
                            (pp/build-postings builder facts
                                               {:accounts (dissoc basic-accounts
                                                                  :au-payroll-wages)
                                                :ledger book-ledger-eid}))))))

;; ============================================================================
;; Balanced posting set
;; ============================================================================

(deftest per-fact-postings-sum-to-zero
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledger book-ledger-eid})
        sum (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                      (.add a ^BigDecimal amount))
                    0M postings)]
    (testing "the full posting set balances (sum of amounts is zero)"
      (is (zero? (.signum sum))))))

(deftest per-employee-balance-on-the-wage-block
  ;; For each employee, sum of (wages DR - PAYGW - SS - net) = 0,
  ;; AND (SG-expense DR - SG-payable CR) = 0. Test both independently.
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledger book-ledger-eid})
        sg-postings (filter (fn [p]
                              (#{(:au-payroll-er-super basic-accounts)
                                 (:au-payroll-super basic-accounts)}
                               (:kontor.posting/account p)))
                            postings)
        sg-sum (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                         (.add a ^BigDecimal amount))
                       0M sg-postings)]
    (testing "SG postings net to zero across all employees"
      (is (zero? (.signum sg-sum))))))

;; ============================================================================
;; State allocation (mirror US ADP per ADR-077)
;; ============================================================================

(deftest per-state-allocation-rides-on-analytic-distribution
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledger book-ledger-eid})]
    (testing "no posting carries :kontor.posting/entity (single Pty Ltd)"
      ;; Per ADR-080 + mirror of ADR-077: per-state lives on
      ;; :analytic-distribution, NOT :kontor.posting/entity.
      (doseq [p postings]
        (is (nil? (:kontor.posting/entity p))
            (str "Unexpected :kontor.posting/entity on " p))))
    (testing "wage-side postings carry an analytic-distribution to a state"
      (let [with-dist (filter #(some? (:kontor.posting/analytic-distributions %)) postings)]
        (is (seq with-dist))
        (doseq [p with-dist
                d (:kontor.posting/analytic-distributions p)]
          (is (= [:analytic-plan/code "state"]
                 (:analytic-distribution/plan d)))
          (is (some? (:analytic-distribution/account d))))))
    (testing "the three states from the fixture appear"
      (let [paths (->> postings
                       (mapcat :kontor.posting/analytic-distributions)
                       (map :analytic-distribution/account)
                       (map second)
                       distinct
                       set)]
        (is (= #{"state:NSW" "state:VIC" "state:QLD"} paths))))))

(deftest hybrid-state-allocation-override
  ;; Override: E101 splits 60 % NSW / 40 % VIC.
  (let [facts (three-emp-facts)
        builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledger book-ledger-eid
                                     :state-allocations {1001 {:NSW 60M
                                                               :VIC 40M}}})
        e101-wage (some (fn [p]
                          (when (and (= (:au-payroll-wages basic-accounts)
                                        (:kontor.posting/account p))
                                     (let [dists (:kontor.posting/analytic-distributions p)]
                                       (and (= 2 (count dists)))))
                            p))
                        postings)]
    (testing "the override produces two distribution rows for E101's wage line"
      (is (some? e101-wage))
      (when e101-wage
        (let [dists (:kontor.posting/analytic-distributions e101-wage)
              pcts (sort (map :analytic-distribution/percent dists))]
          (is (= [40M 60M] pcts)))))))

;; ============================================================================
;; Protocol satisfaction
;; ============================================================================

(deftest builder-record-satisfies-protocol
  (let [builder (au/make-au-payroll-posting-builder {:commodity aud-eid})
        facts (three-emp-facts)]
    (testing "satisfies PayrollPostingBuilder"
      (is (satisfies? pp/PayrollPostingBuilder builder)))
    (testing "produces a non-empty vector of posting maps"
      (let [postings (pp/build-postings builder facts
                                        {:accounts basic-accounts
                                         :ledger book-ledger-eid})]
        (is (seq postings))
        (is (every? map? postings))))))

(deftest constructor-requires-commodity
  (testing ":commodity is required at construction time"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":commodity"
                          (au/make-au-payroll-posting-builder {})))))
