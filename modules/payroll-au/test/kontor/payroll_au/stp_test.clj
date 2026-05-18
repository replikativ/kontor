(ns kontor.payroll-au.stp-test
  "Stage R C6 — STP Phase 2 event structure tests (ADR-080).

   Covers:
     - payee-payload: per-employee period totals + YTD carry +
       STP income-type disaggregation.
     - pay-event: aggregate envelope with total-gross + total-PAYGW
       cross-check.
     - update-event marker.
     - facts->payees walk function.
     - TFN structural validator (algorithmic, mod-11)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-au.stp :as stp]
            [kontor.payroll-au.wage-types :as wt])
  (:import [java.math BigDecimal]))

(def fact-1
  {:employment 1001
   :gross 6500M
   :net 4650M
   :components [{:kind :ordinary-time-earnings :amount 6500M :employer-side? false}
                {:kind :paygw :amount -1200M :employer-side? false}
                {:kind :salary-sacrifice-super :amount -650M :employer-side? false}
                {:kind :superannuation-guarantee-employer
                 :amount 747.50M :employer-side? true}]})

(def payee-1
  {:tfn "123456782"
   :given-name "Alice"
   :family-name "Outback"
   :employee-id "E101"
   :tax-treatment-code "RTXXXX"
   :date-of-birth #inst "1985-03-12"
   :address {:line-1 "1 George St" :suburb "Sydney" :state "NSW" :postcode "2000"}})

;; ============================================================================
;; payee-payload
;; ============================================================================

(deftest payee-payload-disaggregates-income-types
  (let [income-type-fn (fn [k] (wt/stp2-income-type k))
        payload (stp/payee-payload {:payee payee-1
                                    :fact fact-1
                                    :income-type-fn income-type-fn
                                    :ytd {:gross 78000M
                                          :ote 78000M
                                          :paygw 14400M
                                          :super 8970M}})]
    (testing "core payee identity fields are present"
      (is (= "123456782" (:stp.payee/tfn payload)))
      (is (= "Alice"     (:stp.payee/given-name payload)))
      (is (= "Outback"   (:stp.payee/family-name payload)))
      (is (= "E101"      (:stp.payee/employee-id payload)))
      (is (= "RTXXXX"    (:stp.payee/tax-treatment-code payload))))
    (testing "period totals carry the right disaggregation"
      (let [pt (:stp.payee/period-totals payload)]
        (is (= "6500.00" (:stp/ote pt)))
        (is (= "1200.00" (:stp/paygw pt)))
        (is (= "650.00"  (:stp/salary-sacrifice-s pt)))
        (is (= "747.50"  (:stp/super-guarantee pt)))))
    (testing "YTD totals are formatted to two decimal places"
      (let [ytd (:stp.payee/ytd payload)]
        (is (= "78000.00" (:stp.ytd/gross ytd)))
        (is (= "78000.00" (:stp.ytd/ote ytd)))
        (is (= "14400.00" (:stp.ytd/paygw ytd)))
        (is (= "8970.00"  (:stp.ytd/super ytd)))))
    (testing "date-of-birth + address pass through"
      (is (string? (:stp.payee/date-of-birth payload)))
      (is (map? (:stp.payee/address payload))))))

(deftest payee-payload-requires-mandatory-keys
  (testing "missing :payee throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":payee required"
                          (stp/payee-payload
                           {:fact fact-1
                            :income-type-fn (constantly :ote)}))))
  (testing "missing :fact throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fact required"
                          (stp/payee-payload
                           {:payee payee-1
                            :income-type-fn (constantly :ote)}))))
  (testing "missing :income-type-fn throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":income-type-fn required"
                          (stp/payee-payload {:payee payee-1 :fact fact-1})))))

;; ============================================================================
;; pay-event aggregation
;; ============================================================================

(deftest pay-event-aggregates-totals-from-payees
  (let [income-type-fn (fn [k] (wt/stp2-income-type k))
        payee (stp/payee-payload {:payee payee-1
                                  :fact fact-1
                                  :income-type-fn income-type-fn})
        event (stp/pay-event {:abn "33051775556"
                              :pay-period-start #inst "2026-05-01"
                              :pay-period-end #inst "2026-05-31"
                              :submission-date #inst "2026-06-01"
                              :pay-date #inst "2026-05-31"
                              :payees [payee]
                              :submission-id "SUB-1"
                              :bms-id "KONTOR-AU-2.0"})]
    (testing "envelope fields carry through"
      (is (= "2.0" (:stp.event/version event)))
      (is (= "33051775556" (:stp.event/abn event)))
      (is (= "AU"  (:stp.event/country-code event)))
      (is (= "001" (:stp.event/branch-code event)))
      (is (= 1     (:stp.event/payee-count event)))
      (is (= "SUB-1" (:stp.event/submission-id event)))
      (is (= "KONTOR-AU-2.0" (:stp.event/bms-id event))))
    (testing "aggregate totals match the single payee"
      (is (= "6500.00" (:stp.event/total-gross event)))
      (is (= "1200.00" (:stp.event/total-paygw event))))
    (testing "default flags are false"
      (is (false? (:stp.event/final-event? event)))
      (is (false? (:stp.event/update-event? event))))))

