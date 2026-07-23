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

(deftest add-zero-is-cross-commodity-identity
  ;; note 196 F5b: a zero is the additive identity in ANY commodity, so an
  ;; empty statement line (a zero of the engine's default commodity) folds
  ;; into a real-commodity total without a spurious cross-commodity throw.
  (testing "(add zero-X y) => y, carrying y's commodity"
    (let [r (m/add (m/zero :EUR) (m/money "5.00" :CAD))]
      (is (= 0 (.compareTo (BigDecimal. "5.00") (:amount r))))
      (is (= :CAD (:commodity r)))))
  (testing "(add x zero-Y) => x, carrying x's commodity"
    (let [r (m/add (m/money "5.00" :CAD) (m/zero :EUR))]
      (is (= :CAD (:commodity r)))
      (is (= 0 (.compareTo (BigDecimal. "5.00") (:amount r))))))
  (testing "two zeros of different commodity do not throw"
    (is (m/zero? (m/add (m/zero :EUR) (m/zero :CAD)))))
  (testing "two NON-zero operands of different commodity still throw"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cross-commodity :add is forbidden"
         (m/add (m/money "1" :EUR) (m/money "1" :USD))))))

(deftest sub-zero-is-cross-commodity-identity
  (testing "(sub x zero-Y) => x"
    (let [r (m/sub (m/money "5.00" :CAD) (m/zero :EUR))]
      (is (= :CAD (:commodity r)))
      (is (= 0 (.compareTo (BigDecimal. "5.00") (:amount r))))))
  (testing "(sub zero-X y) => (neg y)"
    (let [r (m/sub (m/zero :EUR) (m/money "5.00" :CAD))]
      (is (= :CAD (:commodity r)))
      (is (= 0 (.compareTo (BigDecimal. "-5.00") (:amount r))))))
  (testing "two NON-zero operands of different commodity still throw"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cross-commodity :sub is forbidden"
         (m/sub (m/money "1" :EUR) (m/money "1" :USD))))))

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
    (is (= {:kontor.posting/amount    (BigDecimal. "42.50")
            :kontor.posting/commodity :EUR}
           frag))
    (is (m/equiv? m1 m2))))

(deftest posting-without-amount-yields-nil
  (is (nil? (m/posting->money {:kontor.posting/display-type :note}))))

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

;; ============================================================================
;; split-by-percentages — largest-remainder apportionment
;; ============================================================================

(defn- sum-amounts [monies]
  (reduce #(.add ^BigDecimal %1 ^BigDecimal %2)
          BigDecimal/ZERO
          (map :amount monies)))

(deftest split-clean-50-50
  (let [parts (m/split-by-percentages (m/money "100.00" :EUR) [50M 50M])]
    (is (= 2 (count parts)))
    (is (m/equiv? (m/money "50.00" :EUR) (nth parts 0)))
    (is (m/equiv? (m/money "50.00" :EUR) (nth parts 1)))))

(deftest split-clean-60-40
  (let [parts (m/split-by-percentages (m/money "100.00" :EUR) [60M 40M])]
    (is (m/equiv? (m/money "60.00" :EUR) (nth parts 0)))
    (is (m/equiv? (m/money "40.00" :EUR) (nth parts 1)))))

(deftest split-with-residue-100-eur-into-thirds
  (testing "100.00 EUR / 3 → bit-exact total of 100.00; the residue
            cent lands on the child with the largest fractional remainder"
    (let [parts (m/split-by-percentages
                 (m/money "100.00" :EUR)
                 [33.333333M 33.333333M 33.333334M])]
      (is (= 3 (count parts)))
      (is (= 0 (.compareTo (bigdec "100.00") (sum-amounts parts)))
          "Sum must be bit-exact")
      ;; Slot 2 has the larger percent → its true value has the
      ;; larger fractional remainder → it gets the residue cent.
      (is (m/equiv? (m/money "33.34" :EUR) (nth parts 2))))))

(deftest split-with-residue-100-eur-equal-thirds
  (testing "100.00 EUR / 3 with EXACTLY equal 33.333333% percents (sum
            99.999999) — sub-tolerance case still produces a bit-exact total"
    (let [parts (m/split-by-percentages
                 (m/money "100.00" :EUR)
                 [33.333333M 33.333333M 33.333333M])]
      (is (= 0 (.compareTo (bigdec "100.00") (sum-amounts parts)))))))

(deftest split-zero-percent-children-get-zero
  (let [parts (m/split-by-percentages
               (m/money "100.00" :EUR)
               [60M 0M 40M])]
    (is (m/equiv? (m/money  "60.00" :EUR) (nth parts 0)))
    (is (m/equiv? (m/money   "0.00" :EUR) (nth parts 1)))
    (is (m/equiv? (m/money  "40.00" :EUR) (nth parts 2)))))

(deftest split-rejects-negative-percent
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"negative percent"
       (m/split-by-percentages (m/money "100.00" :EUR) [110M -10M]))))

(deftest split-rejects-empty
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"empty percent sequence"
       (m/split-by-percentages (m/money "100.00" :EUR) []))))

(deftest split-negative-amount
  (testing "Splitting a negative amount produces negative children
            whose sum is bit-exact to the input — symmetric to the
            positive case"
    (let [parts (m/split-by-percentages
                 (m/money "-100.00" :EUR)
                 [33.333333M 33.333333M 33.333334M])]
      (is (= 0 (.compareTo (bigdec "-100.00") (sum-amounts parts)))
          "Sum must be bit-exact for negative input")
      (is (m/equiv? (m/money "-33.34" :EUR) (nth parts 2))
          "Residue cent lands on the largest-percent slot, with negative sign"))))

(deftest split-large-residue-many-children
  (testing "7 children @ 100/7 % — residue is several cents; must be
            distributed across the largest-remainder slots so the sum
            remains bit-exact"
    (let [parts (m/split-by-percentages
                 (m/money "100.00" :EUR)
                 (vec (repeat 7 (.divide 100M 7M 10 java.math.RoundingMode/HALF_EVEN))))]
      (is (= 0 (.compareTo (bigdec "100.00") (sum-amounts parts)))
          "Sum must be bit-exact across 7-way split with residue"))))
