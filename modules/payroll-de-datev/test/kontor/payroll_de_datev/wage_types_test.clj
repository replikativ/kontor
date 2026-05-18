(ns kontor.payroll-de-datev.wage-types-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-de-datev.wage-types :as wt]))

(def ^:private example-catalog
  {:catalog/version 1
   :catalog/mandant "99999"
   :catalog/berater "1234"
   :catalog/coa     :skr04
   :catalog/wage-types
   {100  {:kind :base-salary :account-hint :gehalt}
    200  {:kind :base-wage   :account-hint :lohn :uom :hours}
    300  {:kind :weihnachtsgeld :account-hint :freiwillig-st-pflichtig}
    869  {:kind :imputed-income-tax-exempt :account-hint :sachbezug-frei}
    9050 {:kind :pfaendung :account-hint :verbindlichkeiten-pfaendung}
    9100 {:kind :net-deduction :account-hint :verbindlichkeiten-pfaendung}}})

(deftest validate-catalog-accepts-valid-shape
  (is (= example-catalog (wt/validate-catalog example-catalog))))

(deftest validate-catalog-rejects-bad-coa
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid"
                        (wt/validate-catalog
                         (assoc example-catalog :catalog/coa :skr99)))))

(deftest validate-catalog-rejects-bezug-kind-on-netto-range
  ;; Lohnart 9100 is a Netto-range slot; :base-salary belongs in Bezug.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"netto-range|invalid"
                        (wt/validate-catalog
                         (-> example-catalog
                             (assoc-in [:catalog/wage-types 9100 :kind] :base-salary))))))

(deftest validate-catalog-rejects-netto-kind-on-bezug-range
  ;; Pfändung (:netto-range) can't live at Lohnart < 9000.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bezug-range|invalid"
                        (wt/validate-catalog
                         (-> example-catalog
                             (assoc-in [:catalog/wage-types 500 :kind] :pfaendung)
                             (assoc-in [:catalog/wage-types 500 :account-hint]
                                       :verbindlichkeiten-pfaendung))))))

(deftest validate-catalog-requires-kind-and-hint
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"required|invalid"
                        (wt/validate-catalog
                         (assoc-in example-catalog [:catalog/wage-types 999] {})))))

(deftest validate-catalog-allows-extra-bezug-kinds
  ;; Consumer adds :reisekosten-vergütung as a custom Bezug-side kind.
  (is (= :ok
         (let [c (-> example-catalog
                     (assoc-in [:catalog/wage-types 400 :kind] :reisekosten-vergütung)
                     (assoc-in [:catalog/wage-types 400 :account-hint] :gehalt))]
           (wt/validate-catalog c {:allow-extra-bezug-kinds #{:reisekosten-vergütung}})
           :ok))))

(deftest resolve-account-code-prefers-overrides
  (let [catalog (assoc example-catalog
                       :catalog/account-overrides {:gehalt "9999"})]
    (is (= "9999" (wt/resolve-account-code catalog :gehalt)))))

(deftest resolve-account-code-falls-back-to-defaults
  (is (= "6020" (wt/resolve-account-code example-catalog :gehalt)))
  (is (= "3720" (wt/resolve-account-code example-catalog :verb-lohn)))
  (is (= "3790" (wt/resolve-account-code example-catalog :verrechnung))))

(deftest resolve-account-code-uses-skr03-when-coa-is-skr03
  (let [catalog (assoc example-catalog :catalog/coa :skr03)]
    (is (= "4124" (wt/resolve-account-code catalog :gehalt)))
    (is (= "1740" (wt/resolve-account-code catalog :verb-lohn)))))

(deftest lookup-wage-type-works
  (is (= {:kind :base-salary :account-hint :gehalt}
         (wt/lookup-wage-type example-catalog 100)))
  (is (nil? (wt/lookup-wage-type example-catalog 9999999))))
