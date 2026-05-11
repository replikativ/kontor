(ns kontor.money-test
  "Money type and arithmetic. Mostly pure unit tests; no datahike."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.money :as m])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Construction
;; ============================================================================

(deftest construct-from-string
  (let [eur (m/money "100.00" :EUR)]
    (is (m/money? eur))
    (is (= (BigDecimal. "100.00") (:amount eur)))
    (is (= :EUR (:commodity eur)))))

(deftest construct-from-bigdecimal
  (let [usd (m/money 99.99M :USD)]
    (is (= (BigDecimal. "99.99") (:amount usd)))
    (is (= :USD (:commodity usd)))))

(deftest construct-from-integer
  (let [jpy (m/money 1000 :JPY)]
    (is (= 0 (.compareTo (BigDecimal. "1000") (:amount jpy))))
    (is (= :JPY (:commodity jpy)))))

(deftest construct-from-double-rejected
  (testing "Rejecting doubles forces callers to use strings/BigDecimals
            instead of risking float-precision corruption."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Refusing to construct Money from a double"
         (m/money 100.0 :EUR)))))

(deftest commodity-required
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires a commodity"
       (m/money "1.00" nil))))

;; ============================================================================
;; Predicates
;; ============================================================================

(deftest zero-pos-neg
  (is (m/zero? (m/zero :EUR)))
  (is (m/zero? (m/money "0.00" :EUR)))
  (is (m/zero? (m/money "0.0000" :EUR)))
  (is (m/positive? (m/money "0.01" :EUR)))
  (is (m/negative? (m/money "-0.01" :EUR)))
  (is (not (m/positive? (m/zero :EUR))))
  (is (not (m/negative? (m/zero :EUR)))))

(deftest same-commodity-pred
  (is (m/same-commodity? (m/money "1" :EUR) (m/money "2" :EUR)))
  (is (not (m/same-commodity? (m/money "1" :EUR) (m/money "1" :USD)))))

;; ============================================================================
;; Arithmetic
;; ============================================================================

(deftest add-same-commodity
  (let [r (m/add (m/money "1.50" :EUR) (m/money "2.25" :EUR))]
    (is (= 0 (.compareTo (BigDecimal. "3.75") (:amount r))))
    (is (= :EUR (:commodity r)))))

(deftest add-cross-commodity-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Cross-commodity :add is forbidden"
       (m/add (m/money "1" :EUR) (m/money "1" :USD)))))

(deftest sub-and-neg
  (is (m/equiv? (m/money "1.00" :EUR)
                (m/sub (m/money "3.00" :EUR) (m/money "2.00" :EUR))))
  (is (m/equiv? (m/money "-1.00" :EUR)
                (m/neg (m/money "1.00" :EUR)))))

(deftest mul-scalar-with-bigdec-and-int
  (is (m/equiv? (m/money "10.00" :EUR)
                (m/mul-scalar (m/money "5.00" :EUR) 2)))
  (is (m/equiv? (m/money "5.50" :EUR)
                (m/mul-scalar (m/money "5.00" :EUR) 1.1M))))

(deftest mul-scalar-rejects-money
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unitless scalar"
       (m/mul-scalar (m/money "1" :EUR) (m/money "1" :EUR)))))

(deftest sum-empty-requires-commodity
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"empty money sequence"
       (m/sum [])))
  (is (m/zero? (m/sum [] :EUR))))

(deftest sum-mixed-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (m/sum [(m/money "1" :EUR) (m/money "1" :USD)]))))

