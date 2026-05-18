(ns kontor.payroll-au.compute-test
  "Stage R C6 — AU compute-provider unit tests (ADR-080).

   Covers:
     - Xero GL CSV parser on the canonical 3-employee fixture.
     - MYOB GL CSV parser with custom column-mapping headers.
     - Per-employee net-zero invariant rejection on a corrupt file.
     - Skip-payable mirror rows dropped from output components.
     - PayrollFacts assembly (gross / net / employer-side split)
       matches `kontor.hr.payroll/check-facts`.
     - The Reckon One skeleton throws clearly.
     - `:csv-source` canonical key (P2-86-4 — recommended over
       legacy US `:adp-gli-csv-source`)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.hr.payroll :as hrp]
            [kontor.payroll-au.compute :as compute]
            [kontor.payroll-provider :as pp]))

(defn- xero-csv [] (io/resource "kontor/payroll_au/fixtures/xero_3_employees_3_states.csv"))
(defn- myob-csv [] (io/resource "kontor/payroll_au/fixtures/myob_single_employee.csv"))
(defn- corrupt-csv [] (io/resource "kontor/payroll_au/fixtures/xero_corrupt_unbalanced.csv"))

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

;; ============================================================================
;; Xero parser
;; ============================================================================

(deftest xero-parser-round-trips-canonical-fixture
  (let [parsed (compute/parse-au-gl-csv
                (xero-csv)
                {:pay-element-codes pay-element-codes})]
    (testing "every parsed component carries the required keys"
      (is (every? :employee-external-id parsed))
      (is (every? :kind parsed))
      (is (every? #(contains? % :amount) parsed)))
    (testing "skip-payable mirror rows are dropped (NET + SUPER-ER-PAY)"
      (let [kinds (set (map :kind parsed))]
        (is (not (contains? kinds :__skip-payable)))))
    (testing "all three employees represented"
      (is (= #{"E101" "E102" "E103"}
             (set (map :employee-external-id parsed)))))))

(deftest xero-parser-rejects-corrupt-file
  (testing "per-employee non-zero sum throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"per-employee sum != 0"
                          (compute/parse-au-gl-csv
                           (corrupt-csv)
                           {:pay-element-codes pay-element-codes})))))

(deftest parser-requires-pay-element-codes
  (testing "missing :pay-element-codes throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pay-element-codes"
                          (compute/parse-au-gl-csv (xero-csv) {})))))

(deftest parser-rejects-unknown-pay-element-code
  (testing "an unmapped pay-element-code throws — no silent drop"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown pay-element code"
                          (compute/parse-au-gl-csv
                           (xero-csv)
                           ;; Missing OTE mapping → throw on the first OTE row.
                           {:pay-element-codes (dissoc pay-element-codes "OTE")})))))

;; ============================================================================
;; PayrollFacts assembly
;; ============================================================================

