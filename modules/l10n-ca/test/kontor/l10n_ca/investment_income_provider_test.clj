(ns kontor.l10n-ca.investment-income-provider-test
  "Tests for the CA investment-income provider.

   Coverage:
     §1  Eligible dividend gross-up + federal DTC + ON provincial DTC
         (Ms. Chen-style, ON variant)
     §2  Non-eligible dividend gross-up + federal + ON DTC
     §3  Interest income — folds to PIT base via :pit-base-additions
     §4  TFSA / FHSA exemption (consumer filters → no items reach
         the provider; the slice is skipped)
     §5  Foreign dividend §126 — 15% non-business cap on tax credit
     §6  Mixed Ms Chen BC portfolio
     §7  Mixed Ms Chen with ON — fed + ON parallel
     §8  Quebec resident — eligible + non-eligible DTC chain
     §9  Alberta resident — sanity on per-province rates
     §10 Corporate Part IV refundable tax (OpsCo)
     §11 Corporate §123.3 ART on AII
     §12 Corporate §112 inter-corporate deduction (full deduction)
     §13 Components carry :investment-income-tax / :part-iv-tax kinds
     §14 Kind validation."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.investment-income-provider :as inv]
            [kontor.l10n-ca.investment-income-statute :as inv-statute]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with the CA investment-income statute installed + a CAD
   commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "CAD" :kontor.commodity/name "Canadian Dollar"
                       :kontor.commodity/precision 2}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
