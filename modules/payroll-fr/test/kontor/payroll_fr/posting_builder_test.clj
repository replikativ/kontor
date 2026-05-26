(ns kontor.payroll-fr.posting-builder-test
  "Tests for the FR posting builder (PCG-keyed)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-fr.posting-builder :as pb]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Synthetic eid stand-ins (no DB needed for the per-fact build)
;; ============================================================================

(def synthetic-accounts
  {:fr-payroll-salaires           :acct-6411
   :fr-payroll-conges-payes       :acct-6412
   :fr-payroll-primes             :acct-6413
   :fr-payroll-avantages-nature   :acct-6414
   :fr-payroll-er-urssaf          :acct-6451
   :fr-payroll-er-retraite        :acct-6453
   :fr-payroll-er-assedic         :acct-6454
   :fr-payroll-er-prevoyance      :acct-6455
   :fr-payroll-conges-accrual     :acct-6412
   :fr-payroll-personnel-net      :acct-421
   :fr-payroll-acomptes           :acct-425
   :fr-payroll-oppositions        :acct-427
   :fr-payroll-urssaf             :acct-431
   :fr-payroll-retraite           :acct-4371
   :fr-payroll-pole-emploi        :acct-4373
   :fr-payroll-prevoyance         :acct-4374
   :fr-payroll-pas                :acct-4421
   :fr-payroll-conges-liability   :acct-4282})

(defn- bd-sum [postings]
  (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
            (.add a ^BigDecimal amount))
          0M postings))

(def dupont-fact
  {:employment 101
   :gross 3700M
   :net 2566.36M
   :components [{:kind :base-salary          :amount 3500M    :employer-side? false}
                {:kind :overtime             :amount 200M     :employer-side? false}
                {:kind :cotisation-urssaf    :amount -284.80M :employer-side? false}
                {:kind :csg-deductible       :amount -240.87M :employer-side? false}
                {:kind :csg-non-deductible   :amount -108.39M :employer-side? false}
                {:kind :crds                 :amount -22.58M  :employer-side? false}
                {:kind :cotisation-arrco-agirc :amount -148M  :employer-side? false}
                {:kind :medical-mutuelle     :amount -45M     :employer-side? false}
                {:kind :pas-withholding      :amount -284M    :employer-side? false}
                {:kind :employer-urssaf      :amount 1110M    :employer-side? true}
                {:kind :employer-arrco-agirc :amount 222M     :employer-side? true}
                {:kind :employer-pole-emploi :amount 148M     :employer-side? true}]
   :jurisdiction-specific-codes {:engine :silae :matricule "M-DUPONT"}})

;; ============================================================================
;; Fact → postings
;; ============================================================================

(deftest gross-debit-routes-to-pcg-641
  (testing "Gross debit lands on PCG 6411 (salaires) and 6411 (overtime)"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts
                     :commodity :eur
                     :extras-map nil
                     :etab-account-tag nil})
          salaires (filter #(= :acct-6411 (:kontor.posting/account %)) postings)
          gross-amount (bd-sum salaires)]
      ;; Base 3500 + overtime 200 routes through 6411 = 3700 DR
      (is (= 3700M gross-amount))
      (is (every? #(pos? (compare (:kontor.posting/amount %) 0M)) salaires)))))

(deftest deductions-route-to-correct-payables
  (testing "CSG / URSSAF / CRDS all credit PCG 431"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts
                     :commodity :eur})
          urssaf (filter #(= :acct-431 (:kontor.posting/account %)) postings)]
      ;; Employee URSSAF (-284.80) + CSG-ded (-240.87) + CSG-nded (-108.39)
      ;; + CRDS (-22.58) + employer URSSAF mirror (-1110)
      ;; = -1766.64 (negative because credit)
      (is (= -1766.64M (bd-sum urssaf)))))
  (testing "ARRCO/AGIRC credits PCG 4371"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur})
          retraite (filter #(= :acct-4371 (:kontor.posting/account %)) postings)]
      ;; Employee -148 + employer mirror -222 = -370
      (is (= -370M (bd-sum retraite)))))
  (testing "PAS credits PCG 4421"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur})
          pas (filter #(= :acct-4421 (:kontor.posting/account %)) postings)]
      (is (= -284M (bd-sum pas))))))

(deftest employer-side-double-leg
  (testing "Each employer-side component produces TWO legs: expense DR + payable CR"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur})
          ;; URSSAF employer expense: 6451
          urssaf-exp (filter #(= :acct-6451 (:kontor.posting/account %)) postings)]
      (is (= 1110M (bd-sum urssaf-exp)))
      ;; The mirror credit lands on 431 (URSSAF payable) — already
      ;; verified above as part of the 431 total.
      )))

