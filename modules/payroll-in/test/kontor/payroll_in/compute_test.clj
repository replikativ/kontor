(ns kontor.payroll-in.compute-test
  "CSV parser tests for the three IN compute providers."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.compute :as compute]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; Pay-element-code maps used across providers
;; ============================================================================

(def keka-codes
  "Keka's documented pay-element codes (a subset; consumer's full
   instance map would carry more)."
  {"BASIC"     :basic-salary
   "DA"        :dearness-allowance
   "HRA"       :house-rent-allowance
   "SPECIAL"   :special-allowance
   "BONUS"     :bonus
   "OT"        :overtime
   "TDS"       :tds
   "PF-EE"     :pf-employee
   "ESI-EE"    :esi-employee
   "PT"        :professional-tax
   "PF-ER"     {:kind :pf-employer :employer-side? true}
   "ESI-ER"    {:kind :esi-employer :employer-side? true}})

(def greythr-codes
  "GreytHR's head-code vocabulary."
  {"BASIC"     :basic-salary
   "DA"        :dearness-allowance
   "HRA"       :house-rent-allowance
   "INC-TAX"   :tds
   "EPF-EE"    :pf-employee
   "ESI-EE"    :esi-employee
   "P-TAX"     :professional-tax
   "EPF-ER"    {:kind :pf-employer :employer-side? true}
   "ESI-ER"    {:kind :esi-employer :employer-side? true}})

;; ============================================================================
;; Keka CSV parser
;; ============================================================================

