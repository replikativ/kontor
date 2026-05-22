(ns kontor.l10n-mx.period-tax-provider-test
  "Iteration 3 — MX ISN (Impuesto Sobre Nóminas) period-tax provider."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-mx.period-tax-provider :as mx]
            [kontor.period-tax-provider :as ptp]))

(deftest isn-rate-by-state
  (testing "known states resolve from isn-rates"
    (is (= 0.03M (:rate (:schedule (mx/mx-isn-provider {:state :cdmx})))))
    (is (= 0.02M (:rate (:schedule (mx/mx-isn-provider {:state :jalisco}))))))
  (testing "an unknown state falls back to the 3% default"
    (is (= 0.03M (:rate (:schedule (mx/mx-isn-provider {:state :sonora}))))))
  (testing "an explicit :rate overrides the table"
    (is (= 0.025M (:rate (:schedule (mx/mx-isn-provider
                                     {:state :cdmx :rate 0.025M}))))))
  (is (= :MXN (:commodity (mx/mx-isn-provider {:state :cdmx})))))

(deftest isn-end-to-end
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "MXN" :commodity/name "Mexican Peso"
                  :commodity/precision 2}
                 {:journal/code "GEN" :journal/type :general}
                 {:journal/code "PUR" :journal/type :purchase}
                 {:account/path "Gastos:Sueldos" :account/code "6010"
                  :account/type :expense}
                 {:account/path "Activo:Banco"   :account/code "1010"
                  :account/type :asset}])
    (book/buy! conn {:debit-account  [:account/path "Gastos:Sueldos"]
                     :credit-account [:account/path "Activo:Banco"]
                     :amount 500000 :commodity [:commodity/symbol "MXN"]
                     :effective-date #inst "2026-03-31"})
    (let [provider (mx/mx-isn-provider {:state :cdmx :wage-codes ["6010"]})
          facts    (ptp/period-tax-facts
                    provider {:period {:from #inst "2026-01-01"
                                       :to   #inst "2027-01-01"}
                              :conn conn})]
      (is (== 15000M (:amount (ptp/total-liability facts)))
          "CDMX ISN — 3% of the 500000 nómina gravable")
      (is (= :mx-state/cdmx (:authority (first (:components facts))))))))

(deftest isr-corporate-is-flat-30pct
  (let [p (mx/mx-isr-corporate-provider {})]
    (is (= 0.30M (:rate p)) "ISR personas morales — flat 30%")
    (is (= :MXN (:commodity p)))
    (is (= :mx-isr-corporate (:id p)))
    (is (= :mx-sat (:authority p)))))
