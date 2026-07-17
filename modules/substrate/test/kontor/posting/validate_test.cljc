(ns kontor.posting.validate-test
  "Cross-platform tests for the pure tier-1 balance/well-formedness check.
   Runs on the JVM in the kaocha suite; the same assertions compile to
   ClojureScript (the browser client runs this exact code for optimistic
   form feedback — research note 190)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.posting.validate :as pv]
            [kontor.money :as money]))

;; Mirror the client shape: :kontor.posting/amount is a platform decimal
;; (BigDecimal on the JVM, fress Bigdec in the browser), obtained here via
;; the commodity-checked constructor.
(defn- amt [s comm] (:amount (money/money s comm)))

(def eur :EUR)

(defn- header [] {:kontor.transaction/journal        :sales
                  :kontor.transaction/effective-date "2026-01-01"})

(deftest balanced-two-leg-is-ok
  (let [r (pv/validate
           {:transaction (header)
            :postings [{:kontor.posting/account :cash    :kontor.posting/amount (amt "100.00" eur)  :kontor.posting/commodity eur}
                       {:kontor.posting/account :revenue :kontor.posting/amount (amt "-100.00" eur) :kontor.posting/commodity eur}]})]
    (is (:ok? r))
    (is (= :single-entity (:mode r)))
    (is (empty? (:errors r)))
    (is (empty? (:unbalanced r)))))

(deftest scale-insensitive-net
  (testing "differing scales that net to zero are balanced"
    (let [r (pv/validate
             {:transaction (header)
              :postings [{:kontor.posting/account :cash :kontor.posting/amount (amt "100.0" eur)   :kontor.posting/commodity eur}
                         {:kontor.posting/account :r1   :kontor.posting/amount (amt "-40.00" eur)  :kontor.posting/commodity eur}
                         {:kontor.posting/account :r2   :kontor.posting/amount (amt "-60.000" eur) :kontor.posting/commodity eur}]})]
      (is (:ok? r))
      (is (empty? (:unbalanced r))))))

(deftest unbalanced-by-a-cent
  (let [r (pv/validate
           {:transaction (header)
            :postings [{:kontor.posting/account :cash    :kontor.posting/amount (amt "100.00" eur) :kontor.posting/commodity eur}
                       {:kontor.posting/account :revenue :kontor.posting/amount (amt "-99.99" eur) :kontor.posting/commodity eur}]})]
    (is (not (:ok? r)))
    (is (some #(= :unbalanced (:error %)) (:errors r)))
    (testing "the residual is surfaced for the UI to render"
      (let [residual (get-in r [:unbalanced nil eur])]
        (is (some? residual))
        (is (= "0.01 EUR" (money/money->str residual)))))))

(deftest missing-commodity-is-caught
  (let [r (pv/validate
           {:transaction (header)
            :postings [{:kontor.posting/account :a :kontor.posting/amount (amt "5.00" eur) :kontor.posting/commodity eur}
                       {:kontor.posting/account :b :kontor.posting/amount (amt "-5.00" eur)}]})]
    (is (not (:ok? r)))
    (is (some #(= :missing-commodity (:error %)) (:errors r)))))

(deftest too-few-postings
  (let [r (pv/validate
           {:transaction (header)
            :postings [{:kontor.posting/account :cash :kontor.posting/amount (amt "100.00" eur) :kontor.posting/commodity eur}]})]
    (is (not (:ok? r)))
    (is (some #(= :too-few-postings (:error %)) (:errors r)))))

(deftest ui-only-lines-ignored-in-balance
  (testing ":section / :note lines do not require account/amount and don't affect the sum"
    (let [r (pv/validate
             {:transaction (header)
              :postings [{:kontor.posting/display-type :section :kontor.posting/narration "Header"}
                         {:kontor.posting/account :cash    :kontor.posting/amount (amt "100.00" eur)  :kontor.posting/commodity eur}
                         {:kontor.posting/account :revenue :kontor.posting/amount (amt "-100.00" eur) :kontor.posting/commodity eur}
                         {:kontor.posting/display-type :note :kontor.posting/narration "footnote"}]})]
      (is (:ok? r)))))

(deftest multi-entity-mode-detected
  (testing "per (entity, ledger, commodity) sum-to-zero"
    (let [r (pv/validate
             {:transaction (header)
              :postings [{:kontor.posting/entity :acme-de :kontor.posting/account :ic-recv :kontor.posting/amount (amt "50.00" eur)  :kontor.posting/commodity eur}
                         {:kontor.posting/entity :acme-de :kontor.posting/account :rev     :kontor.posting/amount (amt "-50.00" eur) :kontor.posting/commodity eur}
                         {:kontor.posting/entity :acme-us :kontor.posting/account :ic-pay  :kontor.posting/amount (amt "50.00" eur)  :kontor.posting/commodity eur}
                         {:kontor.posting/entity :acme-us :kontor.posting/account :exp     :kontor.posting/amount (amt "-50.00" eur) :kontor.posting/commodity eur}]})]
      (is (= :multi-entity (:mode r)))
      (is (:ok? r)))))

(deftest mixed-entity-mode-rejected
  (testing "some tagged, some not → ambiguous, rejected"
    (let [r (pv/validate
             {:transaction (header)
              :postings [{:kontor.posting/entity :acme-de :kontor.posting/account :a :kontor.posting/amount (amt "50.00" eur)  :kontor.posting/commodity eur}
                         {:kontor.posting/account :b :kontor.posting/amount (amt "-50.00" eur) :kontor.posting/commodity eur}]})]
      (is (not (:ok? r)))
      (is (some #(= :mixed-entity-mode (:error %)) (:errors r))))))