(def keka-csv
  "Mock Keka GL CSV. Columns: employee-id, component, amount, type,
   work-state. Type 'Earning' / 'Deduction' / 'Employer' (the latter
   we map through the codes map's :employer-side? flag)."
  (str "employee-id,component,amount,type,work-state\n"
       "E001,BASIC,50000,Earning,IN-MH\n"
       "E001,DA,5000,Earning,IN-MH\n"
       "E001,HRA,20000,Earning,IN-MH\n"
       "E001,TDS,4000,Deduction,IN-MH\n"
       "E001,PF-EE,1800,Deduction,IN-MH\n"
       "E001,PT,200,Deduction,IN-MH\n"
       "E001,PF-ER,1800,Employer,IN-MH\n"))

(deftest keka-csv-parses-into-component-rows
  (let [parsed (compute/parse-keka-csv keka-csv
                                       {:pay-element-codes keka-codes})]
    (testing "Eight CSV rows → seven component rows (header skipped)"
      (is (= 7 (count parsed))))
    (testing "Basic salary earning row"
      (let [r (first (filter #(= :basic-salary (:kind %)) parsed))]
        (is (= "E001" (:employee-external-id r)))
        (is (= 50000M (:amount r)))
        (is (not (:employer-side? r)))))
    (testing "TDS deduction row has negative amount"
      (let [r (first (filter #(= :tds (:kind %)) parsed))]
        (is (= -4000M (:amount r)))
        (is (not (:employer-side? r)))))
    (testing "PT routes correctly + carries province"
      (let [r (first (filter #(= :professional-tax (:kind %)) parsed))]
        (is (= -200M (:amount r)))
        (is (= "IN-MH" (:province-of-employment r)))))
    (testing "Employer PF flagged"
      (let [r (first (filter #(= :pf-employer (:kind %)) parsed))]
        (is (:employer-side? r))
        (is (= 1800M (:amount r)))))))

(deftest keka-csv-throws-on-unknown-code
  (let [bad-csv (str "employee-id,component,amount,type,work-state\n"
                     "E001,UNKNOWN-CODE,100,Earning,IN-MH\n")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown IN pay-element code"
                          (compute/parse-keka-csv
                           bad-csv
                           {:pay-element-codes keka-codes})))))

;; ============================================================================
;; GreytHR CSV parser
;; ============================================================================

(def greythr-csv
  "Mock GreytHR GL CSV. Columns: emp-no, head-code, amount, head-type,
   work-state. head-type EAR/DED/EMP."
  (str "emp-no,head-code,amount,head-type,work-state\n"
       "G100,BASIC,30000,EAR,IN-KA\n"
       "G100,DA,3000,EAR,IN-KA\n"
       "G100,HRA,12000,EAR,IN-KA\n"
       "G100,INC-TAX,2500,DED,IN-KA\n"
       "G100,EPF-EE,1800,DED,IN-KA\n"
       "G100,P-TAX,200,DED,IN-KA\n"
       "G100,EPF-ER,1800,EMP,IN-KA\n"))

(deftest greythr-csv-parses-with-ear-ded-emp-vocabulary
  (let [parsed (compute/parse-greythr-csv greythr-csv
                                          {:pay-element-codes greythr-codes})]
    (is (= 7 (count parsed)))
    (testing "EAR rows get positive amount"
      (let [r (first (filter #(= :basic-salary (:kind %)) parsed))]
        (is (= 30000M (:amount r)))))
    (testing "DED rows get negative amount"
      (let [r (first (filter #(= :tds (:kind %)) parsed))]
        (is (= -2500M (:amount r)))))
    (testing "EMP rows are employer-side + positive"
      (let [r (first (filter #(= :pf-employer (:kind %)) parsed))]
        (is (:employer-side? r))
        (is (= 1800M (:amount r)))))))

;; ============================================================================
;; Generic ZenHR provider (column-mapping required)
;; ============================================================================

(def saral-csv
  "Saral PayPack-style CSV with custom column names."
  (str "EmpCode,Element,Value,Indicator,State\n"
       "S01,BASIC,40000,+,IN-TN\n"
       "S01,HRA,16000,+,IN-TN\n"
       "S01,TDS,3500,-,IN-TN\n"))

(def saral-mapping
  {:employee-id-col   "empcode"
   :pay-element-col   "element"
   :amount-col        "value"
   :sign-col          "indicator"
   :province-col      "state"})

(def saral-codes
  {"BASIC" :basic-salary
   "HRA"   :house-rent-allowance
   "TDS"   :tds})

(deftest generic-csv-parser-handles-custom-columns
  (let [parsed (compute/parse-csv saral-csv
                                  {:column-mapping saral-mapping
                                   :pay-element-codes saral-codes})]
    (is (= 3 (count parsed)))
    (is (= 40000M (:amount (first parsed))))
    (is (= -3500M (:amount (last parsed))))))

;; ============================================================================
;; Facts assembly
;; ============================================================================

(deftest facts-assembly-produces-balanced-substrate-shape
  (let [parsed (compute/parse-keka-csv keka-csv
                                       {:pay-element-codes keka-codes})
        facts (compute/csv->facts
               parsed
               {:external-id->eid {"E001" 12345}
                :commodity-eid 67
                :engine :keka})]
    (testing "One fact per employee"
      (is (= 1 (count facts))))
    (let [{:keys [employment gross net components
                  jurisdiction-specific-codes commodity]}
          (first facts)]
      (testing "Employment eid rewritten"
        (is (= 12345 employment)))
      (testing "Commodity passed through"
        (is (= 67 commodity)))
      (testing "Gross = Σ positive employee-side (50000 + 5000 + 20000 = 75000)"
        (is (= 75000M gross)))
      (testing "Net = gross + Σ negative (75000 - 4000 - 1800 - 200 = 69000)"
        (is (= 69000M net)))
      (testing "Components include both employee and employer-side"
        (is (>= (count components) 7))
        (is (some #(and (= :pf-employer (:kind %)) (:employer-side? %)) components)))
      (testing "Province surfaces in jurisdiction-specific-codes"
        (is (= "IN-MH" (:province-of-employment jurisdiction-specific-codes))))
      (testing "Engine + external-id preserved"
        (is (= :keka (:engine jurisdiction-specific-codes)))
        (is (= "E001" (:employee-external-id jurisdiction-specific-codes)))))))

(deftest unknown-external-id-fails-loud
  (let [parsed (compute/parse-keka-csv keka-csv
                                       {:pay-element-codes keka-codes})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown employee external-id"
                          (compute/csv->facts parsed
                                              {:external-id->eid {"OTHER" 1}})))))

;; ============================================================================
;; Provider record contract
;; ============================================================================

(deftest keka-provider-satisfies-protocol
  (let [provider (compute/->KekaProvider
                  {:csv-source keka-csv
                   :pay-element-codes keka-codes
                   :external-id->eid {"E001" 999}
                   :commodity-eid 1})]
    (is (= :keka (pp/provider-id provider)))
    (let [facts (pp/compute-payroll provider {})]
      (is (= 1 (count facts)))
      (is (= 999 (:employment (first facts))))
      (is (= 75000M (:gross (first facts)))))))

(deftest greythr-provider-satisfies-protocol
  (let [provider (compute/->GreytHrProvider
                  {:csv-source greythr-csv
                   :pay-element-codes greythr-codes
                   :external-id->eid {"G100" 888}
                   :commodity-eid 1})]
    (is (= :greythr (pp/provider-id provider)))
    (let [facts (pp/compute-payroll provider {})]
      (is (= 1 (count facts)))
      (is (= 888 (:employment (first facts)))))))

(deftest zenhr-provider-requires-column-mapping
  (let [bad (compute/->ZenHrProvider {:csv-source saral-csv
                                      :pay-element-codes saral-codes
                                      :external-id->eid {"S01" 1}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"needs :column-mapping"
                          (pp/compute-payroll bad {})))))
