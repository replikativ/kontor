(ns kontor.l10n-ca.period-tax-provider-test
  "Iteration 2 — the CA T1 period-tax pilot (ADR-099; notes 102 §6 / 103).

   The differential gate: the `PeriodTaxProvider` wraps `t1/compute`,
   which is therefore the oracle — the `TaxReturnFacts` must reproduce
   its numbers exactly. Plus a cross-check that the kernel schedule
   algebra reproduces CA's own `apply-brackets` on the real CRA
   brackets, and a provision-posting smoke."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.period-tax-provider :as ca-ptp]
            [kontor.l10n-ca.y2024.constants :as k]
            [kontor.l10n-ca.y2024.t1 :as t1]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-return-posting-builder :as trpb]
            [kontor.tax-schedule :as ts]
            [kontor.validation :as validation]))

(defn- cad [n] (money/money (bigdec n) :CAD))

(def ^:private employee-input
  "A salaried BC employee — $80k employment income, CPP/EI maxed,
   $14k withheld."
  {:filer/province :BC :filer/tax-year 2024
   :t4s [{:t4/box-14 (cad 80000) :t4/box-16 (cad 3867.50)
          :t4/box-18 (cad 1049.12) :t4/box-22 (cad 14000)}]})

(def ^:private employee+cg-input
  (assoc employee-input :s3 {:s3/gains [(cad 10000)] :s3/losses []}))

(deftest provider-shape
  (let [facts (ca-ptp/t1-tax-return-facts {:entity 1 :inputs employee-input})]
    (is (ptp/valid-return-facts? facts))
    (is (= 2 (count (:components facts))) "federal + provincial")
    (is (every? #(= :personal-income-tax (:kind %)) (:components facts)))
    (is (= [:cra :bc] (mapv :authority (:components facts)))
        "the multi-authority fan-out — one return, two governments")
    (is (= :CAD (:functional-commodity facts)))
    (is (= {:country "CA" :subdivision :BC} (:jurisdiction facts)))))

(deftest differential-gate-vs-t1-compute
  ;; t1/compute is the oracle — the provider must reproduce it exactly.
  (testing "a salaried BC employee"
    (let [oracle     (t1/compute employee-input)
          facts      (ca-ptp/t1-tax-return-facts {:entity 1 :inputs employee-input})
          [fed prov] (:components facts)]
      (is (money/equiv? (:t1/federal-tax oracle)     (:liability fed)))
      (is (money/equiv? (:t1/bc-tax oracle)          (:liability prov)))
      (is (money/equiv? (:t1/total-tax oracle)       (ptp/total-liability facts)))
      (is (money/equiv? (:t1/income-tax-paid oracle) (ptp/total-prepaid facts)))
      (is (money/equiv? (:t1/balance oracle)         (ptp/balance facts))
          "balance = total-liability − total-prepaid = the T1 refund/owing")
      (is (= (:t1/lines oracle)
             (into {} (map (juxt :line :value)) (:line-items fed)))
          ":line-items round-trips :t1/lines exactly")))
  (testing "capital gains fold into the federal base — no separate component"
    (let [base-plain (-> {:entity 1 :inputs employee-input}
                         ca-ptp/t1-tax-return-facts :components first :base)
          base-cg    (-> {:entity 1 :inputs employee+cg-input}
                         ca-ptp/t1-tax-return-facts :components first :base)]
      (is (pos? (compare (:amount base-cg) (:amount base-plain)))
          "an :s3 capital gain lifts taxable income (the :inputs-fed path)")
      (is (zero? (count (filter #(= :capital-gains-tax (:kind %))
                                (:components
                                 (ca-ptp/t1-tax-return-facts
                                  {:entity 1 :inputs employee+cg-input})))))
          "CA folds CGT into income — no :capital-gains-tax component"))))

(deftest substrate-schedule-matches-ca-apply-brackets
  ;; Validates kontor.tax-schedule against the real CRA brackets AND
  ;; t1's own apply-brackets — the substrate's :progressive-bracket
  ;; IS Canada's federal ladder.
  (doseq [income [10000M 55867M 100000M 200000M 350000M]]
    (let [via-ca  (:amount (t1/apply-brackets (money/money income :CAD)
                                              k/federal-brackets))
          via-sub (.setScale (ts/apply-schedule
                              (ts/progressive k/federal-brackets) income)
                             2 java.math.RoundingMode/HALF_EVEN)]
      (is (zero? (compare via-ca via-sub))
          (str "federal bracket tax on $" income)))))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :account/path ?p] [?pp :posting/account ?a]
                 [?pp :posting/amount ?amt]]
               (d/db conn) path)))

(deftest provision-posts-a-balanced-transaction
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "CAD" :commodity/name "Canadian Dollar"
                  :commodity/precision 2}
                 {:journal/code "GEN" :journal/type :general}
                 {:account/path "Expenses:Income-Tax"     :account/type :expense}
                 {:account/path "Liabilities:Tax-Payable" :account/type :liability}])
    (let [facts   (ca-ptp/t1-tax-return-facts {:entity 1 :inputs employee-input})
          builder (trpb/make-static-tax-return-posting-builder
                   {:expense-account [:account/path "Expenses:Income-Tax"]
                    :payable-account [:account/path "Liabilities:Tax-Payable"]
                    :journal         [:journal/code "GEN"]
                    :commodity       [:commodity/symbol "CAD"]})]
      (validation/transact-with-validation
       conn (trpb/provision-tx-data builder facts
                                    {:effective-date #inst "2024-12-31"}))
      (is (= (:amount (ptp/total-liability facts))
             (sum-account conn "Expenses:Income-Tax"))
          "the provision posts the full T1 + BC428 liability as expense")
      (is (= (- (:amount (ptp/total-liability facts)))
             (sum-account conn "Liabilities:Tax-Payable"))
          "and the matching credit to the tax payable"))))
