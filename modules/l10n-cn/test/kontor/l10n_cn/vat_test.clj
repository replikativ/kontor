(ns kontor.l10n-cn.vat-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.vat :as vat]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(defn- cny [s] (money/money (bigdec s) :CNY))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                       :journal/type :sale :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- post-sale!
  "Generic sale with per-rate revenue routing — MOF-canonical: single
   output-VAT account 2221.01.01, rate-discrimination via revenue tag."
  [conn ext-id date net revenue-code rate-bd]
  (let [db (d/db conn)
        cny-eid (:db/id (d/entity db [:kontor.commodity/symbol "CNY"]))
        rec (ace db "1122")
        rev (ace db revenue-code)
        out-vat (ace db "2221.01.01")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        tax-bd (.setScale (.multiply net-bd rate-bd) 2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd tax-bd)
        tx (-> (posting/build-transaction
                {:transaction {:transaction/external-id ext-id
                               :transaction/journal jnl
                               :transaction/effective-date date
                               :transaction/narration ext-id
                               :transaction/state :posted
                               :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity cny-eid}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity cny-eid}
                  {:posting/account out-vat :posting/amount (.negate tax-bd) :posting/commodity cny-eid}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(deftest single-13pct-sale
  (testing "CNY 10,000 sale at 13% → sales-13=10,000, output-vat=1,300"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:from jan-1 :to feb-1 :compute-surcharges? false})]
      (is (money/equiv? (cny "10000.00") (:sales-13 (:return/lines r))))
      (is (money/equiv? (cny "1300.00")  (:output-vat (:return/lines r))))
      (is (money/equiv? (cny "1300.00")  (:return/output-vat r)))
      (is (money/equiv? (cny "1300.00")  (get (:return/output-by-rate r) 0.13M)))
      (is (= :payment (:return/outcome r))))))

(deftest mixed-rate-sales
  (testing "10k @ 13% + 5k @ 9% + 2k @ 6% — per-rate output breakdown
            from rate-tagged revenue"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          _ (post-sale! conn "INV-2" jan-15  5000 "5001.9"  0.09M)
          _ (post-sale! conn "INV-3" jan-15  2000 "5001.6"  0.06M)
          r (vat/compute-return conn {:from jan-1 :to feb-1 :compute-surcharges? false})]
      (testing "Per-rate revenue tracked separately"
        (is (money/equiv? (cny "10000.00") (:sales-13 (:return/lines r))))
        (is (money/equiv? (cny "5000.00")  (:sales-9 (:return/lines r))))
        (is (money/equiv? (cny "2000.00")  (:sales-6 (:return/lines r)))))
      (testing "Per-rate output computed from revenue × rate"
        (is (money/equiv? (cny "1300.00") (get (:return/output-by-rate r) 0.13M)))
        (is (money/equiv? (cny "450.00")  (get (:return/output-by-rate r) 0.09M)))
        (is (money/equiv? (cny "120.00")  (get (:return/output-by-rate r) 0.06M))))
      (testing "Total output = 1300 + 450 + 120 = 1870 (booked + computed agree)"
        (is (money/equiv? (cny "1870.00") (:return/output-vat r)))
        (is (money/equiv? (cny "1870.00") (:return/computed-output r)))))))

(deftest monthly-period-bounds
  (testing "Monthly: January 2026"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:year 2026 :month 1 :compute-surcharges? false})]
      (is (= :monthly (:kind (:return/period r))))
      (is (money/equiv? (cny "1300.00") (:return/output-vat r))))))

(deftest nil-return
  (let [conn (bootstrap)
        r (vat/compute-return conn {:from jan-1 :to feb-1 :compute-surcharges? false})]
    (is (= :nil-return (:return/outcome r)))))

;; ============================================================================
;; Surcharges (UMCT + Education + Local Education)
;; ============================================================================

(deftest umct-rate-by-tier
  (testing "Urban Maintenance & Construction Tax rate by location"
    (is (= 0.07M (vat/umct-rate-for-tier :municipal)))
    (is (= 0.05M (vat/umct-rate-for-tier :county)))
    (is (= 0.01M (vat/umct-rate-for-tier :other)))
    (is (= 0.01M (vat/umct-rate-for-tier :unknown))
        "Default fallback to :other (rural / suburban)")))

(deftest surcharges-municipal
  (testing "$10k @ 13% sale (net VAT = 1,300) for a municipal company:
            UMCT  = 1300 × 7%  = 91
            Edu   = 1300 × 3%  = 39
            Local = 1300 × 2%  = 26
            Total surcharges  = 156"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:from jan-1 :to feb-1
                                       :location-tier :municipal})]
      (is (money/equiv? (cny "1300.00") (:return/net-vat r)))
      (is (money/equiv? (cny "91.00")   (:return/umct-payable r)))
      (is (money/equiv? (cny "39.00")   (:return/edu-surcharge-payable r)))
      (is (money/equiv? (cny "26.00")   (:return/local-edu-surcharge-payable r)))
      (is (money/equiv? (cny "156.00")  (:return/total-surcharges r))))))

(deftest surcharges-county
  (testing "Same sale for a county-level company: UMCT 5%"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:from jan-1 :to feb-1 :location-tier :county})]
      (is (money/equiv? (cny "65.00")   (:return/umct-payable r))
          "1300 × 5% = 65")
      (is (money/equiv? (cny "130.00")  (:return/total-surcharges r))
          "65 + 39 + 26 = 130"))))

(deftest surcharges-default-other
  (testing "Default :other tier (UMCT 1%)"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:from jan-1 :to feb-1})]
      (is (= :other (:return/location-tier r)))
      (is (money/equiv? (cny "13.00")   (:return/umct-payable r))
          "1300 × 1% = 13"))))

(deftest surcharges-zero-on-refund
  (testing "Surcharges only apply to positive net VAT — refund period
            produces no surcharge"
    (let [conn (bootstrap)
          ;; No sales (only nil-return); surcharges should be zero
          r (vat/compute-return conn {:from jan-1 :to feb-1 :location-tier :municipal})]
      (is (money/equiv? (cny "0.00") (:return/umct-payable r))))))

(deftest compute-surcharges-flag
  (testing ":compute-surcharges? false omits surcharge keys entirely"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 10000 "5001.13" 0.13M)
          r (vat/compute-return conn {:from jan-1 :to feb-1
                                       :compute-surcharges? false})]
      (is (not (contains? r :return/umct-payable)))
      (is (not (contains? r :return/total-surcharges))))))
