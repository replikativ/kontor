(ns kontor.payroll-us-adp.wage-types-test
  "Stage R C3 — wage-type-map loader + validator tests (ADR-077).

   The wage-type map ships as consumer-supplied EDN with no baked-in
   defaults. We validate that the loader compiles regexes correctly
   and the lightweight validator catches the common mistakes
   (missing :vendor / :description-rules / catch-all-rule)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-us-adp.wage-types :as wt]))

(deftest load-reference-produces-valid-shape
  (let [wtm (wt/load-reference)]
    (testing "vendor + format markers present"
      (is (= :adp (:vendor wtm)))
      (is (seq (:description-rules wtm))))
    (testing "regex strings compiled to Patterns"
      (is (every? #(instance? java.util.regex.Pattern (:match %))
                  (:description-rules wtm))))
    (testing "validate is clean"
      (is (nil? (wt/validate wtm))))))

(deftest validate-catches-missing-vendor
  (let [errs (wt/validate {:description-rules
                           [{:match #".*" :role :unmapped}]})]
    (is (some #(= :missing-vendor (:error %)) errs))))

(deftest validate-catches-empty-rules
  (let [errs (wt/validate {:vendor :adp :csv-format {:columns []}
                           :description-rules []})]
    (is (some #(= :no-description-rules (:error %)) errs))))

(deftest validate-catches-missing-catch-all
  (let [errs (wt/validate {:vendor :adp
                           :csv-format {:columns []}
                           :description-rules
                           [{:match #"^GROSS$" :role :wage-expense}]})]
    (is (some #(= :no-catch-all-rule (:error %)) errs))))