(def ^:private as-of-2026 #inst "2026-06-30")

(defn- run-individual
  [conn items & [tax-unit]]
  (let [provider (inv/ca-individual-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     {:db       (d/db conn)
      :entity   :tp
      :period   p2026
      :as-of    as-of-2026
      :tax-unit (or tax-unit {})
      :inputs   {:ca-investment-income items}})))

(defn- run-corporate
  [conn items & [tax-unit]]
  (let [provider (inv/ca-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     {:db       (d/db conn)
      :entity   :corp
      :period   p2026
      :as-of    as-of-2026
      :tax-unit (or tax-unit {})
      :inputs   {:ca-investment-income items}})))

(defn- component-by-authority [facts authority]
  (->> (:components facts)
       (filter #(= authority (:authority %)))
       first))

(defn- component-by-kind [facts kind]
  (->> (:components facts)
       (filter #(= kind (:kind %)))
       first))

(defn- credit-by-code [component code]
  (->> (:credits component)
       (filter #(= code (:code %)))
       first))

;; ============================================================================
;; §1. Eligible dividend gross-up + federal DTC
;; ============================================================================

(deftest eligible-dividend-gross-up-and-federal-dtc
  (testing "$5,000 eligible div → grossed-up $6,900; federal DTC = $1,036.37"
    (let [conn  (fresh)
          facts (run-individual conn {:eligible-dividends 5000M}
                                {:province :on})
          fed   (component-by-authority facts :cra)
          gu    (get-in fed [:jurisdiction-specific-codes :ca/grossed-up-eligible])
          dtc   (credit-by-code fed :ca-federal-dtc-eligible)]
      (is (some? fed))
      ;; gross-up: 5000 × 1.38 = 6900
      (is (== 6900M gu))
      ;; PIT base addition carries the grossed-up amount
      (is (= [6900M] (get-in fed [:jurisdiction-specific-codes :pit-base-additions])))
      ;; federal DTC = 6900 × 0.150198 = 1036.3662
      (is (some? dtc))
      (is (== 1036.3662M (:amount dtc)))
      ;; liability is negative of credit total
      (is (== -1036.3662M (-> fed :liability :amount))))))

;; ============================================================================
;; §2. Non-eligible dividend gross-up + federal DTC + ON DTC
;; ============================================================================

(deftest non-eligible-dividend-and-on-dtc
  (testing "$3,000 non-eligible div → grossed-up $3,450; federal + ON DTC fire"
    (let [conn  (fresh)
          facts (run-individual conn {:non-eligible-dividends 3000M}
                                {:province :on})
          fed   (component-by-authority facts :cra)
          on    (component-by-authority facts :ca-on)
          gu    (get-in fed [:jurisdiction-specific-codes :ca/grossed-up-non-eligible])]
      (is (some? fed))
      (is (some? on))
      ;; gross-up: 3000 × 1.15 = 3450
      (is (== 3450M gu))
      ;; federal DTC = 3450 × 0.090301 = 311.538..M
      (let [fed-dtc (credit-by-code fed :ca-federal-dtc-non-eligible)]
        (is (some? fed-dtc))
        (is (== 311.538450M (:amount fed-dtc))))
      ;; ON non-eligible DTC = 3450 × 0.029863 = 103.02735
      (let [on-dtc (credit-by-code on :ca-on-dtc-non-eligible)]
        (is (some? on-dtc))
        (is (== 103.027350M (:amount on-dtc))))
      ;; Provincial component carries NO PIT base additions (federal owns those)
      (is (nil? (get-in on [:jurisdiction-specific-codes :pit-base-additions]))))))

;; ============================================================================
;; §3. Interest income — folds to PIT base via :pit-base-additions
;; ============================================================================

(deftest interest-folds-to-pit-base
  (testing "$1,500 interest → PIT base addition; no DTC; ordinary income"
    (let [conn  (fresh)
          facts (run-individual conn {:interest 1500M}
                                {:province :bc})
          fed   (component-by-authority facts :cra)]
      (is (some? fed))
      (is (= [1500M] (get-in fed [:jurisdiction-specific-codes :pit-base-additions])))
      ;; No DTC fires (the conditions require grossed-up > 0)
      (is (empty? (:credits fed)))
      (is (== 0M (-> fed :liability :amount))))))

;; ============================================================================
;; §4. TFSA / FHSA — exempt at the consumer level (no items reach provider)
;; ============================================================================

(deftest sheltered-accounts-skip-the-provider
  (testing "consumer filters out TFSA / FHSA / RRSP postings; provider sees no income"
    (let [conn  (fresh)
          ;; Consumer has filtered the TFSA/FHSA dividends out
          ;; entirely — empty items map.
          facts (run-individual conn {} {:province :on})
          fed   (component-by-authority facts :cra)]
      (is (some? fed))
      ;; No PIT base additions
      (is (nil? (get-in fed [:jurisdiction-specific-codes :pit-base-additions])))
      ;; No credits
      (is (empty? (:credits fed)))
      ;; Zero liability
      (is (== 0M (-> fed :liability :amount))))))

;; ============================================================================
;; §5. Foreign dividend §126 — 15% non-business cap
;; ============================================================================

(deftest foreign-dividend-§126-credit-capped-at-15-pct
  (testing "$2,000 MSFT div + $300 US WHT → §126 credit = min(300, 15% × 2000 = 300) = 300"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:foreign-dividends 2000M
                                 :foreign-tax-paid  300M}
                                {:province :bc})
          fed   (component-by-authority facts :cra)
          ftc   (credit-by-code fed :ca-federal-foreign-tax-credit)]
      (is (some? ftc))
      (is (== 300M (:amount ftc)))
      ;; PIT base addition carries the foreign dividend at GROSS (no Canadian gross-up)
      (is (= [2000M] (get-in fed [:jurisdiction-specific-codes :pit-base-additions])))))

  (testing "treaty WHT exceeds 15% → cap binds; excess goes to §20(11) deduction (out of scope)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:foreign-dividends 1000M
                                 ;; Hypothetical 25% WHT (no treaty)
                                 :foreign-tax-paid  250M}
                                {:province :bc})
          fed   (component-by-authority facts :cra)
          ftc   (credit-by-code fed :ca-federal-foreign-tax-credit)]
      ;; cap = 1000 × 0.15 = 150; min(250, 150) = 150
      (is (== 150M (:amount ftc))))))

;; ============================================================================
;; §6. Ms Chen, BC resident
;; ============================================================================

