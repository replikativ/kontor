(ns kontor.cgt-test
  "Tests for `kontor.cgt` — the CGT → PIT/CIT composition helper
   shipped per ADR-103 Addendum 1 (note 137 §6)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.cgt :as cgt]))

;; ============================================================================
;; Synthetic CGT facts — small enough to assert without setup
;; ============================================================================

(def ^:private synthetic-cgt-facts
  "A pretend CGT TaxReturnFacts with four components covering each
   of the four composition lanes we want the helper to handle."
  {:entity 42
   :period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
   :components
   [;; A standalone-tax component with no fold.
    {:kind :capital-gains-tax
     :base {:amount 100000M :commodity :USD}
     :liability {:amount 15000M :commodity :USD}
     :jurisdiction-specific-codes {:lane :lt}}

    ;; A PIT-base-additions component (US ST → ordinary).
    {:kind :capital-gains-tax
     :base {:amount 8000M :commodity :USD}
     :liability {:amount 0M :commodity :USD}
     :jurisdiction-specific-codes {:lane :st
                                   :pit-base-additions [8000M]}}

    ;; A PIT-base-deductions component (CA ABIL — flat vector).
    {:kind :capital-gains-tax
     :base {:amount 0M :commodity :CAD}
     :liability {:amount 0M :commodity :CAD}
     :jurisdiction-specific-codes {:lane :ca-abil
                                   :pit-base-deductions [5000M]}}

    ;; A CIT-base-additions component (DE §8b 5% addback).
    {:kind :capital-gains-tax
     :base {:amount 0M :commodity :EUR}
     :liability {:amount 0M :commodity :EUR}
     :jurisdiction-specific-codes {:lane :de-§8b
                                   :cit-base-additions [200000M]}}]})

;; ============================================================================
;; §1. fold-into-base-transform
;; ============================================================================

(deftest fold-pit-additions-and-deductions
  (testing "PIT fold gathers vector additions + vector deductions across components"
    (let [bt (cgt/fold-into-base-transform synthetic-cgt-facts :pit)]
      (is (= :adjustments        (:transform/type bt)))
      (is (= [8000M]             (:additions bt)))
      (is (= [5000M]             (:deductions bt))))))

(deftest fold-cit-additions
  (testing "CIT fold only sees CIT-tagged keys"
    (let [bt (cgt/fold-into-base-transform synthetic-cgt-facts :cit)]
      (is (= :adjustments  (:transform/type bt)))
      (is (= [200000M]     (:additions bt)))
      (is (nil?            (:deductions bt))
          "no CIT deductions present in synthetic facts"))))

(deftest fold-returns-nil-when-no-folds-present
  (testing "no additions or deductions → nil (consumer skips :base-transform)"
    (let [facts {:components [{:kind :capital-gains-tax
                               :base {:amount 100000M :commodity :USD}
                               :liability {:amount 15000M :commodity :USD}
                               :jurisdiction-specific-codes {:lane :lt}}]}]
      (is (nil? (cgt/fold-into-base-transform facts :pit))))))

(deftest fold-rejects-bad-pit-or-cit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be :pit or :cit"
                        (cgt/fold-into-base-transform synthetic-cgt-facts :bogus))))

(deftest fold-skips-tagged-map-shape
  (testing "AT's :pit-base-deductions {:§28-vermietung [...]} tagged-map shape is skipped"
    (let [facts {:components
                 [{:kind :capital-gains-tax
                   :base {:amount 0M :commodity :EUR}
                   :liability {:amount 0M :commodity :EUR}
                   :jurisdiction-specific-codes
                   {:lane :at-immoest
                    :pit-base-deductions {:§28-vermietung [12000M]}}}]}]
      (is (nil? (cgt/fold-into-base-transform facts :pit))
          "tagged-map is NOT folded — consumer wires AT-specific routing"))))

;; ============================================================================
;; §2. fold-into-inputs
;; ============================================================================

(deftest fold-into-inputs-merges-extras
  (testing "fold-into-inputs merges :base-transform with extra inputs"
    (let [inputs (cgt/fold-into-inputs synthetic-cgt-facts :pit
                                       {:tax-unit {:filing-status :single}
                                        :credits  [{:code :ctc :amount 2000M}]})]
      (is (= :adjustments (get-in inputs [:base-transform :transform/type])))
      (is (= [8000M] (get-in inputs [:base-transform :additions])))
      (is (= [5000M] (get-in inputs [:base-transform :deductions])))
      (is (= :single (get-in inputs [:tax-unit :filing-status]))
          "tax-unit pass-through preserved")
      (is (= 1 (count (:credits inputs)))
          "credits pass-through preserved"))))

(deftest fold-into-inputs-omits-base-transform-when-no-folds
  (testing "no folds → no :base-transform key (clean inputs)"
    (let [facts  {:components []}
          inputs (cgt/fold-into-inputs facts :pit {:tax-unit {:filing-status :single}})]
      (is (not (contains? inputs :base-transform)))
      (is (= :single (get-in inputs [:tax-unit :filing-status]))))))

;; ============================================================================
;; §3. components-by-lane + total-liability
;; ============================================================================

(deftest components-by-lane-groups-correctly
  (let [grouped (cgt/components-by-lane synthetic-cgt-facts)]
    (is (= #{:lt :st :ca-abil :de-§8b} (set (keys grouped))))
    (is (= 1 (count (:lt grouped))))
    (is (= 100000M (-> grouped :lt first :base :amount)))))

(deftest total-liability-sums-across-components
  (testing "sums :liability :amount across all components"
    (is (== 15000M (cgt/total-liability synthetic-cgt-facts))
        "only the LT component has a non-zero liability in synthetic facts")))
