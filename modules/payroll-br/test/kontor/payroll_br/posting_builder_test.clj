(ns kontor.payroll-br.posting-builder-test
  "Tests for BrPayrollPostingBuilder — rubrica → CoA mapping, CNPJ
   routing, employer-side leg pairs."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-br.posting-builder :as pb]
            [kontor.payroll-provider :as pp]))

(def accounts
  "Synthetic eid-shaped account map. Real consumers feed eids from
   (d/q ... :account/code) lookups."
  {:br-payroll-wages              :acct/wages
   :br-payroll-er-inss            :acct/er-inss
   :br-payroll-er-fgts            :acct/er-fgts
   :br-payroll-er-charges         :acct/er-charges
   :br-payroll-ferias-accrual     :acct/ferias-accrual
   :br-payroll-13th-accrual       :acct/thirteenth-accrual
   :br-payroll-severance-accrual  :acct/severance-accrual
   :br-payroll-inss-employee      :acct/inss-employee
   :br-payroll-inss-employer      :acct/inss-employer
   :br-payroll-fgts               :acct/fgts
   :br-payroll-irrf               :acct/irrf
   :br-payroll-union-dues         :acct/union-dues
   :br-payroll-garnishment        :acct/garnishment
   :br-payroll-other-deduction    :acct/other-deduction
   :br-payroll-vr-vt              :acct/vr-vt
   :br-payroll-benefits           :acct/benefits
   :br-payroll-net-wages          :acct/net-wages
   :br-payroll-ferias-liability   :acct/ferias-liability
   :br-payroll-13th-liability     :acct/thirteenth-liability
   :br-payroll-severance-liability :acct/severance-liability})

(def commodity :kontor.commodity/brl)

(defn- jane-fact
  "A representative balanced BR payroll fact, $5000 base, INSS+IRRF
   employee deductions, employer INSS (CPP 20%) + FGTS (8%)."
  []
  {:employment :emp/jane
   :gross 5000M
   :net 4352.50M
   :components [{:kind :base-wage      :amount 5000M     :employer-side? false}
                {:kind :inss-employee  :amount -400M     :employer-side? false}
                {:kind :irrf-employee  :amount -247.50M  :employer-side? false}
                {:kind :inss-employer  :amount 1000M     :employer-side? true}
                {:kind :fgts-employer  :amount 400M      :employer-side? true}]
   :jurisdiction-specific-codes {:engine :test}})

(defn- build [opts fact]
  (let [builder (pb/->BrPayrollPostingBuilder
                 (merge {:commodity commodity} opts))]
    (pp/build-postings builder [fact] {:accounts accounts})))

