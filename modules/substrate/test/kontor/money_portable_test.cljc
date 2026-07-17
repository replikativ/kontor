(ns kontor.money-portable-test
  "The cross-platform subset of the Money API — the arithmetic the browser
   client relies on for sum-to-zero and display. Runs on BOTH the JVM
   (kaocha) and Node (shadow-cljs :node-test), so a missing `:cljs` branch
   or a numeric divergence between BigDecimal and the fress Bigdec is caught
   in CI rather than shipping broken (research note 191, the top risk).

   The exhaustive, JVM-only Money behaviours (locale parsing, round,
   split-by-percentages, rounding-mode matrix) stay in `kontor.money-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.money :as m]))

(deftest construct-and-display
  (testing "string + integer construction, unlocalized display"
    (is (= "100.00 EUR" (m/money->str (m/money "100.00" :EUR))))
    (is (= "1000 JPY"   (m/money->str (m/money 1000 :JPY))))
    (is (= "-0.50 USD"  (m/money->str (m/money "-0.50" :USD))))
    (is (m/money? (m/money "1.23" :EUR)))))

(deftest rejects-floats
  (testing "a double (JVM) / JS number (cljs) is refused — no silent float corruption"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (m/money 1.5 :EUR)))))

(deftest requires-commodity
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/money "1.00" nil))))

(deftest addition-subtraction-negation
  (let [a (m/money "100.00" :EUR)
        b (m/money "40.00" :EUR)]
    (is (m/equiv? (m/money "140.00" :EUR) (m/add a b)))
    (is (m/equiv? (m/money "60.00" :EUR)  (m/sub a b)))
    (is (m/equiv? (m/money "-100.00" :EUR) (m/neg a)))))

(deftest cross-commodity-arithmetic-throws
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/add (m/money "1.00" :EUR) (m/money "1.00" :USD)))))

(deftest predicates
  (is (m/zero?     (m/zero :EUR)))
  (is (m/positive? (m/money "0.01" :EUR)))
  (is (m/negative? (m/money "-0.01" :EUR)))
  (is (m/same-commodity? (m/money "1" :EUR) (m/money "2" :EUR)))
  (is (not (m/same-commodity? (m/money "1" :EUR) (m/money "2" :USD)))))

(deftest equiv-is-scale-insensitive
  (testing "differing scales, equal value"
    (is (m/equiv? (m/money "100.0" :EUR) (m/money "100.00" :EUR)))
    (is (m/equiv? (m/money "100.000" :EUR) (m/money "100" :EUR)))))

(deftest sum-and-sum-to-zero
  (testing "sum a same-commodity sequence"
    (is (m/equiv? (m/money "6.00" :EUR)
                  (m/sum [(m/money "1.00" :EUR) (m/money "2.00" :EUR) (m/money "3.00" :EUR)]))))
  (testing "the double-entry primitive: heterogeneous postings net per commodity"
    (let [by (m/sum-by-commodity [(m/money "100.00" :EUR) (m/money "-100.00" :EUR)
                                  (m/money "50.00" :USD)  (m/money "-50.00" :USD)])]
      (is (m/zero? (:EUR by)))
      (is (m/zero? (:USD by)))))
  (testing "scale-insensitive net to zero (the residual-detection path)"
    (let [by (m/sum-by-commodity [(m/money "100.0" :EUR)
                                  (m/money "-40.00" :EUR)
                                  (m/money "-60.000" :EUR)])]
      (is (m/zero? (:EUR by)))))
  (testing "a one-cent imbalance is NOT zero"
    (let [by (m/sum-by-commodity [(m/money "100.00" :EUR) (m/money "-99.99" :EUR)])]
      (is (not (m/zero? (:EUR by))))
      (is (m/equiv? (m/money "0.01" :EUR) (:EUR by))))))

(deftest posting-fragment-roundtrip
  (let [frag (m/money->posting-fragment (m/money "12.34" :EUR))
        back (m/posting->money frag)]
    (is (m/equiv? (m/money "12.34" :EUR) back))))
