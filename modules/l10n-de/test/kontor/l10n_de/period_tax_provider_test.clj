(ns kontor.l10n-de.period-tax-provider-test
  "Iteration 5 — DE Einkommensteuer (§32a) period-tax provider."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-de.period-tax-provider :as de]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

(deftest est-32a-faithful
  (let [s (:schedule (de/de-income-tax-provider {}))]
    (testing "the Grundfreibetrag — no tax up to €11,604"
      (is (zero? (ts/apply-schedule s 11604M)))
      (is (zero? (ts/apply-schedule s 8000M))))
    (testing "§32a is continuous across every zone boundary"
      (doseq [b [17005M 66760M 277825M]]
        (is (>= 1 (- (ts/apply-schedule s (inc b))
                     (ts/apply-schedule s b)))
            (str "no jump at the §32a zone boundary " b))))
    (testing "known values — the real piecewise-polynomial formula"
      (is (== 7495M   (ts/apply-schedule s 40000M)))
      (is (== 116063M (ts/apply-schedule s 300000M))))
    (testing "monotone increasing"
      (is (apply < (map #(ts/apply-schedule s %)
                        [20000M 50000M 150000M 400000M]))))))

(deftest est-with-soli-end-to-end
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:kontor.account/path "Income:Gehalt" :kontor.account/type :income}
                 {:kontor.account/path "Aktiva:Bank"   :kontor.account/type :asset}])
    (book/sell! conn {:debit-account  [:kontor.account/path "Aktiva:Bank"]
                      :credit-account [:kontor.account/path "Income:Gehalt"]
                      :amount 120000 :commodity [:kontor.commodity/symbol "EUR"]
                      :effective-date #inst "2026-06-30"})
    (let [facts (ptp/period-tax-facts
                 (de/de-income-tax-provider {})
                 {:period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                  :conn conn})
          [c]   (:components facts)]
      ;; §32a(120000) = 0.42×120000 − 10602.13 = 39797.87 → floor 39797
      (is (== 39797M (:amount (:gross-liability c))) "§32a Einkommensteuer")
      ;; Soli = min(5.5%×39797, 11.9%×(39797−18130)) = 2188.835
      (is (= 1 (count (:surtaxes c))) "the Solidaritätszuschlag")
      (is (== 41985.835M (:amount (:liability c))) "Einkommensteuer + Soli"))))
