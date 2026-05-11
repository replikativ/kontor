(ns kontor.bank-ca.info-preservation-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.bank-ca.parser :as p]))

(def fixture-dir (-> (io/resource "rbc.csv") io/file .getParentFile))
(def fixtures ["rbc.csv" "td.csv" "scotiabank.csv" "bmo.csv"])

(defn- candidate-reachable [c]
  (str/join " " [(or (:counterparty c) "")
                 (or (:counterparty-iban c) "")
                 (or (:description c) "")
                 (or (:transaction-type c) "")
                 (str/join " " (or (:raw-row c) []))]))

(deftest raw-row-preserved
  (doseq [fname fixtures
          :let [cs (p/parse-statement (.getAbsolutePath (io/file fixture-dir fname)))]]
    (is (every? #(vector? (:raw-row %)) cs) (str fname " — :raw-row missing"))))

(deftest non-blank-cells-reachable
  (doseq [fname fixtures
          :let [cs (p/parse-statement (.getAbsolutePath (io/file fixture-dir fname)))]]
    (let [drops (for [c cs
                      :let [non-blank (filter (complement str/blank?) (:raw-row c))
                            reach (candidate-reachable c)]
                      cell non-blank
                      :when (not (str/includes? reach cell))]
                  {:fname fname :missing cell})]
      (is (empty? drops)
          (str fname " dropped " (count drops) " cells, e.g.: " (pr-str (take 3 drops)))))))
