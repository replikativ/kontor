(ns kontor.payroll-us-adp.posting-builder-test
  "Stage R C3 — UsPayrollPostingBuilder tests (ADR-077).

   Covers:
     - Wage-type → GL-account routing (consumer-supplied
       :accounts map).
     - Multi-state allocation: per-row :state → analytic-distribution
       on :kontor.posting/analytic-distributions, NOT :kontor.posting/entity
       (note 83 §4).
     - Parallel-ledger split: each component routes to all
       declared :ledgers (book + tax, or book-only for accruals).
     - Per-(ledger, commodity) sum-to-zero invariant holds across
       the produced posting set (ADR-021 + ADR-077).
     - Missing :accounts entry throws — kontor never silently
       drops a posting."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-us-adp.compute :as compute]
            [kontor.payroll-us-adp.core :as adp]
            [kontor.payroll-us-adp.posting-builder :as pb]
            [kontor.payroll-us-adp.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def book-ledger-eid -10001)
(def tax-ledger-eid -10002)
(def usd-eid -10003)

(def basic-accounts
  "Hand-rolled accounts map. Real consumers supply lookup-refs or
   actual eids."
  {:wages-expense       -20001
   :er-fica-ss          -20002
   :er-fica-medicare    -20003
   :er-futa             -20004
   :er-suta             -20005
   :er-health           -20006
   :er-401k-match       -20007
   :er-workers-comp     -20008
   :ee-fed-withheld     -20009
   :ee-state-withheld   -20010
   :ee-local-withheld   -20011
   :ee-fica-ss          -20012
   :ee-fica-medicare    -20013
   :ee-401k-deferral    -20014
   :ee-roth-deferral    -20015
   :ee-section125       -20016
   :ee-hsa              -20017
   :ee-fsa              -20018
   :ee-dep-care-fsa     -20019
   :garnishment         -20020
   :child-support       -20021
   :net-pay-payable     -20022
   :unmapped-suspense   -20099})

(defn- compute-3-state-facts []
  (let [wtm (wt/load-reference)
        {:keys [classified]}
        (compute/parse-and-classify
         (io/resource "kontor/payroll_us_adp/fixtures/gli-3-employees-3-states.csv")
         wtm)]
    (compute/payroll-facts-from-rows classified)))

;; ============================================================================
;; Routing
;; ============================================================================

(deftest builder-routes-components-to-consumer-accounts
  (let [facts (compute-3-state-facts)
        postings (pb/build-payroll-postings
                  {:facts facts
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid
                                 :us-tax  tax-ledger-eid}
                   :commodity usd-eid})]
    (testing "every posting has account + amount + commodity + ledger"
      (doseq [p postings]
        (is (some? (:kontor.posting/account p)) (str "missing account on " p))
        (is (some? (:kontor.posting/amount p)) (str "missing amount on " p))
        (is (some? (:kontor.posting/commodity p)) (str "missing commodity on " p))
        (is (some? (:kontor.posting/ledger p)) (str "missing ledger on " p))))
    (testing "wage-expense postings route to :wages-expense account"
      (let [wage-postings (filter #(= (:wages-expense basic-accounts)
                                      (:kontor.posting/account %)) postings)]
        ;; 3 employees × 2 ledgers (us-gaap + us-tax) = 6.
        (is (= 6 (count wage-postings)))))
    (testing "ee-state-withheld routes to :ee-state-withheld account"
      (let [state-postings (filter #(= (:ee-state-withheld basic-accounts)
                                       (:kontor.posting/account %)) postings)]
        ;; E101 (CA) + E102 (NY) emit a state-withholding line each on
        ;; both ledgers; E103 (TX, no state tax) does not. 2 × 2 = 4.
        (is (= 4 (count state-postings)))))))