(deftest ms-chen-bc-mixed-portfolio
  (testing "Ms Chen BC: $5k elig + $3k non-elig + $1.5k int + $2k foreign + $300 US WHT"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:eligible-dividends     5000M
                                 :non-eligible-dividends 3000M
                                 :interest               1500M
                                 :foreign-dividends      2000M
                                 :foreign-tax-paid       300M}
                                {:province :bc})
          fed   (component-by-authority facts :cra)
          bc    (component-by-authority facts :ca-bc)]
      (is (some? fed))
      (is (some? bc))

      (testing "federal: grossed-up + interest + foreign all in PIT base additions"
        ;; gu-elig 6900 + gu-non-elig 3450 + interest 1500 + foreign 2000
        (let [adds (get-in fed [:jurisdiction-specific-codes :pit-base-additions])]
          (is (= [6900M 3450M 1500M 2000M] adds))))

      (testing "federal DTC: eligible (1036.37) + non-eligible (311.54) + §126 (300)"
        (let [fed-elig (credit-by-code fed :ca-federal-dtc-eligible)
              fed-ne   (credit-by-code fed :ca-federal-dtc-non-eligible)
              fed-ftc  (credit-by-code fed :ca-federal-foreign-tax-credit)]
          (is (== 1036.3662M  (:amount fed-elig)))
          (is (== 311.538450M (:amount fed-ne)))
          (is (== 300M        (:amount fed-ftc)))))

      (testing "BC DTC: eligible (12% × 6900 = 828) + non-eligible (1.96% × 3450 = 67.62)"
        (let [bc-elig (credit-by-code bc :ca-bc-dtc-eligible)
              bc-ne   (credit-by-code bc :ca-bc-dtc-non-eligible)]
          (is (== 828M     (:amount bc-elig)))
          (is (== 67.6200M (:amount bc-ne))))))))

;; ============================================================================
;; §7. ON-resident parallel — verifies per-province rate routing
;; ============================================================================

(deftest ms-chen-on-mixed-portfolio
  (testing "ON resident, same portfolio: 10% eligible DTC + 2.9863% non-elig DTC"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:eligible-dividends     5000M
                                 :non-eligible-dividends 3000M}
                                {:province :on})
          on    (component-by-authority facts :ca-on)
          on-e  (credit-by-code on :ca-on-dtc-eligible)
          on-n  (credit-by-code on :ca-on-dtc-non-eligible)]
      ;; ON eligible: 10% × 6900 = 690
      (is (== 690M (:amount on-e)))
      ;; ON non-eligible: 2.9863% × 3450 = 103.02735
      (is (== 103.027350M (:amount on-n))))))

;; ============================================================================
;; §8. Quebec resident — both lanes
;; ============================================================================

(deftest qc-resident-both-lanes
  (testing "QC resident: 11.7% eligible / 3.42% non-eligible DTC"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:eligible-dividends     5000M
                                 :non-eligible-dividends 3000M}
                                {:province :qc})
          qc    (component-by-authority facts :ca-qc-revenu)
          qc-e  (credit-by-code qc :ca-qc-dtc-eligible)
          qc-n  (credit-by-code qc :ca-qc-dtc-non-eligible)]
      (is (some? qc))
      ;; QC eligible: 11.7% × 6900 = 807.30
      (is (== 807.300M (:amount qc-e)))
      ;; QC non-eligible: 3.42% × 3450 = 117.99
      (is (== 117.9900M (:amount qc-n))))))

;; ============================================================================
;; §9. Alberta resident — sanity
;; ============================================================================

(deftest ab-resident-eligible-dtc-rate
  (testing "AB eligible DTC rate 8.12% × grossed-up"
    (let [conn  (fresh)
          facts (run-individual conn {:eligible-dividends 10000M}
                                {:province :ab})
          ab    (component-by-authority facts :ca-ab-tra)
          ab-e  (credit-by-code ab :ca-ab-dtc-eligible)]
      (is (some? ab))
      ;; AB eligible: 8.12% × 13800 = 1120.56
      (is (== 1120.560M (:amount ab-e))))))

