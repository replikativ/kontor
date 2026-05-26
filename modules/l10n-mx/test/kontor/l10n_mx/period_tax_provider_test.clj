(ns kontor.l10n-mx.period-tax-provider-test
  "MX period-tax providers — ISN (Impuesto Sobre Nóminas), ISR
   personas morales, and ISR personas físicas (note 104 Stage 1)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-mx.period-tax-provider :as mx]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

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
                [{:kontor.commodity/symbol "MXN" :kontor.commodity/name "Mexican Peso"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.journal/code "PUR" :kontor.journal/type :purchase}
                 {:kontor.account/path "Gastos:Sueldos" :kontor.account/code "6010"
                  :kontor.account/type :expense}
                 {:kontor.account/path "Activo:Banco"   :kontor.account/code "1010"
                  :kontor.account/type :asset}])
    (book/buy! conn {:debit-account  [:kontor.account/path "Gastos:Sueldos"]
                     :credit-account [:kontor.account/path "Activo:Banco"]
                     :amount 500000 :commodity [:kontor.commodity/symbol "MXN"]
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

;; ============================================================================
;; ISR personas físicas — personal income tax
;; ============================================================================

(deftest isr-tarifa-converts-to-marginal-brackets
  (testing "the tarifa has 11 bands, ascending límites, open top band"
    (is (= 11 (count mx/isr-tarifa-2025)))
    (is (= 11 (count mx/isr-personal-brackets)))
    (is (apply < (map :lower mx/isr-tarifa-2025))
        "límites inferiores strictly ascending")
    (is (nil? (:upper (last mx/isr-personal-brackets)))
        "the top band is open"))
  (testing "the marginal :rate of each band is its % sobre el excedente"
    (is (= (mapv :rate mx/isr-tarifa-2025)
           (mapv :rate mx/isr-personal-brackets))))
  (testing "each band :upper is the next band's límite inferior − 0.01"
    (is (= (mapv #(- % 0.01M) (rest (map :lower mx/isr-tarifa-2025)))
           (vec (butlast (map :upper mx/isr-personal-brackets)))))))

(deftest isr-personal-golden-values
  ;; Cross-checked against the published SAT annual tarifa (Art. 152
  ;; LISR). The `progressive` schedule computes the EXACT integral of
  ;; the marginal rates; the SAT `cuota fija` form (the :formula
  ;; schedule) reproduces SAT's pre-rounded worksheet arithmetic. The
  ;; two agree to within ~3 cents — see the abstraction note in the
  ;; provider ns. Golden values pinned to the marginal-bracket form.
  (let [marginal (ts/progressive mx/isr-personal-brackets)
        sat      mx/isr-personal-cuota-fija-schedule]
    (testing "marginal-bracket schedule — exact integral"
      (is (== 96.000000M       (ts/apply-schedule marginal 5000M)))
      (is (== 2798.928448M     (ts/apply-schedule marginal 50000M)))
      (is (== 7074.820608M     (ts/apply-schedule marginal 100000M)))
      (is (== 22704.033256M    (ts/apply-schedule marginal 200000M)))
      (is (== 89487.535048M    (ts/apply-schedule marginal 500000M)))
      (is (== 233603.954896M   (ts/apply-schedule marginal 1000000M)))
      (is (== 560967.368896M   (ts/apply-schedule marginal 2000000M)))
      (is (== 1585850.295196M  (ts/apply-schedule marginal 5000000M))))
    (testing "cuota-fija :formula schedule — SAT worksheet arithmetic"
      ;; income 100000 falls in band 3 (límite 75984.56, cuota 4461.94,
      ;; 10.88%): 4461.94 + 0.1088*(100000−75984.56) = 7074.819872
      (is (== 7074.819872M  (ts/apply-schedule sat 100000M)))
      ;; income 500000 in band 7 (límite 374837.89, cuota 60049.40,
      ;; 23.52%): 60049.40 + 0.2352*(500000−374837.89) = 89487.528272
      (is (== 89487.528272M (ts/apply-schedule sat 500000M))))
    (testing "the two forms agree to within 3 cents (rounding of SAT's
              published cuota fija — see the abstraction finding)"
      (doseq [income [50000M 200000M 1000000M 2000000M 5000000M]]
        (is (> 0.04M (abs (- (ts/apply-schedule marginal income)
                             (ts/apply-schedule sat income))))
            (str "income " income))))
    (testing "zero / sub-peso income yields zero or near-zero tax"
      (is (zero? (ts/apply-schedule marginal 0M))))))

(deftest subsidio-empleo-is-a-credit
  (testing "the subsidio para el empleo builds a credit item"
    (let [c (mx/subsidio-empleo-credit)]
      (is (= :subsidio-empleo (:code c)))
      (is (= (* 12M mx/subsidio-empleo-monthly-2025) (:amount c))
          "a full year of eligibility — 12 monthly grants")))
  (testing "partial-year eligibility scales by month count"
    (is (= (* 5M mx/subsidio-empleo-monthly-2025)
           (:amount (mx/subsidio-empleo-credit 5))))
    (is (zero? (:amount (mx/subsidio-empleo-credit 0)))))
  (testing "the credit nets against gross ISR — the abstraction's
            − Σ credits step"
    ;; a low earner: 50000 taxable → ISR 2798.93; subsidio 12×475=5700
    ;; fully offsets it → after-credits liability floored at 0.
    (let [marginal (ts/progressive mx/isr-personal-brackets)
          gross    (ts/apply-schedule marginal 50000M)
          credit   (:amount (mx/subsidio-empleo-credit 12))]
      (is (< gross credit)
          "subsidio exceeds ISR for a low earner — net liability 0"))))

(deftest isr-personal-provider-config
  (let [p (mx/mx-isr-personal-provider {})]
    (is (= :mx-isr-personal (:id p)))
    (is (= :mx-sat (:authority p)))
    (is (= :MXN (:commodity p)))
    (is (= :progressive-bracket (:kontor.schedule/type (:schedule p)))
        "default schedule is the marginal-bracket progressive form")
    (is (= "Ley del ISR, Art. 152 (personas físicas)" (:statute p))))
  (testing "the cuota-fija :formula schedule can be selected explicitly"
    (let [p (mx/mx-isr-personal-provider
             {:schedule mx/isr-personal-cuota-fija-schedule})]
      (is (= :formula (:kontor.schedule/type (:schedule p)))))))

(deftest isr-personal-end-to-end
  ;; A persona física with 600000 MXN of income, 90000 of deducciones
  ;; personales (under the Art. 151 cap), claiming the subsidio.
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "MXN" :kontor.commodity/name "Mexican Peso"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.journal/code "SAL" :kontor.journal/type :sale}
                 {:kontor.account/path "Ingresos:Honorarios" :kontor.account/code "4010"
                  :kontor.account/type :income}
                 {:kontor.account/path "Activo:Banco" :kontor.account/code "1010"
                  :kontor.account/type :asset}])
    (book/sell! conn {:debit-account  [:kontor.account/path "Activo:Banco"]
                      :credit-account [:kontor.account/path "Ingresos:Honorarios"]
                      :amount 600000 :commodity [:kontor.commodity/symbol "MXN"]
                      :effective-date #inst "2026-06-30"})
    (let [provider (mx/mx-isr-personal-provider {})
          facts    (ptp/period-tax-facts
                    provider
                    {:period {:from #inst "2026-01-01"
                              :to   #inst "2027-01-01"}
                     :conn   conn
                     :inputs {:base-transform
                              {:transform/type :adjustments
                               :additions  []
                               :deductions [90000M]}
                              :credits [(mx/subsidio-empleo-credit 12)]}})
          component (first (:components facts))]
      (is (== 510000M (:amount (:base component)))
          "taxable income = 600000 gross − 90000 deducciones")
      ;; ISR on 510000 (marginal) = 91839.535048; − subsidio 5700
      (is (== 91839.535048M (:amount (:gross-liability component)))
          "gross ISR on 510000 taxable")
      (is (== 86139.535048M (:amount (ptp/total-liability facts)))
          "ISR after the subsidio para el empleo credit")
      (is (= :mx-sat (:authority component))))))
