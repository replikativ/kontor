(ns kontor.payroll-cn.compute-test
  "Unit tests for the CN payroll CSV compute providers (ADR-085 /
)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.compute :as compute]
            [kontor.provider.payroll-provider :as pp]))

(def yonyou-pay-element-codes
  "Yonyou wage-element labels (in zh-cn) → kontor :component-kind.
   Per
   wage-types/standard-component-kinds."
  {"基本工资"         :base-wage
   "绩效工资"         :performance-bonus
   "加班费"           :overtime
   "年终奖"           :annual-bonus
   "补贴"             :allowance
   "个人所得税"       :iit-withheld
   "养老保险-个人"    :ee-pension
   "医疗保险-个人"    :ee-medical
   "失业保险-个人"    :ee-unemployment
   "住房公积金-个人"  :ee-housing-fund
   "养老保险-单位"    :er-pension
   "医疗保险-单位"    :er-medical
   "失业保险-单位"    :er-unemployment
   "工伤保险-单位"    :er-work-injury
   "生育保险-单位"    :er-maternity
   "住房公积金-单位"  :er-housing-fund})

(deftest parse-yonyou-csv-extracts-components
  (let [source (io/resource "kontor/payroll_cn/fixtures/yonyou_sample.csv")
        parsed (compute/parse-cn-csv
                source
                {:column-mapping compute/yonyou-default-column-mapping
                 :pay-element-codes yonyou-pay-element-codes})]
    (testing "every row maps to a kind"
      (is (every? :kind parsed)))
    (testing "the E001 fixture rows are extracted"
      (let [e001 (filter #(= "E001" (:employee-external-id %)) parsed)]
        (is (= 12 (count e001)))))
    (testing "employee-side flag matches expected"
      (let [e001 (filter #(= "E001" (:employee-external-id %)) parsed)
            er-rows (filter :employer-side? e001)
            ee-rows (remove :employer-side? e001)]
        (is (= 5 (count er-rows)))
        (is (= 7 (count ee-rows)))))))

(deftest parse-yonyou-csv-amounts
  (let [source (io/resource "kontor/payroll_cn/fixtures/yonyou_sample.csv")
        parsed (compute/parse-cn-csv
                source
                {:column-mapping compute/yonyou-default-column-mapping
                 :pay-element-codes yonyou-pay-element-codes})
        e001 (filter #(= "E001" (:employee-external-id %)) parsed)]
    (testing "base wage is positive"
      (let [bw (first (filter #(= :base-wage (:kind %)) e001))]
        (is (= 15000.00M (:amount bw)))))
    (testing "IIT withholding is negative"
      (let [iit (first (filter #(= :iit-withheld (:kind %)) e001))]
        (is (= -1330.00M (:amount iit)))))
    (testing "employer SI is positive + employer-side"
      (let [er (first (filter #(= :er-pension (:kind %)) e001))]
        (is (= 2880.00M (:amount er)))
        (is (true? (:employer-side? er)))))))

(deftest cn-facts-assembly-respects-sum-invariant
  (let [source (io/resource "kontor/payroll_cn/fixtures/yonyou_sample.csv")
        parsed (compute/parse-cn-csv
                source
                {:column-mapping compute/yonyou-default-column-mapping
                 :pay-element-codes yonyou-pay-element-codes})
        facts (compute/cn-facts parsed
                                {:external-id->eid {"E001" 1001 "E002" 1002}
                                 :engine-id :yonyou})]
    (is (= 2 (count facts)))
    (testing "E001 gross + net match (gross 18000 - deductions 5380 = net 12620)"
      (let [e001 (first (filter #(= 1001 (:employment %)) facts))]
        (is (= 18000.00M (:gross e001)))
        (is (= 12620.00M (:net e001)))))
    (testing "E002 gross + net match"
      (let [e002 (first (filter #(= 1002 (:employment %)) facts))]
        (is (= 13500.00M (:gross e002)))
        (is (= 9837.50M (:net e002)))))))

(deftest yonyou-provider-end-to-end
  (let [provider (compute/->YonyouCsvComputeProvider {})
        source (io/resource "kontor/payroll_cn/fixtures/yonyou_sample.csv")
        facts (pp/compute-payroll provider
                                  {:variable-inputs {}
                                   :csv-source source
                                   :pay-element-codes yonyou-pay-element-codes
                                   :external-id->eid {"E001" 1001 "E002" 1002}})]
    (is (= :yonyou-csv (pp/provider-id provider)))
    (is (= 2 (count facts)))
    (testing "engine recorded in jurisdiction codes"
      (is (= :yonyou (get-in (first facts) [:jurisdiction-specific-codes :engine]))))))

(deftest kingdee-provider-respects-default-column-mapping
  (let [provider (compute/->KingdeeCsvComputeProvider {})
        source (io/resource "kontor/payroll_cn/fixtures/kingdee_sample.csv")
        facts (pp/compute-payroll
               provider
               {:csv-source source
                ;; The kingdee fixture uses 薪酬项目 instead of 工资项目;
                ;; the default mapping handles that.
                :pay-element-codes yonyou-pay-element-codes
                :external-id->eid {"K001" 2001}})]
    (is (= :kingdee-csv (pp/provider-id provider)))
    (is (= 1 (count facts)))
    (let [k001 (first facts)]
      (is (= 18000.00M (:gross k001)))
      ;; 18000 - (1440+360+90+2160+1530) = 18000 - 5580 = 12420
      (is (= 12420.00M (:net k001))))))

(def beisen-pay-element-codes
  "Beisen-style English wage-element codes → kontor :component-kind."
  {"BASE_WAGE"          :base-wage
   "PERFORMANCE_BONUS"  :performance-bonus
   "OVERTIME"           :overtime
   "ANNUAL_BONUS"       :annual-bonus
   "IIT"                :iit-withheld
   "EE_PENSION"         :ee-pension
   "EE_MEDICAL"         :ee-medical
   "EE_UNEMPLOYMENT"    :ee-unemployment
   "EE_HOUSING_FUND"    :ee-housing-fund
   "ER_PENSION"         :er-pension
   "ER_MEDICAL"         :er-medical
   "ER_UNEMPLOYMENT"    :er-unemployment
   "ER_WORK_INJURY"     :er-work-injury
   "ER_HOUSING_FUND"    :er-housing-fund})

(deftest beisen-provider-uses-english-columns
  (let [provider (compute/->BeisenCsvComputeProvider {})
        source (io/resource "kontor/payroll_cn/fixtures/beisen_sample.csv")
        facts (pp/compute-payroll
               provider
               {:csv-source source
                :pay-element-codes beisen-pay-element-codes
                :external-id->eid {"B001" 3001}})]
    (is (= :beisen-csv (pp/provider-id provider)))
    (is (= 1 (count facts)))
    (let [b001 (first facts)]
      (is (= 20000.00M (:gross b001)))
      ;; 20000 - (1600+400+100+2400+1830) = 20000 - 6330 = 13670
      (is (= 13670.00M (:net b001))))))

(deftest unknown-pay-element-code-throws
  (let [csv "员工编号,工资项目,金额\nE999,XYZ,100.00\n"]
    (is (thrown? clojure.lang.ExceptionInfo
                 (compute/parse-cn-csv
                  csv
                  {:column-mapping compute/yonyou-default-column-mapping
                   :pay-element-codes {}})))))

(deftest skip-marker-drops-row
  (let [csv "员工编号,工资项目,金额\nE001,基本工资,15000.00\nE001,内部对账,0.00\n"
        parsed (compute/parse-cn-csv
                csv
                {:column-mapping compute/yonyou-default-column-mapping
                 :pay-element-codes {"基本工资" :base-wage
                                     "内部对账" :__skip}})]
    (is (= 1 (count parsed)))
    (is (= :base-wage (-> parsed first :kind)))))

(deftest jurisdiction-codes-fn-applied
  (let [provider (compute/->YonyouCsvComputeProvider {})
        source (io/resource "kontor/payroll_cn/fixtures/yonyou_sample.csv")
        facts (pp/compute-payroll
               provider
               {:csv-source source
                :pay-element-codes yonyou-pay-element-codes
                :external-id->eid {"E001" 1001 "E002" 1002}
                :jurisdiction-codes-fn
                (fn [ext-id]
                  (case ext-id
                    "E001" {:cn/province-of-employment "CN-BJ"
                            :cn/social-insurance-city "CN-BJ-110100"}
                    "E002" {:cn/province-of-employment "CN-SH"}))})
        e001 (first (filter #(= 1001 (:employment %)) facts))
        e002 (first (filter #(= 1002 (:employment %)) facts))]
    (is (= "CN-BJ" (get-in e001 [:jurisdiction-specific-codes
                                 :cn/province-of-employment])))
    (is (= "CN-SH" (get-in e002 [:jurisdiction-specific-codes
                                 :cn/province-of-employment])))))