;; ============================================================================
;; §10. Corporate Part IV refundable tax (OpsCo)
;; ============================================================================

(deftest corp-part-iv-on-portfolio-dividends
  (testing "OpsCo receives $50k portfolio div → Part IV @ 38⅓ % = $19,166.65"
    (let [conn  (fresh)
          facts (run-corporate conn {:portfolio-dividends 50000M})
          part4 (component-by-kind facts :part-iv-tax)]
      (is (some? part4))
      ;; 50000 × 0.383333 = 19166.65
      (is (== 19166.65M (-> part4 :liability :amount)))
      ;; RDTOH credits-out — Part IV adds to Eligible RDTOH
      (let [rdtoh (get-in part4 [:jurisdiction-specific-codes :rdtoh-credits-out])]
        (is (== 19166.65M (:eligible     rdtoh)))
        (is (== 0M        (:non-eligible rdtoh)))))))

;; ============================================================================
;; §11. Corporate §123.3 ART on AII
;; ============================================================================

(deftest corp-art-on-aii
  (testing "AII = $25k interest → §123.3 ART @ 10⅔ % = $2,666.675"
    (let [conn  (fresh)
          facts (run-corporate conn {:aii 25000M
                                     :interest 25000M})
          art   (->> (:components facts)
                     (filter #(and (= :corporate-income-tax (:kind %))
                                   (= :ca-art (get-in % [:jurisdiction-specific-codes :lane]))))
                     first)]
      (is (some? art))
      ;; 25000 × 0.106667 = 2666.675
      (is (== 2666.675M (-> art :liability :amount)))
      ;; RDTOH — ART adds to Non-Eligible RDTOH
      (let [rdtoh (get-in art [:jurisdiction-specific-codes :rdtoh-credits-out])]
        (is (== 0M        (:eligible     rdtoh)))
        (is (== 2666.675M (:non-eligible rdtoh)))))))

;; ============================================================================
;; §12. §112 inter-corporate deduction — full deduction of CA-source div
;; ============================================================================

(deftest corp-s112-feeder-deduction
  (testing "OpsCo: portfolio $50k + connected $20k → §112 deduction = $70k via feeder"
    (let [conn   (fresh)
          facts  (run-corporate conn {:portfolio-dividends 50000M
                                      :connected-dividends 20000M})
          feeder (->> (:components facts)
                      (filter #(= :ca-corp-inv-feeder
                                  (get-in % [:jurisdiction-specific-codes :lane])))
                      first)]
      (is (some? feeder))
      (let [deds (get-in feeder [:jurisdiction-specific-codes :cit-base-deductions])]
        (is (= [70000M] deds)))
      (is (== 70000M (get-in feeder [:jurisdiction-specific-codes :ca/section112-deduction]))))))

;; ============================================================================
;; §13. Components carry the right kind enum values
;; ============================================================================

(deftest components-use-correct-kind
  (testing "individual provider: every component is :investment-income-tax"
    (let [conn  (fresh)
          facts (run-individual conn {:eligible-dividends 5000M}
                                {:province :on})]
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts)))))

  (testing "corporate provider: Part IV is :part-iv-tax; ART + feeder are :corporate-income-tax"
    (let [conn  (fresh)
          facts (run-corporate conn {:portfolio-dividends 1000M
                                     :aii                 1000M
                                     :interest            1000M})
          kinds (set (map :kind (:components facts)))]
      (is (contains? kinds :part-iv-tax))
      (is (contains? kinds :corporate-income-tax)))))

;; ============================================================================
;; §14. Kind validation — provider rejects invalid :kind
;; ============================================================================

(deftest invalid-kind-rejected
  (testing "non-:individual/:corporation :kind throws"
    (let [conn     (fresh)
          provider (assoc (inv/ca-individual-investment-income-provider {})
                          :kind :bogus)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #":kind must be :individual or :corporation"
           (ptp/period-tax-facts
            provider
            {:db (d/db conn) :entity :tp :period p2026
             :inputs {:ca-investment-income {}}}))))))