(deftest pay-event-requires-mandatory-keys
  (testing "missing :abn throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":abn required"
                          (stp/pay-event {:pay-period-start #inst "2026-05-01"
                                          :pay-period-end #inst "2026-05-31"
                                          :submission-date #inst "2026-06-01"
                                          :pay-date #inst "2026-05-31"
                                          :payees [{:foo :bar}]}))))
  (testing "empty :payees throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":payees"
                          (stp/pay-event {:abn "33051775556"
                                          :pay-period-start #inst "2026-05-01"
                                          :pay-period-end #inst "2026-05-31"
                                          :submission-date #inst "2026-06-01"
                                          :pay-date #inst "2026-05-31"
                                          :payees []})))))

(deftest update-event-flag-set
  (let [income-type-fn (fn [k] (wt/stp2-income-type k))
        payee (stp/payee-payload {:payee payee-1
                                  :fact fact-1
                                  :income-type-fn income-type-fn})
        event (stp/update-event {:abn "33051775556"
                                 :pay-period-start #inst "2026-05-01"
                                 :pay-period-end #inst "2026-05-31"
                                 :submission-date #inst "2026-06-15"
                                 :pay-date #inst "2026-06-15"
                                 :payees [payee]})]
    (testing ":update-event? is true (per BIG §9.2)"
      (is (true? (:stp.event/update-event? event))))))

(deftest summary-string-is-human-readable
  (let [income-type-fn (fn [k] (wt/stp2-income-type k))
        payee (stp/payee-payload {:payee payee-1
                                  :fact fact-1
                                  :income-type-fn income-type-fn})
        event (stp/pay-event {:abn "33051775556"
                              :pay-period-start #inst "2026-05-01"
                              :pay-period-end #inst "2026-05-31"
                              :submission-date #inst "2026-06-01"
                              :pay-date #inst "2026-05-31"
                              :payees [payee]})
        s (stp/pay-event->summary-string event)]
    (testing "summary surfaces the key totals"
      (is (re-find #"ABN 33051775556" s))
      (is (re-find #"6500\.00" s))
      (is (re-find #"1200\.00" s))
      (is (re-find #"1 payee" s)))))

;; ============================================================================
;; facts->payees
;; ============================================================================

(deftest facts-to-payees-walk
  (let [payees-info {1001 (assoc payee-1 :ytd {:gross 78000M :paygw 14400M})}
        income-type-fn (fn [k] (wt/stp2-income-type k))
        payees (stp/facts->payees {:facts [fact-1]
                                   :payees-info payees-info
                                   :income-type-fn income-type-fn})]
    (testing "one payee payload per fact"
      (is (= 1 (count payees))))
    (testing "YTD passes through from payees-info"
      (let [ytd (:stp.payee/ytd (first payees))]
        (is (= "78000.00" (:stp.ytd/gross ytd)))))))

(deftest facts-to-payees-throws-on-missing-info
  (let [income-type-fn (fn [k] (wt/stp2-income-type k))]
    (testing "missing :payees-info entry throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No :payees-info"
                            (stp/facts->payees {:facts [fact-1]
                                                :payees-info {}
                                                :income-type-fn income-type-fn}))))))

;; ============================================================================
;; TFN validator
;; ============================================================================

(deftest tfn-mod-11-check-on-known-good-and-bad
  (testing "ATO's published test TFN 123 456 782 validates"
    ;; Per the ATO TFN algorithm published reference: 123456782
    ;; 1*1 + 2*4 + 3*3 + 4*7 + 5*5 + 6*8 + 7*6 + 8*9 + 2*10
    ;; = 1 + 8 + 9 + 28 + 25 + 48 + 42 + 72 + 20 = 253; 253 mod 11 = 0
    (is (true? (stp/valid-tfn? "123456782")))
    (is (true? (stp/valid-tfn? "123 456 782")))
    (is (true? (stp/valid-tfn? "123-456-782"))))
  (testing "a corrupted TFN fails"
    (is (false? (stp/valid-tfn? "123456789")))
    (is (false? (stp/valid-tfn? "000000000"))))
  (testing "non-strings + wrong-length return false"
    (is (false? (stp/valid-tfn? nil)))
    (is (false? (stp/valid-tfn? 123456782)))
    (is (false? (stp/valid-tfn? "")))
    (is (false? (stp/valid-tfn? "1")))))

(deftest assert-tfn-throws-on-failure
  (testing "valid TFN passes through"
    (is (= "123456782" (stp/assert-tfn! "123456782"))))
  (testing "invalid TFN throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid TFN"
                          (stp/assert-tfn! "111111111")))))
