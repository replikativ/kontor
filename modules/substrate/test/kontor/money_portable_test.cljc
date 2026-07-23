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

(deftest zero-is-cross-commodity-additive-identity
  ;; note 196 F5b — must hold on the cljs branch too (bd-add/bd-neg + the
  ;; cljs zero? path): a zero is the additive identity in ANY commodity.
  (testing "add: a zero of either commodity carries the other's commodity"
    (is (m/equiv? (m/money "5.00" :CAD) (m/add (m/zero :EUR) (m/money "5.00" :CAD))))
    (is (m/equiv? (m/money "5.00" :CAD) (m/add (m/money "5.00" :CAD) (m/zero :EUR))))
    (is (m/zero? (m/add (m/zero :EUR) (m/zero :CAD)))))
  (testing "sub: zero is the identity; (sub 0-X y) => -y"
    (is (m/equiv? (m/money "5.00" :CAD)  (m/sub (m/money "5.00" :CAD) (m/zero :EUR))))
    (is (m/equiv? (m/money "-5.00" :CAD) (m/sub (m/zero :EUR) (m/money "5.00" :CAD)))))
  (testing "two NON-zero mismatched operands still throw"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (m/sub (m/money "1.00" :EUR) (m/money "1.00" :USD))))))

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

(defn- rstr [s prec mode] (m/money->str (m/round (m/money s :EUR) prec mode)))

(deftest round-half-even
  (testing "ties round to even — bit-identical JVM ↔ cljs"
    (is (= "1.00 EUR" (rstr "1.005" 2 :half-even)) "tie, preceding digit even → stays")
    (is (= "1.02 EUR" (rstr "1.015" 2 :half-even)) "tie, preceding digit odd → up")
    (is (= "2 EUR"    (rstr "2.5" 0 :half-even)))
    (is (= "4 EUR"    (rstr "3.5" 0 :half-even)))
    (is (= "-1.00 EUR" (rstr "-1.005" 2 :half-even)) "sign-symmetric")
    (is (= "-2 EUR"   (rstr "-2.5" 0 :half-even)))))

(deftest round-other-modes
  (is (= "1.01 EUR" (rstr "1.005" 2 :half-up)))
  (is (= "1.00 EUR" (rstr "1.004" 2 :half-up)))
  (is (= "1.24 EUR" (rstr "1.231" 2 :ceiling)))
  (is (= "1.23 EUR" (rstr "1.239" 2 :floor)))
  (is (= "1.23 EUR" (rstr "1.239" 2 :down)))
  (is (= "1.24 EUR" (rstr "1.231" 2 :up)))
  (is (= "-1.24 EUR" (rstr "-1.231" 2 :floor)) "floor goes toward -inf")
  (testing "scaling up pads exactly"
    (is (= "1.2000 EUR" (rstr "1.2" 4 :half-even))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/round (m/money "1.00" :EUR) 2 :bogus))))

(deftest multiply-amounts-exact
  (testing "amount × rate, exact (scale = sum of scales); round after"
    (let [a (:amount (m/money "1.10" :EUR))
          b (:amount (m/money "1.10" :EUR))
          prod (m/->Money (m/multiply-amounts a b) :EUR)]
      (is (= "1.2100 EUR" (m/money->str prod)) "1.10 × 1.10 = 1.2100 (scale 4)")
      (is (= "1.21 EUR" (m/money->str (m/round prod 2 :half-even)))))
    (let [amt (:amount (m/money "100.00" :EUR))
          rate (:amount (m/money "0.8375" :EUR))
          conv (m/round (m/->Money (m/multiply-amounts amt rate) :EUR) 2 :half-even)]
      (is (= "83.75 EUR" (m/money->str conv)) "100.00 × 0.8375 = 83.7500 → 83.75"))))

(deftest divide-amounts-parity
  (let [d (fn [a b n] (m/money->str (m/->Money (m/divide-amounts (:amount (m/money a :EUR))
                                                                 (:amount (m/money b :EUR)) n)
                                               :EUR)))]
    (is (= "0.333333333333 EUR" (d "1" "3" 12)) "1/3 to 12 places, half-even")
    (is (= "0.666666666667 EUR" (d "2" "3" 12)) "2/3 rounds the last digit up")
    (is (= "1.194029850746 EUR" (d "1" "0.8375" 12)) "reciprocal of a rate")
    (is (= "-0.25 EUR" (d "-1" "4" 2)) "sign-aware")))
