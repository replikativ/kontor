(ns kontor.l10n-br.period-tax-provider-test
  "Phase 1 — BR personal income tax (IRPF) period-tax provider.

   Golden values cross-checked against the published IRPF tabela
   progressiva anual (exercício 2025 / ano-calendário 2024)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-br.period-tax-provider :as br]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; The annual schedule — golden values vs. the published tabela
;; ============================================================================

(deftest irpf-annual-schedule-golden-values
  (let [s (:schedule (br/br-irpf-provider {}))]
    (testing "the 0 % isenção band — at and below the limite de isenção"
      (is (zero? (ts/apply-schedule s 0M)))
      (is (zero? (ts/apply-schedule s 20000M)))
      (is (zero? (ts/apply-schedule s 24511.92M))
          "the exact limite de renda isenta"))
    (testing "marginal-rate values match the published parcela-a-deduzir table"
      ;; parcela form: base × alíquota − parcela a deduzir.
      ;; 40 000 → 15 % band: 40000 × 0.15 − 4382.38 = 1617.62
      (is (== 1617.62100M (ts/apply-schedule s 40000M)))
      ;; 60 000 → 27,5 % band: 60000 × 0.275 − 10557.13 = 5942.87
      (is (== 5942.86800M (ts/apply-schedule s 60000M)))
      ;; 100 000 → 27,5 %: 100000 × 0.275 − 10557.13 = 16942.87
      (is (== 16942.86800M (ts/apply-schedule s 100000M)))
      ;; 300 000 → 27,5 %: 300000 × 0.275 − 10557.13 = 71942.87
      (is (== 71942.86800M (ts/apply-schedule s 300000M))))
    (testing "the marginal ladder agrees with the parcela-a-deduzir form"
      ;; The published table is `limite / alíquota / parcela a deduzir`;
      ;; kontor stores the marginal-rate form. They are mathematically
      ;; identical — confirm across every band (the published parcela
      ;; constants are rounded, so tolerate <= 1 centavo of noise).
      (let [parcela (fn [base]
                      (let [[rate ded]
                            (cond
                              (<= base 24511.92M) [0M     0M]
                              (<= base 33919.80M) [0.075M 1838.39M]
                              (<= base 45012.60M) [0.15M  4382.38M]
                              (<= base 55976.16M) [0.225M 7758.32M]
                              :else               [0.275M 10557.13M])]
                        (- (* base rate) ded)))]
        (doseq [base [30000M 33919.80M 45012.60M 50000M 55976.16M 120000M]]
          (is (>= 0.01M (abs (- (ts/apply-schedule s base) (parcela base))))
              (str "marginal vs parcela form at " base)))))))

;; ============================================================================
;; The monthly IRRF table = annual / 12
;; ============================================================================

(deftest irpf-monthly-table-is-annual-over-12
  (testing "the monthly tabela is the annual one divided by 12"
    (is (= 5 (count br/irpf-monthly-brackets)))
    (is (= 2042.66M (:upper (first br/irpf-monthly-brackets)))
        "24511.92 / 12 = 2042.66 — the monthly limite de isenção")
    (is (nil? (:upper (last br/irpf-monthly-brackets)))
        "the open top band")
    (is (= (mapv :rate br/irpf-annual-brackets)
           (mapv :rate br/irpf-monthly-brackets))
        "same alíquotas, monthly and annual")))

;; ============================================================================
;; Deductions — itemized vs. the simplified discount
;; ============================================================================

(deftest simplified-discount-is-20pct-capped
  (testing "20 % of taxable income, below the cap"
    (is (== 8000M (br/simplified-discount 40000M))
        "20 % of 40 000"))
  (testing "the cap bites on higher incomes"
    (is (== br/simplified-discount-cap (br/simplified-discount 100000M))
        "20 % of 100 000 = 20 000 > the 16 754,34 cap"))
  (testing "never negative"
    (is (zero? (br/simplified-discount 0M)))))