(deftest xero-facts-assembly-matches-substrate-invariant
  (let [parsed (compute/parse-au-gl-csv
                (xero-csv)
                {:pay-element-codes pay-element-codes})
        facts (compute/au-gl-facts
               parsed
               {:external-id->eid {"E101" 1001 "E102" 1002 "E103" 1003}
                :pay-period-eid 9001
                :commodity-eid 7001
                :engine-tag :xero})]
    (testing "one fact per distinct employee"
      (is (= 3 (count facts)))
      (is (= #{1001 1002 1003} (set (map :employment facts)))))
    (testing "E101 gross + net match the canonical math"
      (let [f (first (filter #(= 1001 (:employment %)) facts))]
        ;; gross = 6500 OTE; deductions = -1200 PAYGW + -650 SS = -1850
        ;; net = 6500 - 1850 = 4650
        (is (= 6500.00M (:gross f)))
        (is (= 4650.00M (:net f)))))
    (testing "E102 includes overtime in gross"
      (let [f (first (filter #(= 1002 (:employment %)) facts))]
        ;; gross = 7200 OTE + 300 OVT = 7500; net = 7500 - 1600 = 5900
        (is (= 7500.00M (:gross f)))
        (is (= 5900.00M (:net f)))))
    (testing "E103 includes bonus in gross"
      (let [f (first (filter #(= 1003 (:employment %)) facts))]
        ;; gross = 5800 + 500 = 6300; net = 6300 - 1100 = 5200
        (is (= 6300.00M (:gross f)))
        (is (= 5200.00M (:net f)))))
    (testing "every fact passes substrate check-facts"
      (doseq [f facts]
        (is (= f (hrp/check-facts f)))))
    (testing "employer-side SG is preserved as a component (employer-side? true)"
      (doseq [f facts]
        (let [er-sg (first (filter #(and (= :superannuation-guarantee-employer (:kind %))
                                         (:employer-side? %))
                                   (:components f)))]
          (is (some? er-sg)
              (str "Expected employer-SG component on fact " (:employment f))))))
    (testing "jurisdiction-specific-codes carry the engine + state"
      (let [f (first (filter #(= 1001 (:employment %)) facts))]
        (is (= :xero (-> f :jurisdiction-specific-codes :engine)))
        (is (= "NSW"  (-> f :jurisdiction-specific-codes :state)))))))

;; ============================================================================
;; XeroGlComputeProvider — protocol record
;; ============================================================================

(deftest xero-compute-provider-protocol
  (let [provider (compute/->XeroGlComputeProvider {})
        ctx {:pay-period-eid 9001
             :entity-eid 7777
             :employment-eids [1001 1002 1003]
             :variable-inputs nil}]
    (testing "provider-id is :xero-gl"
      (is (= :xero-gl (pp/provider-id provider))))
    (testing "satisfies the protocol"
      (is (satisfies? pp/PayrollComputeProvider provider)))
    (testing "throws clearly on missing :csv-source"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":csv-source"
                            (pp/compute-payroll provider ctx))))
    (testing "happy path via ctx overrides"
      (let [ctx' (assoc ctx
                        :csv-source (xero-csv)
                        :pay-element-codes pay-element-codes
                        :external-id->eid {"E101" 1001
                                           "E102" 1002
                                           "E103" 1003})
            facts (pp/compute-payroll provider ctx')]
        (is (= 3 (count facts)))))))

;; ============================================================================
;; MYOB parser — custom column-mapping
;; ============================================================================

(deftest myob-parser-with-custom-column-mapping
  (let [mapping {:employee-id-col "card-id"
                 :pay-element-col "wage-category"
                 :debit-col "dr-amount"
                 :credit-col "cr-amount"
                 :state-col "state-of-employment"}
        parsed (compute/parse-au-gl-csv
                (myob-csv)
                {:pay-element-codes pay-element-codes
                 :column-mapping mapping})
        facts (compute/au-gl-facts
               parsed
               {:external-id->eid {"EMP-007" 5001}
                :commodity-eid 7001
                :engine-tag :myob})]
    (testing "MYOB single-employee parses + derives gross / net"
      (is (= 1 (count facts)))
      (let [f (first facts)]
        (is (= 5000.00M (:gross f)))
        (is (= 4100.00M (:net f)))
        (is (= "VIC" (-> f :jurisdiction-specific-codes :state)))))))

(deftest myob-compute-provider-id
  (let [provider (compute/->MyobGlComputeProvider {})]
    (testing "provider-id is :myob-gl"
      (is (= :myob-gl (pp/provider-id provider))))))

;; ============================================================================
;; Reckon One skeleton
;; ============================================================================

(deftest reckon-skeleton-throws-loud
  (let [provider (compute/->ReckonOneComputeProvider {})]
    (testing "compute-payroll throws with a clear skeleton message"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"skeleton"
                            (pp/compute-payroll provider {}))))))
