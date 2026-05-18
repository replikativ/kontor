(ns kontor.payroll-us-adp.w2-recon-test
  "Stage R C3 — W-2 reconciliation report tests (ADR-077, note 83 §7.3).

   The kontor surface is data-prep, not generation. We validate:
     - Box 1 (Federal wages) = Σ wages - Σ :reduces-box-1?
     - Box 3 (SS wages) capped at the annual SS wage base
     - Box 5 (Medicare wages) uncapped
     - Box 12 grouped by :w2-code
     - Box 16 / 17 / 19 grouped by state"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-us-adp.w2-recon :as w2]))

(defn- mk-fact [opts]
  (merge {:employment "E-test"
          :gross 0M :net 0M :components []}
         opts))

(def standard-employee-fact
  ;; Annual: $120k gross, $12k 401(k) pre-tax, $4.8k §125 health.
  ;; Box 1 = 120000 - 12000 - 4800 = 103,200
  ;; Box 3 = 120000 - 4800 = 115,200 (well under SS base)
  ;; Box 5 = 115,200 (uncapped)
  ;; Box 12 = {"D" 12000, "DD" 14400}
  (mk-fact
   {:employment "E-NORMAL"
    :gross 120000M :net 0M
    :components [{:role :wage-expense :amount 120000M
                  :employer-side? false :state "CA"}
                 {:role :ee-401k-deferral :amount -12000M
                  :w2-box "12" :w2-code "D"
                  :reduces-box-1? true :reduces-box-3? false
                  :reduces-box-5? false :employer-side? false :state "CA"}
                 {:role :ee-section125 :amount -4800M
                  :reduces-box-1? true :reduces-box-3? true
                  :reduces-box-5? true :section-125? true
                  :employer-side? false :state "CA"}
                 {:role :er-health :amount 14400M
                  :w2-box "12" :w2-code "DD"
                  :employer-side? true :state "CA"}
                 {:role :ee-fica-ss :amount -7142.40M
                  :w2-box "4" :employer-side? false :state "CA"}
                 {:role :ee-fica-medicare :amount -1670.40M
                  :w2-box "6" :employer-side? false :state "CA"}
                 {:role :ee-fed-withheld :amount -18000M
                  :w2-box "2" :employer-side? false :state "CA"}
                 {:role :ee-state-withheld :amount -8000M
                  :w2-box "17" :employer-side? false :state "CA"}]}))

(deftest box-1-reduces-by-401k-and-section-125
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 1 = gross - 401(k) - §125"
      (is (= 103200M (:w2-box-1 r))))))

(deftest box-3-reduces-by-section-125-but-not-401k-pre-tax
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 3 = gross - §125 (401(k) traditional does NOT reduce SS wages)"
      ;; 120000 - 4800 = 115200, below SS wage base of 168_600 → uncapped.
      (is (= 115200M (:w2-box-3 r))))))

(deftest box-5-uncapped
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 5 = gross - §125 (no cap)"
      (is (= 115200M (:w2-box-5 r))))))

(deftest box-3-capped-at-ss-wage-base
  ;; Synthesize a high earner: $300k gross, no deductions.
  ;; Box 3 should cap at 168,600 (2024 SS wage base).
  (let [hi (mk-fact
            {:employment "E-HIGH"
             :gross 300000M
             :components [{:role :wage-expense :amount 300000M
                           :employer-side? false :state "NY"}]})
        r (w2/employee-w2-from-facts [hi] {})]
    (testing "Box 3 caps at SS wage base"
      (is (= 168600M (:w2-box-3 r))))
    (testing "Box 5 stays uncapped (Medicare has no cap)"
      (is (= 300000M (:w2-box-5 r))))))

(deftest box-4-and-6-derive-from-ee-fica-postings
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 4 = absolute sum of :ee-fica-ss"
      (is (= 7142.40M (:w2-box-4 r))))
    (testing "Box 6 = absolute sum of :ee-fica-medicare"
      (is (= 1670.40M (:w2-box-6 r))))))

(deftest box-12-groups-by-w2-code
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 12 has {D … DD …}"
      (is (= {"D" 12000M "DD" 14400M} (:w2-box-12 r))))))

(deftest box-2-aggregates-federal-withholding
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 2 = |Σ :ee-fed-withheld|"
      (is (= 18000M (:w2-box-2 r))))))

(deftest box-17-grouped-by-state
  (let [r (w2/employee-w2-from-facts [standard-employee-fact] {})]
    (testing "Box 17 keyed by state code"
      (is (= 8000M (get (:w2-box-17 r) "CA"))))))

(deftest ytd-by-employee-handles-multiple-facts
  (let [january (mk-fact
                 {:employment "E1"
                  :gross 10000M
                  :components [{:role :wage-expense :amount 10000M
                                :employer-side? false :state "TX"}
                               {:role :ee-fed-withheld :amount -1500M
                                :w2-box "2" :employer-side? false :state "TX"}]})
        february (mk-fact
                  {:employment "E1"
                   :gross 10000M
                   :components [{:role :wage-expense :amount 10000M
                                 :employer-side? false :state "TX"}
                                {:role :ee-fed-withheld :amount -1500M
                                 :w2-box "2" :employer-side? false :state "TX"}]})
        reports (w2/ytd-by-employee [january february] {})]
    (testing "one report per distinct employment"
      (is (= 1 (count reports))))
    (testing "totals accumulate across pay-periods"
      (is (= 20000M (:wages-gross (first reports))))
      (is (= 3000M (:w2-box-2 (first reports)))))))