(deftest itemized-deductions-sum-with-the-right-caps
  (testing "INSS, dependents, uncapped health"
    (is (== (+ 8000M (* 2M br/dependent-deduction-annual) 5000M)
            (br/itemized-deductions {:inss 8000M :dependents 2 :health 5000M}))))
  (testing "education is capped per person, health is not"
    ;; education [5000 1000] → min(5000,3561.50) + min(1000,3561.50)
    ;;                       = 3561.50 + 1000 = 4561.50
    (is (== 4561.50M
            (br/itemized-deductions {:education [5000M 1000M]}))))
  (testing "an empty deduction map is zero"
    (is (zero? (br/itemized-deductions {})))))

(deftest base-transform-takes-the-better-of-the-two-regimes
  (testing "on a high income with modest itemized spend the simplified wins"
    (let [t (br/irpf-base-transform {:inss 3000M :dependents 1})
          ;; itemized = 3000 + 2275.08 = 5275.08 ; simplified on 100k = 16754.34
          taxable (ts/apply-base-transform t 100000M)]
      (is (== (- 100000M br/simplified-discount-cap) taxable)
          "the simplified discount is the larger deduction")))
  (testing "with heavy itemized spend the itemized regime wins"
    (let [t (br/irpf-base-transform {:inss 8000M :dependents 2 :health 5000M})
          ;; itemized = 8000 + 4550.16 + 5000 = 17550.16 > 16754.34 cap
          taxable (ts/apply-base-transform t 100000M)]
      (is (== (- 100000M 17550.16M) taxable)
          "itemized 17 550,16 beats the 16 754,34 simplified cap")))
  (testing ":force pins the regime"
    (let [forced-simp (br/irpf-base-transform {:inss 8000M :dependents 2
                                               :health 5000M
                                               :force :simplified})
          forced-item (br/irpf-base-transform {:force :itemized})]
      (is (== (- 100000M br/simplified-discount-cap)
              (ts/apply-base-transform forced-simp 100000M))
          ":force :simplified ignores the larger itemized total")
      (is (== 100000M (ts/apply-base-transform forced-item 100000M))
          ":force :itemized with no spend deducts nothing"))))

(deftest end-to-end-deduction-then-schedule
  ;; The provider pipeline: marginalized gross → base-transform → schedule.
  (let [s (:schedule (br/br-irpf-provider {}))]
    (testing "simplified regime — 100 000 gross, modest itemized"
      (let [t       (br/irpf-base-transform {:inss 3000M})
            taxable (ts/apply-base-transform t 100000M)
            tax     (ts/apply-schedule s taxable)]
        ;; taxable = 100000 − 16754.34 = 83245.66
        (is (== 83245.66M taxable))
        ;; 83245.66 × 0.275 − 10557.13 = 12335.4245
        (is (== 12335.42450M tax))))
    (testing "itemized regime — 100 000 gross, heavy itemized"
      (let [t       (br/irpf-base-transform {:inss 8000M :dependents 2
                                             :health 5000M})
            taxable (ts/apply-base-transform t 100000M)
            tax     (ts/apply-schedule s taxable)]
        ;; taxable = 100000 − 17550.16 = 82449.84
        (is (== 82449.84M taxable))
        ;; 82449.84 × 0.275 − 10557.13 = 12116.574
        (is (== 12116.57400M tax))))))

;; ============================================================================
;; Provider config assertions
;; ============================================================================

(deftest provider-config
  (let [p (br/br-irpf-provider {})]
    (is (= :br-irpf (:id p)))
    (is (= :BRL (:commodity p)))
    (is (= :br-receita-federal (:authority p)))
    (is (= :progressive-bracket (:schedule/type (:schedule p))))
    (is (= 5 (count (:brackets (:schedule p))))
        "five bands — 0 / 7.5 / 15 / 22.5 / 27.5 %")
    (is (= [0M 0.075M 0.15M 0.225M 0.275M]
           (mapv :rate (:brackets (:schedule p)))))
    (is (nil? (:upper (last (:brackets (:schedule p)))))
        "the final band is the open top"))
  (testing "a schedule override is honoured"
    (let [flat (ts/flat 0.275M)
          p    (br/br-irpf-provider {:schedule flat})]
      (is (= flat (:schedule p))))))