(deftest sum-by-commodity-splits
  (let [r (m/sum-by-commodity
           [(m/money "10" :EUR)
            (m/money "20" :EUR)
            (m/money "5"  :USD)
            (m/money "-5" :USD)])]
    (is (m/equiv? (m/money "30" :EUR) (get r :EUR)))
    (is (m/zero? (get r :USD)))
    (is (= #{:EUR :USD} (set (keys r))))))

;; ============================================================================
;; Rounding
;; ============================================================================

(deftest round-half-even-default
  (testing "Banker's rounding rounds .5 to nearest even"
    ;; 2.5 → 2 (not 3) under HALF-EVEN
    (is (= (BigDecimal. "2.50")
           (:amount (m/round (m/money "2.50" :EUR) 2))))
    ;; 0.125 → 0.12 under HALF-EVEN at 2 digits (0.12 is even)
    (is (= (BigDecimal. "0.12")
           (:amount (m/round (m/money "0.125" :EUR) 2))))
    ;; 0.135 → 0.14 under HALF-EVEN at 2 digits (0.14 is even)
    (is (= (BigDecimal. "0.14")
           (:amount (m/round (m/money "0.135" :EUR) 2))))))

(deftest round-half-up-explicit
  (testing "HALF-UP for regulators that mandate it"
    (is (= (BigDecimal. "0.13")
           (:amount (m/round (m/money "0.125" :EUR) 2 :half-up))))
    (is (= (BigDecimal. "0.14")
           (:amount (m/round (m/money "0.135" :EUR) 2 :half-up))))))

(deftest round-unknown-mode
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Unknown rounding mode"
       (m/round (m/money "1" :EUR) 2 :bogus))))

;; ============================================================================
;; Display / parsing
;; ============================================================================

(deftest money-to-string
  (is (= "100.00 EUR" (m/money->str (m/money "100.00" :EUR))))
  (is (= "-1.5 USD" (m/money->str (m/money "-1.5" :USD)))))

(deftest parse-decimal-german-format
  (is (= 0 (.compareTo (BigDecimal. "1234.56") (m/parse-decimal "1.234,56"))))
  (is (= 0 (.compareTo (BigDecimal. "0.99") (m/parse-decimal "0,99")))))

(deftest parse-decimal-english-format
  (is (= 0 (.compareTo (BigDecimal. "1234.56") (m/parse-decimal "1,234.56"))))
  (is (= 0 (.compareTo (BigDecimal. "0.99") (m/parse-decimal "0.99")))))

(deftest parse-decimal-no-separators
  (is (= 0 (.compareTo (BigDecimal. "1000") (m/parse-decimal "1000")))))

(deftest parse-decimal-rejects-garbage
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Could not parse decimal"
       (m/parse-decimal "not-a-number"))))

;; ============================================================================
;; Datahike interop
;; ============================================================================

(deftest money-posting-roundtrip
  (let [m1 (m/money "42.50" :EUR)
        frag (m/money->posting-fragment m1)
        m2 (m/posting->money frag)]
    (is (= {:posting/amount    (BigDecimal. "42.50")
            :posting/commodity :EUR}
           frag))
    (is (m/equiv? m1 m2))))

(deftest posting-without-amount-yields-nil
  (is (nil? (m/posting->money {:posting/display-type :note}))))

;; ============================================================================
;; Equality / equiv
;; ============================================================================

(deftest record-equality-is-value-based
  (testing "Clojure's = on defrecord delegates to clojure.lang.Util/equiv,
            which uses Numbers/equiv for BigDecimal — i.e., 1.0M = 1.00M
            is true. equiv? still has value: it surfaces a clearer error
            path on commodity mismatch, and works as a docstring-level
            assertion of intent."
    (is (= (m/money "1.0" :EUR) (m/money "1.00" :EUR)))
    (is (m/equiv? (m/money "1.0" :EUR) (m/money "1.00" :EUR)))))

(deftest scale-sensitivity-only-via-bigdecimal-equals
  (testing ".equals on BigDecimal is scale-sensitive — useful only when
            you specifically need the storage scale, not the value."
    (is (not (.equals (BigDecimal. "1.0") (BigDecimal. "1.00"))))))

(deftest equiv-cross-commodity-false
  (is (not (m/equiv? (m/money "1.00" :EUR) (m/money "1.00" :USD)))))

(deftest record-equality-different-commodity-false
  (is (not= (m/money "1.00" :EUR) (m/money "1.00" :USD))))
