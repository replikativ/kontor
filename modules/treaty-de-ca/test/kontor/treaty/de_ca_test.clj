(ns kontor.treaty.de-ca-test
  "Acceptance: the DE-CA treaty helper books a 4-leg balanced CA-side
   entry with the right split."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.l10n-ca.preset :as ca-preset]
            [kontor.reporting.trial :as trial]
            [kontor.treaty.de-ca :as treaty]))

(def ^:private cad [:kontor.commodity/symbol "CAD"])

(deftest treaty-rates-lookup
  (testing "post-2017-protocol treaty rates"
    (is (= 0.15M (treaty/treaty-rate :dividend-portfolio)))
    (is (= 0.05M (treaty/treaty-rate :dividend-direct-investment)))
    (is (= 0.10M (treaty/treaty-rate :interest)))
    (is (= 0.00M (treaty/treaty-rate :royalty))))
  (testing "unknown kinds throw loudly"
    (is (thrown? clojure.lang.ExceptionInfo
                 (treaty/treaty-rate :unicorn)))))

(deftest split-de-wht-portfolio
  (testing "DE WHT 26.375% on a €9000 portfolio dividend: 15% creditable, excess refundable"
    (let [out (treaty/split-de-wht {:gross-amount    9000M
                                    :withheld-amount 2373.75M
                                    :income-kind     :dividend-portfolio})]
      (is (== 1350.00M (:treaty-creditable out))
          "15% × 9000 = €1350 creditable")
      (is (== 1023.75M (:over-treaty-refundable out))
          "Excess €2373.75 − €1350 = €1023.75 BZSt-refundable")
      (is (= 0.15M (:treaty-rate out))))))

(deftest split-de-wht-direct-investment-no-excess
  (testing "Direct-investment stake: 5% treaty cap; if DE withhold = 5%, no excess"
    (let [out (treaty/split-de-wht {:gross-amount    10000M
                                    :withheld-amount 500M   ; 5%
                                    :income-kind     :dividend-direct-investment})]
      (is (== 500M (:treaty-creditable out))
          "Full WHT creditable when withhold = treaty cap")
      (is (== 0M (:over-treaty-refundable out))))))

(deftest receive-dividend-from-de-end-to-end
  (testing "the cross-border dividend scenario: €9000 gross dividend
            with €2373.75 KESt+Soli withheld, FX 1.50 CAD/EUR"
    (let [conn (ca-preset/create-ca-db)
          ;; Add the entity + partner the helper expects
          _ (d/transact conn
              [{:kontor.entity/name "Sample Owner (Individual)" :kontor.entity/code "OWNER"
                :kontor.entity/country "CA"
                :kontor.entity/functional-commodity cad}
               {:kontor.partner/name "Hans-Tech UG" :kontor.partner/external-id "HT-UG"
                :kontor.partner/country-code "DE"}
               ;; The 3 extra accounts the helper writes to
               {:kontor.account/path "Income:Dividends:Foreign:DE"  :kontor.account/type :income
                :kontor.account/commodity cad}
               {:kontor.account/path "Assets:Foreign-Tax-Prepaid"   :kontor.account/type :asset
                :kontor.account/commodity cad}
               {:kontor.account/path "Assets:Foreign-Tax-Refundable" :kontor.account/type :asset
                :kontor.account/commodity cad}])
          _ (treaty/receive-dividend-from-de! conn
              {:gross-amount     9000M
               :withheld-amount  2373.75M
               :net-cash-amount  6626.25M
               :income-kind      :dividend-portfolio
               :fx-rate          1.50M
               :effective-date   #inst "2027-01-20"
               :payer-partner    [:kontor.partner/external-id "HT-UG"]
               :entity           [:kontor.entity/code "OWNER"]})
          tb (trial/trial-balance conn)
          pull-path (fn [eid] (:kontor.account/path
                               (d/pull (d/db conn) [:kontor.account/path] eid)))
          summary (->> tb
                       (mapv (fn [[eid m]] [(pull-path eid) (->> m vals first :amount)]))
                       (into {}))]
      ;; Math (HALF_EVEN / banker's rounding throughout):
      ;;   net cash    = €6626.25 × 1.50 = CAD 9939.375 → 9939.38 (HALF_EVEN)
      ;;   creditable  = €1350.00 × 1.50 = CAD 2025.00  (treaty 15 % on gross)
      ;;   refundable  = €1023.75 × 1.50 = CAD 1535.625 → 1535.62 (HALF_EVEN: 5→even)
      ;;   gross sum   = 2025.00 + 1535.62 + 9939.38 = 13500.00 ✓ (balanced)
      (is (== 9939.38M (get summary "Assets:Bank:CAD"))
          "Net cash slice in CAD")
      (is (== 2025.00M (get summary "Assets:Foreign-Tax-Prepaid"))
          "Treaty-15%-creditable slice in CAD")
      (is (== 1535.62M (get summary "Assets:Foreign-Tax-Refundable"))
          "BZSt-refundable excess slice in CAD (HALF_EVEN rounds 1535.625 → 1535.62)")
      (is (== -13500.00M (get summary "Income:Dividends:Foreign:DE"))
          "Gross dividend in CAD = sum of the 3 slices, exactly balanced under HALF_EVEN")
      (is (true? (trial/balanced? tb))
          "Whole trial balance sums to zero"))))
