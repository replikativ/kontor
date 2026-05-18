(ns kontor.payroll-us-adp.compute-test
  "Stage R C3 — AdpGliComputeProvider unit tests (ADR-077).

   Covers:
     - CSV parser round-trip on the canonical 10-column fixture.
     - The balancing-row trap (note 83 §2.3): blank-:gl-account row
       skipped from postings but counted toward sum-to-zero.
     - File-balance invariant: corrupt / truncated files rejected.
     - Wage-type matching with regex capture groups (state from
       'CA TAX' description, state from 'CA SUI' description).
     - PayrollFacts assembly from classified rows (per-employee
       grouping, gross/net derivation matches `kontor.hr.payroll/check-facts`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.hr.payroll :as hrp]
            [kontor.payroll-us-adp.compute :as compute]
            [kontor.payroll-us-adp.wage-types :as wt]))

(defn- single-state-csv []
  (io/resource "kontor/payroll_us_adp/fixtures/gli-single-state-tx.csv"))

(defn- three-state-csv []
  (io/resource "kontor/payroll_us_adp/fixtures/gli-3-employees-3-states.csv"))

(defn- corrupt-csv []
  (io/resource "kontor/payroll_us_adp/fixtures/gli-corrupt-unbalanced.csv"))

(deftest reference-wage-type-map-loads
  (let [wtm (wt/load-reference)]
    (testing "vendor + format markers present"
      (is (= :adp (:vendor wtm)))
      (is (seq (:description-rules wtm)))
      (is (seq (:csv-format wtm)))
      (is (= false (get-in wtm [:csv-format :has-header]))))
    (testing "regex strings are compiled to Patterns"
      (let [first-rule (first (:description-rules wtm))]
        (is (instance? java.util.regex.Pattern (:match first-rule)))))
    (testing "validate returns nil (clean)"
      (is (nil? (wt/validate wtm))))))

(deftest parse-gli-balances-on-canonical-fixture
  (let [wtm (wt/load-reference)
        result (compute/parse-gli (single-state-csv) wtm)]
    (testing "the file is balanced"
      (is (:file-balanced? result))
      (is (zero? (.signum ^java.math.BigDecimal (:sum-of-amounts result)))))
    (testing "the balancing-row is recognized + separated from real rows"
      (is (= 1 (count (:balancing-rows result))))
      (is (= 9 (count (:rows result))))
      (is (every? (fn [r] (str/blank? (:gl-account r)))
                  (:balancing-rows result))))
    (testing "real rows carry the wage-type description text"
      (is (some (fn [r] (= "GROSS" (:description r))) (:rows result)))
      (is (some (fn [r] (= "EE FEDERAL TAX" (:description r)))
                (:rows result))))))

(deftest parse-gli-rejects-corrupt-file
  (let [wtm (wt/load-reference)]
    (testing "non-zero sum-of-amounts throws on parse"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not sum to zero"
                            (compute/parse-gli (corrupt-csv) wtm))))))

(deftest parse-amount-parses-negative-credits
  (testing "negative = credit per ADP convention"
    (is (= -1500.00M (compute/parse-amount "-1500.00")))
    (is (= 8500.00M (compute/parse-amount "8500.00")))
    (is (= 0M (compute/parse-amount "")))
    (is (= 0M (compute/parse-amount "  ")))))

(deftest match-rule-extracts-state-from-regex-capture
  (let [wtm (wt/load-reference)
        rules (:description-rules wtm)]
    (testing "CA TAX → :ee-state-withheld with state capture"
      (let [m (compute/match-rule rules "CA TAX")]
        (is (= :ee-state-withheld (:role m)))
        (is (= ["CA"] (:capture m)))
        (is (= 1 (:state-from-group m)))))
    (testing "CA SUI → :er-suta with state capture"
      (let [m (compute/match-rule rules "CA SUI")]
        (is (= :er-suta (:role m)))
        (is (= ["CA"] (:capture m)))))
    (testing "literal NET PAY matches"
      (is (= :net-pay-liability (:role (compute/match-rule rules "NET PAY")))))
    (testing "unknown vocab falls to the :unmapped catch-all"
      (let [m (compute/match-rule rules "WEIRD-NEW-WAGE-TYPE")]
        (is (= :unmapped (:role m)))
        (is (true? (:flag-for-review? m)))))))

