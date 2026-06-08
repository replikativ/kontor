(ns kontor.payroll-at.compute-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-at.compute :as compute]))

(defn- fixture [name]
  (io/resource (str "kontor/payroll_at/fixtures/" name)))

(deftest bmd-parse-jan
  (testing "Parsing BMD CSV for January 2026"
    (let [r (compute/parse :bmd (fixture "bmd-2026-01.csv"))]
      (is (some? (:payroll-result/period r)))
      (is (= 2 (count (:payroll-result/employees r)))
          "two employees in the fixture")
      (let [emps (:payroll-result/employees r)
            max-emp (first (filter #(= "1234567890" (:vsnr %)) emps))]
        (is (some? max-emp))
        (is (= "Mustermann, Max" (:name max-emp)))
        (is (= 8 (count (:line-items max-emp))))
        ;; line-items vector contains the recognized wage types only
        (let [wt-set (set (map :wage-type (:line-items max-emp)))]
          (is (contains? wt-set :grundgehalt))
          (is (contains? wt-set :lohnsteuer))
          (is (contains? wt-set :sv-arbeitnehmer))
          (is (contains? wt-set :nettogehalt))
          (is (not (contains? wt-set :urlaubsremuneration))
              "no Sonderzahlung in January"))))))

(deftest bmd-period-totals
  (testing "period totals aggregate across employees"
    (let [r (compute/parse :bmd (fixture "bmd-2026-01.csv"))
          totals (:payroll-result/totals r)]
      ;; Max 3000 + Erika 2500 = 5500 grundgehalt
      (is (= 0 (.compareTo (bigdec "5500.00") (:grundgehalt totals))))
      ;; Lohnsteuer 500 + 380 = 880
      (is (= 0 (.compareTo (bigdec "880.00") (:lohnsteuer totals))))
      ;; nettogehalt 1956.40 + 1667.00 = 3623.40
      (is (= 0 (.compareTo (bigdec "3623.40") (:nettogehalt totals)))))))

(deftest bmd-period-bounds
  (testing "the period bounds derive from the Periode column"
    (let [r (compute/parse :bmd (fixture "bmd-2026-01.csv"))
          {:keys [from to]} (:payroll-result/period r)
          fmt (java.text.SimpleDateFormat. "yyyy-MM-dd")]
      (.setTimeZone fmt (java.util.TimeZone/getTimeZone "UTC"))
      (is (= "2026-01-01" (.format fmt from)))
      (is (= "2026-02-01" (.format fmt to))))))

(deftest bmd-validate-net
  (testing "engine fixture is self-consistent — net = gross - withholdings"
    (let [r (compute/parse :bmd (fixture "bmd-2026-01.csv"))
          v (compute/validate-result r)]
      (is (:ok? v) (str "anomalies: " (pr-str (:anomalies v)))))))

(deftest bmd-parse-june-sonderzahlung
  (testing "June fixture carries the 13th Sonderzahlung"
    (let [r (compute/parse :bmd (fixture "bmd-2026-06.csv"))
          totals (:payroll-result/totals r)]
      ;; Two employees × 3000/2500 Sonderzahlung = 5500
      (is (= 0 (.compareTo (bigdec "5500.00")
                           (:urlaubsremuneration totals)))))))

(deftest rzl-parse
  (testing "RZL CSV produces the same normalized shape as BMD"
    (let [r (compute/parse :rzl (fixture "rzl-2026-01.csv"))]
      (is (= 1 (count (:payroll-result/employees r))))
      (let [emp (first (:payroll-result/employees r))]
        (is (= "1234567890" (:vsnr emp)))
        (let [wt-set (set (map :wage-type (:line-items emp)))]
          (is (contains? wt-set :grundgehalt))
          (is (contains? wt-set :lohnsteuer)))))))

(deftest engine-disagrees-fails-validate
  (testing "a bad fixture (net column wrong) is caught"
    (let [bad (str "Periode;VSNR;Name;Lohnart-Nr;Lohnart-Bez;Betrag;Beitragsgruppe;Konto;Kostenstelle\n"
                   "2026-01;1234567890;Test;0001;Grundgehalt;3000,00;D1;6000;100\n"
                   "2026-01;1234567890;Test;0200;Lohnsteuer;500,00;D1;3500;100\n"
                   "2026-01;1234567890;Test;0210;SV-AN;500,00;D1;3540;100\n"
                   ;; Claimed net 9999.99 is way off the expected
                   ;; gross-withholdings of 2000.00 — should flag.
                   "2026-01;1234567890;Test;9000;Nettogehalt;9999,99;D1;3700;100\n")
          r (compute/parse :bmd bad)
          v (compute/validate-result r)]
      (is (not (:ok? v))))))