(deftest postings-balance-to-zero
  (let [postings (build {} (jane-fact))
        sum (reduce (fn [a {:keys [posting/amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (testing "All legs sum to zero (the substrate's posting invariant)"
      (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))))

(deftest one-wages-debit-for-gross
  (let [postings (build {} (jane-fact))
        wages-leg (first (filter #(= :acct/wages (:posting/account %)) postings))]
    (testing "Single wages-expense leg for the gross"
      (is (= 5000M (:posting/amount wages-leg))))))

(deftest deduction-legs-target-statutory-payable-buckets
  (let [postings (build {} (jane-fact))
        by-acct (group-by :posting/account postings)]
    (testing "INSS empregado credited to :acct/inss-employee (distinct bucket!)"
      (let [legs (get by-acct :acct/inss-employee)]
        (is (= 1 (count legs)))
        (is (= -400M (:posting/amount (first legs))))))
    (testing "IRRF credited to :acct/irrf"
      (let [legs (get by-acct :acct/irrf)]
        (is (= 1 (count legs)))
        (is (= -247.50M (:posting/amount (first legs))))))))

(deftest employer-side-emits-paired-legs
  (let [postings (build {} (jane-fact))
        by-acct (group-by :posting/account postings)]
    (testing "Employer INSS expense debit"
      (is (= 1000M (:posting/amount (first (get by-acct :acct/er-inss))))))
    (testing "Employer INSS payable credit (paired, NOT collapsed with INSS-EE)"
      (is (= -1000M (:posting/amount (first (get by-acct :acct/inss-employer))))))
    (testing "Employer FGTS expense debit"
      (is (= 400M (:posting/amount (first (get by-acct :acct/er-fgts))))))
    (testing "Employer FGTS payable credit"
      (is (= -400M (:posting/amount (first (get by-acct :acct/fgts))))))))

(deftest four-statutory-buckets-never-collapsed
  ;; Per ADR-081 §3.2: INSS-EE / INSS-ER / FGTS / IRRF are four distinct
  ;; payable buckets — collapsing them breaks GFIP + eSocial S-1210.
  (let [postings (build {} (jane-fact))
        accounts-touched (set (map :posting/account postings))]
    (is (contains? accounts-touched :acct/inss-employee))
    (is (contains? accounts-touched :acct/inss-employer))
    (is (contains? accounts-touched :acct/fgts))
    (is (contains? accounts-touched :acct/irrf))
    (testing "INSS-EE bucket is DISTINCT from INSS-ER bucket"
      (is (not= :acct/inss-employee :acct/inss-employer)))))

(deftest net-wages-payable-credit
  (let [postings (build {} (jane-fact))
        net-legs (filter #(= :acct/net-wages (:posting/account %)) postings)]
    (testing "Single salários-a-pagar credit equal to the fact's :net"
      (is (= 1 (count net-legs)))
      (is (= -4352.50M (:posting/amount (first net-legs)))))))

(deftest cnpj-routing-tag-applies-to-every-posting
  (let [postings (build {:cnpj-account-tag "br-cnpj-12345678000195"}
                        (jane-fact))]
    (testing "Every posting carries the CNPJ routing tag"
      (is (every? (fn [p]
                    (some #(= % [:account-tag/name "br-cnpj-12345678000195"])
                          (:posting/account-tags p)))
                  postings)))))

(deftest missing-account-tag-throws
  (let [partial (dissoc accounts :br-payroll-inss-employee)
        builder (pb/->BrPayrollPostingBuilder {:commodity commodity})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No account configured"
                          (pp/build-postings builder [(jane-fact)]
                                             {:accounts partial})))))

(deftest cpc-33-accruals-emit-paired-legs
  ;; All three CPC 33 accruals (férias, 13º, multa rescisória) follow the
  ;; employer-side leg-pair pattern (DR expense / CR liability) — note
  ;; they are mandatory employer obligations under BR labor law.
  (let [fact {:employment :emp/jane
              :gross 5000M
              :net 4352.50M
              :components [{:kind :base-wage     :amount 5000M    :employer-side? false}
                           {:kind :inss-employee :amount -400M    :employer-side? false}
                           {:kind :irrf-employee :amount -247.50M :employer-side? false}
                           ;; Engine emits 1/12 + 1/3 férias accrual
                           {:kind :ferias-accrual :amount 555.55M :employer-side? true}
                           ;; Engine emits 1/12 13º accrual
                           {:kind :thirteenth-salary-accrual :amount 416.67M
                            :employer-side? true}
                           ;; Engine emits 40% on FGTS = 0.4 * (8% of 5000) = 160
                           {:kind :severance-fgts-accrual :amount 160M
                            :employer-side? true}]}
        postings (build {} fact)
        by-acct (group-by :posting/account postings)]
    (testing "Férias DR expense + CR liability paired"
      (is (= 555.55M (:posting/amount (first (get by-acct :acct/ferias-accrual)))))
      (is (= -555.55M (:posting/amount (first (get by-acct :acct/ferias-liability))))))
    (testing "13º DR expense + CR liability paired"
      (is (= 416.67M (:posting/amount (first (get by-acct :acct/thirteenth-accrual)))))
      (is (= -416.67M (:posting/amount (first (get by-acct :acct/thirteenth-liability))))))
    (testing "Severance DR expense + CR liability paired"
      (is (= 160M (:posting/amount (first (get by-acct :acct/severance-accrual)))))
      (is (= -160M (:posting/amount (first (get by-acct :acct/severance-liability))))))
    (testing "Postings still balance to zero"
      (let [sum (reduce (fn [a {:keys [posting/amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))))))

(deftest vr-vt-employer-share-routes-to-distinct-account
  ;; Vale-refeição + vale-transporte employer share routes to its own
  ;; :br-payroll-vr-vt expense account; this is a BR convention because
  ;; VR/VT have distinct tax treatment from base wages.
  (let [fact {:employment :emp/jane
              :gross 5500M
              :net 4852.50M
              :components [{:kind :base-wage      :amount 5000M    :employer-side? false}
                           {:kind :meal-voucher   :amount 500M     :employer-side? false}
                           {:kind :inss-employee  :amount -400M    :employer-side? false}
                           {:kind :irrf-employee  :amount -247.50M :employer-side? false}]
              :jurisdiction-specific-codes {}}
        postings (build {} fact)
        by-acct (group-by :posting/account postings)]
    (testing "Base wages route to :acct/wages"
      (is (= 5000M (:posting/amount (first (get by-acct :acct/wages))))))
    (testing "Meal voucher routes to :acct/vr-vt (distinct expense)"
      (is (= 500M (:posting/amount (first (get by-acct :acct/vr-vt))))))))

(deftest ledger-tagging-when-supplied
  (let [postings (build {} (jane-fact))
        builder (pb/->BrPayrollPostingBuilder {:commodity commodity})
        with-ledger (pp/build-postings builder [(jane-fact)]
                                       {:accounts accounts
                                        :ledger :ledger/br-ifrs})]
    (testing "Without :ledger no ledger tagging"
      (is (every? #(not (contains? % :posting/ledger)) postings)))
    (testing "With :ledger every posting tagged"
      (is (every? #(= :ledger/br-ifrs (:posting/ledger %)) with-ledger)))))
