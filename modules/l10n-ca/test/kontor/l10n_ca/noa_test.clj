(ns kontor.l10n-ca.noa-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.noa :as noa]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

(deftest manual-map-passes-through
  (testing "Hand-typed NoA facts normalize into a carryforward-facts structure"
    (let [r (noa/from-manual-map
             {:carryforward/tax-year 2023
              :carryforward/rrsp-deduction-room  (cad "12500.00")
              :carryforward/capital-loss-balance (cad "0.00")
              :carryforward/tfsa-room            (cad "8000.00")})]
      (is (= :manual (:carryforward/source r)))
      (is (empty? (:carryforward/unknown-keys r)))
      (is (≈ (cad "12500.00")
             (:carryforward/rrsp-deduction-room
              (:carryforward/facts r)))))))

(deftest manual-map-flags-unknown-keys
  (testing "Unknown :carryforward/* keys surface as warnings"
    (let [r (noa/from-manual-map
             {:carryforward/tax-year 2023
              :carryforward/something-novel (cad "100.00")})]
      (is (contains? (:carryforward/unknown-keys r)
                     :carryforward/something-novel)))))

(deftest pdf-parser-stub-throws
  (testing "Stub parser throws with a helpful message"
    (let [p (noa/->TextParserStub)]
      (try (noa/parse-pdf p "/nonexistent.pdf")
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :parse-failed (:type (ex-data e)))))))))

(deftest projection-to-t1-inputs
  (testing "->t1-inputs extracts the keys T1 consumes"
    (let [facts (noa/from-manual-map
                 {:carryforward/tax-year 2023
                  :carryforward/rrsp-deduction-room  (cad "12500.00")
                  :carryforward/capital-loss-balance (cad "3000.00")
                  :carryforward/tfsa-room            (cad "8000.00")})
          proj (noa/->t1-inputs facts)]
      (is (≈ (cad "12500.00") (:rrsp-deduction-limit proj)))
      (is (≈ (cad "3000.00")  (:prior-capital-loss proj)))
      (is (contains? proj :carryforward/facts)
          "raw facts preserved for downstream forms"))))