(deftest net-wages-leg
  (testing "Net wages credit lands on PCG 421"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur})
          net-leg (filter #(= :acct-421 (:kontor.posting/account %)) postings)]
      (is (= -2566.36M (bd-sum net-leg))))))

(deftest balanced-per-fact
  (testing "Sum of all posting legs for a fact = 0 (kontor's sum-to-zero invariant)"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur})]
      (is (zero? (compare ^BigDecimal (bd-sum postings) 0M))))))

;; ============================================================================
;; Établissement (SIRET) tag routing
;; ============================================================================

(deftest etab-tag-attached
  (testing "When :etab-account-tag is supplied, every posting carries the tag"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur
                     :etab-account-tag "fr-etab-12345678900012"})]
      (is (every? (fn [p]
                    (some #(= [:kontor.account-tag/name "fr-etab-12345678900012"] %)
                          (:kontor.posting/account-tags p)))
                  postings))))
  (testing "When :etab-account-tag is nil, no etab tag is attached"
    (let [postings (pb/fact->postings
                    dupont-fact
                    {:accounts synthetic-accounts :commodity :eur
                     :etab-account-tag nil})]
      (is (every? (fn [p] (nil? (:kontor.posting/account-tags p))) postings)))))

;; ============================================================================
;; Vacation accrual — in-band, per ADR-079 (note 86 P2-86-3 CA pattern)
;; ============================================================================

(deftest vacation-accrual-routes-correctly
  (testing "Engine-emitted :conges-payes-accrual posts both DR 6412 + CR 4282"
    (let [fact-with-cp (update dupont-fact :components conj
                               {:kind :conges-payes-accrual
                                :amount 308.33M
                                :employer-side? true})
          postings (pb/fact->postings
                    fact-with-cp
                    {:accounts synthetic-accounts :commodity :eur})
          cp-exp (filter #(= :acct-6412 (:kontor.posting/account %)) postings)
          cp-liab (filter #(= :acct-4282 (:kontor.posting/account %)) postings)]
      (is (= 308.33M (bd-sum cp-exp)))
      (is (= -308.33M (bd-sum cp-liab)))
      ;; Still balanced
      (is (zero? (compare ^BigDecimal (bd-sum postings) 0M))))))

;; ============================================================================
;; Missing account → loud failure
;; ============================================================================

(deftest missing-account-throws
  (testing "An account-tag with no entry in :accounts throws clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"No account configured"
         (pb/fact->postings
          dupont-fact
          {:accounts (dissoc synthetic-accounts :fr-payroll-urssaf)
           :commodity :eur})))))

;; ============================================================================
;; FrPayrollPostingBuilder as the protocol impl
;; ============================================================================

(deftest fr-posting-builder-protocol
  (testing "FrPayrollPostingBuilder satisfies PayrollPostingBuilder"
    (let [builder (pb/->FrPayrollPostingBuilder {:commodity :eur})
          postings (pp/build-postings
                    builder [dupont-fact]
                    {:accounts synthetic-accounts :ledger :ledger/main})]
      (is (seq postings))
      (is (every? #(= :ledger/main (:kontor.posting/ledger %)) postings))
      (is (zero? (compare ^BigDecimal (bd-sum postings) 0M))))))

(deftest fr-posting-builder-missing-commodity
  (testing "Missing :commodity in opts throws"
    (let [builder (pb/->FrPayrollPostingBuilder {})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pp/build-postings builder [dupont-fact] {:accounts {}}))))))

;; ============================================================================
;; Multi-fact composition
;; ============================================================================

(deftest multi-fact-postings-balanced
  (testing "Multi-fact build → still net-zero across all postings"
    (let [martin-fact {:employment 102
                       :gross 2800M
                       :net 2142.30M
                       :components [{:kind :base-salary    :amount 2800M    :employer-side? false}
                                    {:kind :cotisation-urssaf :amount -227.84M :employer-side? false}
                                    {:kind :csg-deductible :amount -200.72M :employer-side? false}
                                    {:kind :csg-non-deductible :amount -90.32M :employer-side? false}
                                    {:kind :crds :amount -18.82M :employer-side? false}
                                    {:kind :pas-withholding :amount -120M :employer-side? false}
                                    {:kind :employer-urssaf :amount 888M :employer-side? true}]}
          builder (pb/->FrPayrollPostingBuilder {:commodity :eur})
          postings (pp/build-postings
                    builder [dupont-fact martin-fact]
                    {:accounts synthetic-accounts})]
      (is (zero? (compare ^BigDecimal (bd-sum postings) 0M))))))