(deftest builder-throws-on-missing-account-mapping
  (let [facts (compute-3-state-facts)]
    (testing "missing :wages-expense key explodes — no silent drop"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"no :accounts entry"
           (pb/build-payroll-postings
            {:facts facts
             :accounts (dissoc basic-accounts :wages-expense)
             :ledgers-map {:us-gaap book-ledger-eid}
             :commodity usd-eid}))))))

;; ============================================================================
;; Multi-state allocation (note 83 §4)
;; ============================================================================

(deftest multi-state-allocation-rides-on-analytic-distribution
  (let [facts (compute-3-state-facts)
        postings (pb/build-payroll-postings
                  {:facts facts
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid}
                   :commodity usd-eid})]
    (testing "no posting carries :kontor.posting/entity (single legal entity)"
      ;; Per note 83 §4: kontor uses :kontor.analytic-account/state, NOT
      ;; :kontor.posting/entity. The substrate-level :kontor.posting/entity is
      ;; reserved for multi-LLC / cross-border / PEO secondment.
      (doseq [p postings]
        (is (nil? (:kontor.posting/entity p))
            (str "Posting unexpectedly carries :kontor.posting/entity: " p))))
    (testing "every wage-side posting carries a :state analytic distribution"
      (let [wage-side (filter #(some? (:kontor.posting/analytic-distributions %))
                              postings)]
        (is (seq wage-side))
        (doseq [p wage-side
                d (:kontor.posting/analytic-distributions p)]
          (is (= [:kontor.analytic-plan/code "state"]
                 (:kontor.analytic-distribution/plan d)))
          (is (= 100M (:kontor.analytic-distribution/percent d)))
          (is (some? (:kontor.analytic-distribution/account d))))))
    (testing "three distinct states appear in the distributions"
      (let [states (->> postings
                        (mapcat :kontor.posting/analytic-distributions)
                        (map :kontor.analytic-distribution/account)
                        (map second) ; the path string
                        distinct
                        set)]
        (is (= #{"state:CA" "state:NY" "state:TX"} states))))))

(deftest multi-state-allocation-supports-hybrid-employees
  ;; Demonstrate the consumer-driven hybrid override: the same fact
  ;; (single 'CA' state on the row) gets split across CA 60% / CO 40%
  ;; by passing :state-allocations into the builder.
  (let [facts (compute-3-state-facts)
        ;; E101 hybrid: 60% CA, 40% CO.
        per-emp-allocs {"E101" {:CA 60M :CO 40M}}
        postings (pb/build-payroll-postings
                  {:facts facts
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid}
                   :commodity usd-eid
                   :state-allocations per-emp-allocs})]
    (testing "hybrid distribution: each component carries two distribution rows"
      ;; Pick any wage-expense posting for E101: should have 2 dists
      ;; (CA 60, CO 40).
      (let [wage-e101 (some (fn [p]
                              (when (and (= (:wages-expense basic-accounts)
                                            (:kontor.posting/account p))
                                         (let [dists (:kontor.posting/analytic-distributions p)]
                                           (and (= 2 (count dists))
                                                (some #(= "state:CA"
                                                          (second (:kontor.analytic-distribution/account %)))
                                                      dists)
                                                (some #(= "state:CO"
                                                          (second (:kontor.analytic-distribution/account %)))
                                                      dists))))
                                p))
                            postings)]
        (is (some? wage-e101))
        (when wage-e101
          (let [dists (:kontor.posting/analytic-distributions wage-e101)
                pcts (sort (map :kontor.analytic-distribution/percent dists))]
            (is (= [40M 60M] pcts))))))))

;; ============================================================================
;; Parallel-ledger split (ADR-021)
;; ============================================================================

(deftest parallel-ledger-split-emits-one-posting-per-ledger
  (let [facts (compute-3-state-facts)
        postings (pb/build-payroll-postings
                  {:facts facts
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid
                                 :us-tax  tax-ledger-eid}
                   :commodity usd-eid})
        gaap (filter #(= book-ledger-eid (:kontor.posting/ledger %)) postings)
        tax  (filter #(= tax-ledger-eid  (:kontor.posting/ledger %)) postings)]
    (testing "every ledger receives postings"
      (is (seq gaap))
      (is (seq tax)))
    (testing "the two ledgers have the same count (every component goes to both)"
      (is (= (count gaap) (count tax))))))

(deftest per-ledger-sum-to-zero-holds
  ;; ADR-021 + ADR-031 + ADR-077: substrate sum-to-zero invariant runs
  ;; per (entity, ledger, commodity). For this run we don't tag
  ;; :kontor.posting/entity so it's per (ledger, commodity). The posting set
  ;; must balance on EACH ledger independently.
  (let [facts (compute-3-state-facts)
        postings (pb/build-payroll-postings
                  {:facts facts
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid
                                 :us-tax  tax-ledger-eid}
                   :commodity usd-eid})
        sum-by-ledger (reduce (fn [acc {:kontor.posting/keys [ledger amount]}]
                                (update acc ledger
                                        (fn [^BigDecimal a] (.add (or a 0M) ^BigDecimal amount))))
                              {} postings)]
    (testing ":us-gaap ledger balances"
      (is (zero? (.signum ^BigDecimal (get sum-by-ledger book-ledger-eid 0M)))))
    (testing ":us-tax ledger balances"
      (is (zero? (.signum ^BigDecimal (get sum-by-ledger tax-ledger-eid 0M)))))))

(deftest book-only-component-stays-on-us-gaap
  ;; The wage-type map declares :er-401k-match with :ledgers #{:us-gaap}.
  ;; Even when both ledgers are configured, this component must
  ;; appear ONLY on :us-gaap.
  (let [;; Synthesize one fact with an ER 401K MATCH component.
        ;; (Our 3-state fixture doesn't carry one; we hand-roll here.)
        fact {:employment "EX1"
              :gross 5000M :net 4000M
              :components [{:role :wage-expense
                            :account-key :wages-expense
                            :ledgers #{:us-gaap :us-tax}
                            :amount 5000M
                            :employer-side? false :state "CA"
                            :gl-account "5010"}
                           {:role :ee-fed-withheld
                            :account-key :ee-fed-withheld
                            :ledgers #{:us-gaap :us-tax}
                            :amount -1000M
                            :employer-side? false :state "CA"
                            :gl-account "2110"}
                           {:role :er-401k-match
                            :account-key :er-401k-match
                            :ledgers #{:us-gaap}
                            :amount 500M
                            :employer-side? true :state "CA"
                            :gl-account "5310"}]}
        postings (pb/build-payroll-postings
                  {:facts [fact]
                   :accounts basic-accounts
                   :ledgers-map {:us-gaap book-ledger-eid
                                 :us-tax  tax-ledger-eid}
                   :commodity usd-eid})
        match-postings (filter #(= (:er-401k-match basic-accounts)
                                   (:kontor.posting/account %)) postings)]
    (testing "ER 401K MATCH appears on :us-gaap"
      (is (some #(= book-ledger-eid (:kontor.posting/ledger %)) match-postings)))
    (testing "ER 401K MATCH does NOT appear on :us-tax"
      (is (not-any? #(= tax-ledger-eid (:kontor.posting/ledger %)) match-postings)))))

;; ============================================================================
;; UsPayrollPostingBuilder protocol record
;; ============================================================================

(deftest record-implements-payroll-posting-builder-protocol
  (let [builder (adp/make-us-payroll-posting-builder {:commodity usd-eid})
        facts (compute-3-state-facts)
        postings (pp/build-postings builder facts
                                    {:accounts basic-accounts
                                     :ledgers-map {:us-gaap book-ledger-eid}})]
    (testing "satisfies the protocol"
      (is (satisfies? pp/PayrollPostingBuilder builder)))
    (testing "produces a non-empty vector of posting maps"
      (is (seq postings))
      (is (every? map? postings)))))
