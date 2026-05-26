(ns kontor.payroll-de-datev.posting-builder-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-de-datev.posting-builder :as pb]
            [kontor.payroll-de-datev.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

(def ^:private catalog
  (wt/validate-catalog
   {:catalog/version 1
    :catalog/mandant "99999"
    :catalog/berater "1234"
    :catalog/coa     :skr04
    :catalog/wage-types
    {100 {:kind :base-salary :account-hint :gehalt}
     200 {:kind :base-wage   :account-hint :lohn :uom :hours}
     300 {:kind :weihnachtsgeld :account-hint :freiwillig-st-pflichtig}}}))

(def ^:private eur [:kontor.commodity/symbol "EUR"])

(def ^:private sample-fact
  {:employment "emp-1"
   :employment-pnr "3011"
   :pay-period "11/2025"
   :gross 4000M
   :net 2500M
   :withholding-tax 700M
   :employee-si 800M
   :employer-si 800M
   :components [{:kind :base-salary :amount 4000M :account-hint :gehalt}
                {:kind :withholding-tax :amount -700M}
                {:kind :employee-si :amount -800M}
                {:kind :employer-si :amount 800M :employer-side? true
                 :account-hint :soziale-aufwendungen}]})

;; ============================================================================
;; account resolution
;; ============================================================================

(deftest resolve-account-prefers-explicit-override
  (is (= [:account/code "9999"]
         (pb/resolve-account-ref
          {:catalog catalog :accounts {:gehalt [:account/code "9999"]}}
          :gehalt))))

(deftest resolve-account-falls-back-to-catalog-defaults
  (is (= [:account/code "6020"]
         (pb/resolve-account-ref {:catalog catalog :accounts {}} :gehalt)))
  (is (= [:account/code "3720"]
         (pb/resolve-account-ref {:catalog catalog :accounts {}} :verb-lohn))))

;; ============================================================================
;; build-postings — full Bruttomethode
;; ============================================================================

(deftest build-postings-emits-bruttomethode-rows
  (let [builder (pb/make-builder {:catalog catalog :commodity eur})
        postings (pp/build-postings builder [sample-fact]
                                    {:accounts {} :ledger nil :fx-provider nil})]
    (testing "emits 10 legs for the standard 5-pair Bruttomethode"
      ;; gross expense (2), withholding (2), employee-si (2), net (2), employer-si (2)
      (is (= 10 (count postings))))
    (testing "every leg uses EUR commodity + BigDecimal amounts"
      (is (every? #(= eur (:posting/commodity %)) postings))
      (is (every? #(instance? BigDecimal (:posting/amount %)) postings)))
    (testing "amounts sum to zero (balanced)"
      (let [sum (reduce (fn [^BigDecimal a {:posting/keys [amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "Verrechnung (3790) net-zero per fact"
      (let [verr-sum (->> postings
                          (filter #(= [:account/code "3790"] (:posting/account %)))
                          (map :posting/amount)
                          (reduce (fn [^BigDecimal a ^BigDecimal v] (.add a v)) 0M))]
        (is (zero? (.compareTo ^BigDecimal verr-sum 0M)))))
    (testing "gross expense lands on 6020 (Gehälter)"
      (let [gross-dr (->> postings
                          (filter #(= [:account/code "6020"] (:posting/account %)))
                          (map :posting/amount))]
        (is (= [4000.00M] gross-dr))))
    (testing "employer SI lands on 6110 (Soziale Aufwendungen)"
      (let [agsv (->> postings
                      (filter #(= [:account/code "6110"] (:posting/account %)))
                      (map :posting/amount))]
        (is (= [800.00M] agsv))))
    (testing "verb-lohn (3720) carries the net as credit"
      (let [vl (->> postings
                    (filter #(= [:account/code "3720"] (:posting/account %)))
                    (map :posting/amount))]
        (is (= [-2500.00M] vl))))
    (testing "verb-lohnsteuer (3730) carries the withholding"
      (let [vlst (->> postings
                      (filter #(= [:account/code "3730"] (:posting/account %)))
                      (map :posting/amount))]
        (is (= [-700.00M] vlst))))))

(deftest build-postings-skr03-routes-to-skr03-accounts
  (let [skr03-catalog (wt/validate-catalog
                       (assoc catalog :catalog/coa :skr03))
        builder (pb/make-builder {:catalog skr03-catalog :commodity eur})
        postings (pp/build-postings builder [sample-fact]
                                    {:accounts {} :ledger nil :fx-provider nil})
        codes (set (map (comp second :posting/account) postings))]
    (testing "SKR03 codes appear in place of SKR04"
      (is (contains? codes "4124"))  ; Gehälter SKR03
      (is (contains? codes "1755"))  ; Verb. SV / Verrechnung
      (is (contains? codes "1740"))  ; Verb. Lohn
      (is (contains? codes "1741"))  ; Verb. LSt
      (is (contains? codes "4130"))  ; Soziale Aufwendungen
      (is (not (contains? codes "6020"))))))

(deftest build-postings-uses-ledger-when-supplied
  (let [builder (pb/make-builder {:catalog catalog :commodity eur})
        postings (pp/build-postings builder [sample-fact]
                                    {:accounts {} :ledger :de-handelsrecht
                                     :fx-provider nil})]
    (is (every? #(= :de-handelsrecht (:posting/ledger %)) postings))))

;; ============================================================================
;; Urlaubsrückstellung — HGB §249 simplified accrual
;; ============================================================================

(deftest urlaubsrueckstellung-amount-handelsbilanz-formula
  ;; Annual gross 60000, AG-SV 21%, 220 Arbeitstage, 10 days accrued.
  ;; Expected: ((60000 + 12600) / 220) * 10 = 3300.00
  (let [amt (pb/urlaubsrueckstellung-amount
             {:annual-gross 60000M
              :accrued-vacation-days 10M
              :framework :hgb-handelsbilanz})]
    (is (= 3300.00M amt))))

(deftest urlaubsrueckstellung-amount-steuerbilanz-formula
  ;; Annual gross 60000 (no AG-SV under Steuerbilanz default), 250 Tage,
  ;; 10 days. Expected: 60000 / 250 * 10 = 2400.00
  (let [amt (pb/urlaubsrueckstellung-amount
             {:annual-gross 60000M
              :accrued-vacation-days 10M
              :framework :de-steuerbilanz})]
    (is (= 2400.00M amt))))

(deftest urlaubsrueckstellung-amount-with-urlaubsgeld
  ;; annual-gross 60000; ag-sv add = 12600 (21% of 60000);
  ;; urlaubsgeld add = 3000 (5% of 60000); total cost = 75600.
  ;; tagessatz = 75600 / 220 = 343.6363...; *10 = 3436.36.
  (let [amt (pb/urlaubsrueckstellung-amount
             {:annual-gross 60000M
              :accrued-vacation-days 10M
              :framework :hgb-handelsbilanz
              :include-urlaubsgeld? true})]
    (is (= 3436.36M amt))))

(deftest urlaubsrueckstellung-tx-data-builds-balanced-pair
  (let [postings (pb/urlaubsrueckstellung-tx-data
                  {:amount 3300.00M
                   :commodity eur
                   :catalog catalog
                   :ledger :de-handelsrecht
                   :narration "Urlaubsrückstellung 2026-12-31"})]
    (is (= 2 (count postings)))
    (is (= [:account/code "6035"]
           (-> postings first :posting/account)))    ; aufw
    (is (= [:account/code "3066"]
           (-> postings second :posting/account)))   ; rückstellung
    (is (= 3300.00M  (-> postings first  :posting/amount)))
    (is (= -3300.00M (-> postings second :posting/amount)))
    (is (every? #(= :de-handelsrecht (:posting/ledger %)) postings))))
