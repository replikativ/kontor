(ns kontor.payroll-mx.wage-types-test
  "Tests for the canonical MX wage-type vocabulary."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-mx.wage-types :as wt]))

(deftest registry-spans-the-task-vocabulary
  (testing "Every wage-type called out in the task is registered."
    (let [required #{:sueldo :hora-extra-doble :hora-extra-triple
                     :aguinaldo :prima-vacacional :vales-de-despensa
                     :fondo-de-ahorro :isr-retencion :imss-trabajador
                     :imss-patron :infonavit-trabajador :infonavit-patron
                     :rcv-patron :subsidio-al-empleo}]
      (doseq [w required]
        (is (contains? wt/known-codes w)
            (str "wage-type " w " must be in the registry"))))))

(deftest kinds-partition-the-vocabulary
  (testing "Every wage-type has a known kind."
    (doseq [w wt/known-codes]
      (is (contains? wt/kinds (wt/kind w))
          (str w " kind must be one of " wt/kinds)))))

(deftest codigo-agrupador-routes-match-task-spec
  (is (= "601.01" (wt/codigo-agrupador :sueldo)))
  (is (= "601.01" (wt/codigo-agrupador :hora-extra-doble)))
  (is (= "601.02" (wt/codigo-agrupador :aguinaldo)))
  (is (= "601.02" (wt/codigo-agrupador :prima-vacacional)))
  (is (= "601.05" (wt/codigo-agrupador :imss-patron)))
  (is (= "601.06" (wt/codigo-agrupador :infonavit-patron)))
  (is (= "206.04" (wt/codigo-agrupador :isr-retencion)))
  (is (= "206.05" (wt/codigo-agrupador :imss-trabajador))))

(deftest employer-only-rows-are-recognised
  (testing "Employer-only rows do NOT appear on the worker CFDI."
    (is (wt/employer-only? :imss-patron))
    (is (wt/employer-only? :infonavit-patron))
    (is (wt/employer-only? :rcv-patron))
    (is (not (wt/employer-only? :imss-trabajador)))
    (is (not (wt/employer-only? :sueldo)))))

(deftest partition-helpers-filter-correctly
  (let [rows [{:wage-type :sueldo            :amount 1000.00M}
              {:wage-type :hora-extra-doble  :amount 100.00M}
              {:wage-type :isr-retencion     :amount 50.00M}
              {:wage-type :imss-trabajador   :amount 20.00M}
              {:wage-type :subsidio-al-empleo :amount 10.00M}
              {:wage-type :imss-patron       :amount 80.00M}]]
    (is (= [:sueldo :hora-extra-doble :imss-patron]
           (mapv :wage-type (wt/percepciones rows))))
    (is (= [:isr-retencion :imss-trabajador]
           (mapv :wage-type (wt/deducciones rows))))
    (is (= [:subsidio-al-empleo]
           (mapv :wage-type (wt/otros-pagos rows))))
    (is (= [:sueldo :hora-extra-doble :isr-retencion :imss-trabajador
            :subsidio-al-empleo]
           (mapv :wage-type (wt/employee-side rows))))
    (is (= [:imss-patron] (mapv :wage-type (wt/employer-side rows))))))

(deftest unknown-wage-type-raises
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown MX wage-type"
                        (wt/lookup :totally-made-up))))