(deftest classify-row-resolves-state-from-capture-or-reference-3
  (let [wtm (wt/load-reference)
        {:keys [classified]} (compute/parse-and-classify (single-state-csv) wtm)]
    (testing "every classified row has a :rule attached"
      (is (every? :rule classified)))
    (testing "rows with 'CA TAX'-style descriptions get state from capture"
      ;; single-state-tx.csv has TX SUI; capture group → 'TX'.
      (let [tx-sui (first (filter #(= "TX SUI" (:description %)) classified))]
        (is (= "TX" (:state tx-sui)))))
    (testing "rows without state-capture fall back to reference-3 column"
      (let [net-pay (first (filter #(= "NET PAY" (:description %)) classified))]
        (is (= "TX" (:state net-pay)))))))

(deftest payroll-facts-from-rows-aggregates-by-employee
  (let [wtm (wt/load-reference)
        {:keys [classified]} (compute/parse-and-classify (three-state-csv) wtm)
        facts (compute/payroll-facts-from-rows classified)]
    (testing "one fact per distinct employee-id"
      (is (= 3 (count facts)))
      (is (= #{"E101" "E102" "E103"}
             (set (map :employment facts)))))
    (testing "gross matches the GROSS-row amount"
      (is (= 8500.00M (:gross (first (filter #(= "E101" (:employment %)) facts)))))
      (is (= 9200.00M (:gross (first (filter #(= "E102" (:employment %)) facts)))))
      (is (= 7800.00M (:gross (first (filter #(= "E103" (:employment %)) facts))))))
    (testing "net matches the NET PAY-row amount (per pay-stub)"
      (is (= 5669.75M (:net (first (filter #(= "E101" (:employment %)) facts)))))
      (is (= 6221.20M (:net (first (filter #(= "E102" (:employment %)) facts)))))
      (is (= 5903.30M (:net (first (filter #(= "E103" (:employment %)) facts))))))
    (testing "kontor.hr.payroll/check-facts accepts every fact"
      (doseq [f facts] (is (= f (hrp/check-facts f)))))))

(deftest balancing-row-trap-does-not-become-a-posting
  ;; Note 83 §2.3 / §9.4 gotcha #1: the balancing row (blank
  ;; :gl-account, no description) MUST NOT be transacted. The parser
  ;; routes it to :balancing-rows; payroll-facts-from-rows runs only
  ;; over real rows.
  (let [wtm (wt/load-reference)
        {:keys [classified balancing-rows]}
        (compute/parse-and-classify (three-state-csv) wtm)
        facts (compute/payroll-facts-from-rows classified)]
    (testing "the balancing row is captured separately"
      (is (= 1 (count balancing-rows))))
    (testing "no PayrollFacts component carries a blank gl-account"
      (doseq [f facts
              c (:components f)]
        (is (not (str/blank? (or (:gl-account c) "")))
            (str "Component carries blank GL account — likely the
                 balancing-row trap. Component: " (pr-str c)))))))

(deftest components-carry-state-cost-center-and-w2-flags
  (let [wtm (wt/load-reference)
        {:keys [classified]} (compute/parse-and-classify (three-state-csv) wtm)
        facts (compute/payroll-facts-from-rows classified)
        e101 (first (filter #(= "E101" (:employment %)) facts))
        ee-fed (first (filter (comp #{:ee-fed-withheld} :role) (:components e101)))
        ee-state-w (first (filter (comp #{:ee-state-withheld} :role)
                                  (:components e101)))]
    (testing "EE FEDERAL TAX carries w2-box '2'"
      (is (= "2" (:w2-box ee-fed))))
    (testing "EE state withheld carries the state from capture group + box 17"
      (is (= "CA" (:state ee-state-w)))
      (is (= "17" (:w2-box ee-state-w))))
    (testing "cost-center pulled from reference-2"
      (is (= "ENG" (:cost-center ee-fed))))))
