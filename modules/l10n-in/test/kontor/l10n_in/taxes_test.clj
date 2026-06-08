(ns kontor.l10n-in.taxes-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-in.taxes :as taxes]
            [kontor.money :as money]))

(defn- inr [s] (money/money (bigdec s) :INR))

;; ============================================================================
;; Effective-dated rate resolution (ADR-026)
;; ============================================================================

(deftest pre-cutover-uses-legacy-slabs
  (testing "2024-06-15 (pre-GST-2.0) resolves against 5/12/18/28 stack"
    (let [slabs (taxes/slabs-effective-on #inst "2024-06-15")]
      (is (= 0.05M  (:tier-1 slabs)))
      (is (= 0.12M  (:tier-2 slabs)))
      (is (= 0.18M  (:tier-3 slabs)))
      (is (= 0.28M  (:tier-4 slabs)))
      (is (not (contains? slabs :standard))
          "Post-GST-2.0 keys must NOT appear in the legacy stack"))))

(deftest post-cutover-uses-current-slabs
  (testing "2026-01-01 (post-GST-2.0) resolves against 0/0.25/3/5/18/40 stack"
    (let [slabs (taxes/slabs-effective-on #inst "2026-01-01")]
      (is (= 0.05M  (:essentials slabs)))
      (is (= 0.18M  (:standard slabs)))
      (is (= 0.40M  (:luxury slabs)))
      (is (not (contains? slabs :tier-4))
          "Pre-GST-2.0 keys must NOT appear in the current stack"))))

(deftest cutover-boundary-is-precise
  (testing "00:00 IST 2025-09-22 is the first instant of the new stack"
    (is (= 0.18M (taxes/resolve-rate #inst "2025-09-22T00:00:00+05:30" :standard))
        "The cutover instant itself resolves to the new stack")
    (is (= 0.18M (taxes/resolve-rate #inst "2025-09-21T23:59:59+05:30" :tier-3))
        "One second before the cutover: pre-GST-2.0 tier-3 (18%)")
    (is (thrown? clojure.lang.ExceptionInfo
                 (taxes/resolve-rate #inst "2025-09-21T23:59:59+05:30" :standard))
        ":standard is a post-GST-2.0-only key")))

;; ============================================================================
;; Place-of-supply dispatch (ADR-023)
;; ============================================================================

(deftest intra-state-supply
  (testing "Maharashtra supplier → Maharashtra POS: CGST + SGST"
    (is (= :intra-state (taxes/dispatch-supply "MH" "MH" false)))))

(deftest inter-state-supply
  (testing "Maharashtra supplier → Karnataka POS: IGST"
    (is (= :inter-state (taxes/dispatch-supply "MH" "KA" false)))))

(deftest ut-supply-without-legislature
  (testing "Chandigarh supplier → Chandigarh POS (intra-UT, no
            legislature): CGST + UTGST. UTGST replaces SGST only for
            intra-UT supplies in UTs without a legislature."
    (is (= :ut-supply (taxes/dispatch-supply "CH" "CH" true)))))

(deftest delhi-puducherry-use-sgst-despite-being-uts
  (testing "Delhi has a legislature; POS=DL with `pos-is-ut? false`
            (the caller knows DL/PY have legislatures and passes
            false): dispatch falls to intra-state when supplier is
            also Delhi"
    (is (= :intra-state (taxes/dispatch-supply "DL" "DL" false))
        "Delhi-to-Delhi: SGST not UTGST")))

(deftest inter-state-trumps-ut-flag
  (testing "Inter-state always wins — UT-flag only matters when intra"
    (is (= :inter-state (taxes/dispatch-supply "MH" "CH" true))
        "MH→CH is inter-state IGST even though CH is a UT")))

;; ============================================================================
;; Component split
;; ============================================================================

(deftest split-18-pct-intra-state
  (let [c (taxes/component-split :intra-state 0.18M)]
    (is (= 0.09M (:cgst c)))
    (is (= 0.09M (:sgst c)))
    (is (not (contains? c :igst)))
    (is (not (contains? c :utgst)))))

(deftest split-18-pct-inter-state
  (let [c (taxes/component-split :inter-state 0.18M)]
    (is (= 0.18M (:igst c)))
    (is (= 1 (count c))
        "Inter-state has only IGST")))

(deftest split-18-pct-ut-supply
  (let [c (taxes/component-split :ut-supply 0.18M)]
    (is (= 0.09M (:cgst c)))
    (is (= 0.09M (:utgst c)))
    (is (not (contains? c :sgst)))))

(deftest split-non-divisible-rate
  (testing "5% intra-state: 2.5% CGST + 2.5% SGST (4-decimal precision)"
    (let [c (taxes/component-split :intra-state 0.05M)]
      (is (= 0.0250M (:cgst c)))
      (is (= 0.0250M (:sgst c))))))

;; ============================================================================
;; compute-tax end-to-end
;; ============================================================================

(deftest compute-18pct-intra-state-on-1000-eur-base
  (testing "₹1000 base, 18% intra-state → ₹90 CGST + ₹90 SGST"
    (let [r (taxes/compute-tax (inr "1000.00") 0.18M :intra-state)]
      (is (money/equiv? (inr "90.00") (-> r :components :cgst)))
      (is (money/equiv? (inr "90.00") (-> r :components :sgst)))
      (is (money/equiv? (inr "180.00") (:total r))))))

(deftest compute-18pct-inter-state
  (testing "₹1000 base, 18% inter-state → ₹180 IGST"
    (let [r (taxes/compute-tax (inr "1000.00") 0.18M :inter-state)]
      (is (money/equiv? (inr "180.00") (-> r :components :igst)))
      (is (money/equiv? (inr "180.00") (:total r))))))

(deftest compute-with-cess
  (testing "₹1000 base, 40% luxury + 12% aerated-drinks cess inter-state"
    (let [r (taxes/compute-tax (inr "1000.00") 0.40M :inter-state 0.12M)]
      (is (money/equiv? (inr "400.00") (-> r :components :igst)))
      (is (money/equiv? (inr "120.00") (:cess r)))
      (is (money/equiv? (inr "520.00") (:total r))))))

(deftest compute-5pct-essentials-intra
  (testing "Rounding: ₹100.00 × 2.5% = ₹2.50 each side"
    (let [r (taxes/compute-tax (inr "100.00") 0.05M :intra-state)]
      (is (money/equiv? (inr "2.50") (-> r :components :cgst)))
      (is (money/equiv? (inr "2.50") (-> r :components :sgst))))))
