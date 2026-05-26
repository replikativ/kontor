(ns kontor.payroll-cn.emit-test
  "Unit tests for the CN IIT monthly emit provider (ADR-085 / note 87 §6)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.emit :as emit]
            [kontor.payroll-provider :as pp]))

(def fact-1
  {:employment 1001
   :gross 18000M
   :net 12620M
   :components [{:kind :base-wage         :amount 15000M  :employer-side? false}
                {:kind :performance-bonus :amount 3000M   :employer-side? false}
                {:kind :ee-pension        :amount -1440M  :employer-side? false}
                {:kind :ee-medical        :amount -360M   :employer-side? false}
                {:kind :ee-unemployment   :amount -90M    :employer-side? false}
                {:kind :ee-housing-fund   :amount -2160M  :employer-side? false}
                {:kind :iit-withheld      :amount -1330M  :employer-side? false}]
   :jurisdiction-specific-codes {:employee-external-id "E001"
                                 :cn/employee-name "张三"}})

(def fact-2-with-bonus
  {:employment 1002
   :gross 30000M
   :net 22000M
   :components [{:kind :base-wage     :amount 18000M :employer-side? false}
                {:kind :annual-bonus  :amount 12000M :employer-side? false}
                {:kind :iit-withheld  :amount -8000M :employer-side? false}]
   :jurisdiction-specific-codes {:employee-external-id "E002"
                                 :cn/employee-name "李四"
                                 :cn/annual-bonus-method :single}})

(deftest render-csv-includes-bilingual-header
  (let [csv (emit/render-iit-monthly-csv
             {:facts [fact-1]
              :pay-period-code "2026-04"})]
    (is (str/includes? csv "员工编号 / employee-id"))
    (is (str/includes? csv "扣除个税 / iit-withheld"))
    (is (str/includes? csv "年终奖计税方法 / annual-bonus-method"))))

(deftest render-csv-emits-one-row-per-employee
  (let [csv (emit/render-iit-monthly-csv
             {:facts [fact-1 fact-2-with-bonus]
              :pay-period-code "2026-04"})
        lines (str/split-lines csv)]
    ;; header + 2 employee rows
    (is (= 3 (count lines)))))

(deftest render-csv-row-content
  (let [csv (emit/render-iit-monthly-csv
             {:facts [fact-2-with-bonus]
              :pay-period-code "2026-04"})]
    (testing "row carries employee name in Chinese"
      (is (str/includes? csv "李四")))
    (testing "row records the period"
      (is (str/includes? csv "2026-04")))
    (testing "row carries IIT amount"
      (is (str/includes? csv "8000.00")))
    (testing "row carries annual-bonus method label"
      (is (str/includes? csv "single")))))

(deftest emit-provider-produces-audit-doc
  (let [provider (emit/make-provider {:pay-period-code "2026-04"
                                      :entity-code "ACME-CN"})
        docs (pp/emit-payroll-events provider
                                     [fact-1 fact-2-with-bonus]
                                     {:pay-period-eid 99
                                      :entity-eid 42})]
    (testing "exactly one audit-doc per period"
      (is (= 1 (count docs))))
    (let [doc (first docs)]
      (testing "audit-doc carries the canonical category"
        (is (= :payroll-filing (:kontor.audit-doc/category doc))))
      (testing "language tag is zh-cn"
        (is (= :zh-cn (:kontor.audit-doc/language doc))))
      (testing "type is :emit-payload"
        (is (= :emit-payload (:kontor.audit-doc/type doc))))
      (testing "code follows the CN-IIT-<period>-<entity> convention"
        (is (= "CN-IIT-2026-04-ACME-CN" (:kontor.audit-doc/code doc))))
      (testing "inline payload is non-empty + bilingual"
        (is (some? (:kontor.audit-doc/inline-payload doc)))
        (is (str/includes? (:kontor.audit-doc/inline-payload doc)
                           "员工编号 / employee-id")))
      (testing "storage URI defaults under file://iit/"
        (is (str/starts-with? (:kontor.audit-doc/storage-uri doc)
                              "file://iit/2026-04/")))
      (testing "linkage attrs to period + entity"
        (is (= 99 (:kontor.audit-doc/payroll-period doc)))
        (is (= 42 (:kontor.audit-doc/payroll-entity doc))))
      (testing "unmapped-count is zero (all kinds known)"
        (is (= 0 (:kontor.audit-doc/unmapped-count doc)))))))

(deftest emit-provider-rejects-empty-facts
  (let [provider (emit/make-provider {:pay-period-code "2026-04"
                                      :entity-code "ACME-CN"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (pp/emit-payroll-events provider [] {})))))

(deftest emit-provider-uri-prefix-override
  (let [provider (emit/make-provider {:pay-period-code "2026-04"
                                      :entity-code "ACME-CN"
                                      :uri-prefix "s3://payroll-archive/cn/"})
        [doc] (pp/emit-payroll-events provider [fact-1] {})]
    (is (str/starts-with? (:kontor.audit-doc/storage-uri doc)
                          "s3://payroll-archive/cn/"))))

(deftest emit-provider-derives-period-from-date
  (let [provider (emit/make-provider {:pay-period-date #inst "2026-04-30"
                                      :entity-code "ACME-CN"})
        [doc] (pp/emit-payroll-events provider [fact-1] {})]
    ;; The pay-period-date #inst "2026-04-30" formats to "2026-04" (UTC).
    (is (str/includes? (:kontor.audit-doc/code doc) "2026-04"))))
