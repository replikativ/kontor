(ns kontor.payroll-ca.t4-builder-test
  "T4 aggregator tests — payroll-facts → T4 slip + box catalog +
   XSD validation against the shipped 2026V4 CRA schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core]
            [kontor.l10n-ca.xml.t4 :as xt4]
            [kontor.l10n-ca.xml.validation :as v]
            [kontor.payroll-ca.t4-builder :as t4b])
  (:import [java.io File]))

(def ^:private xsd-path
  (str (System/getProperty "user.dir")
       "/modules/l10n-ca/test/resources/cra/info-returns-xsd-2026"
       "/SchemasEFV-Published - 202601/T619_T4.xsd"))

(defn- xsd-present? []
  (.exists (File. ^String xsd-path)))

(defn- monthly-fact
  "One pay-period's worth of PayrollFacts for one employment. The 12
   summed should be near a full-year T4."
  [emp-eid]
  {:employment emp-eid
   :gross 6250M
   :net 4810M
   :components [{:kind :base-wage          :amount 6250M    :employer-side? false}
                {:kind :income-tax-withheld :amount -800M   :employer-side? false}
                {:kind :employee-cpp       :amount -353M    :employer-side? false}
                {:kind :employee-cpp2      :amount 0M       :employer-side? false}
                {:kind :employee-ei        :amount -102M    :employer-side? false}
                {:kind :employee-rpp-contribution :amount -125M :employer-side? false}
                {:kind :union-dues         :amount -25M     :employer-side? false}
                {:kind :charitable-donation-payroll :amount -35M :employer-side? false}
                {:kind :employer-cpp       :amount 353M     :employer-side? true}
                {:kind :employer-ei        :amount 142.80M  :employer-side? true}]
   :jurisdiction-specific-codes
   ;; Insurable / pensionable earnings — carry-only, NOT posted.
   {:ei-insurable-earnings   6250M
    :cpp-pensionable-earnings 6250M
    :pension-adjustment      150M}})

;; ============================================================================
;; payroll-facts->t4-slip — the load-bearing C4 function
;; ============================================================================

(deftest aggregator-sums-box-14
  (let [facts (vec (repeat 12 (monthly-fact :emp/jane)))
        slip (t4b/payroll-facts->t4-slip
              {:facts facts
               :rp-bn15 "123456782RP0001"
               :person {:family-name "Doe" :given-name "Jane"
                        :national-id-sin "123456782"
                        :province-of-employment "ON"}})]
    (testing "Box 14 = 12 × 6250 = 75000"
      (is (= 75000M (:amount (get-in slip [:t4/boxes :box-14])))))
    (testing "Box 22 = 12 × 800 = 9600"
      (is (= 9600M (:amount (get-in slip [:t4/boxes :box-22])))))
    (testing "Box 16 = 12 × 353 = 4236"
      (is (= 4236M (:amount (get-in slip [:t4/boxes :box-16])))))
    (testing "Box 18 = 12 × 102 = 1224"
      (is (= 1224M (:amount (get-in slip [:t4/boxes :box-18])))))
    (testing "Box 20 (RPP) = 12 × 125 = 1500"
      (is (= 1500M (:amount (get-in slip [:t4/boxes :box-20])))))
    (testing "Box 24 (EI insurable earnings) = 12 × 6250 = 75000"
      (is (= 75000M (:amount (get-in slip [:t4/boxes :box-24])))))
    (testing "Box 26 (CPP pensionable earnings) = 75000"
      (is (= 75000M (:amount (get-in slip [:t4/boxes :box-26])))))
    (testing "Box 44 (union dues) = 12 × 25 = 300"
      (is (= 300M (:amount (get-in slip [:t4/boxes :box-44])))))
    (testing "Box 46 (charitable donations) = 12 × 35 = 420"
      (is (= 420M (:amount (get-in slip [:t4/boxes :box-46])))))
    (testing "Box 52 (pension adjustment, carry-only) = 12 × 150 = 1800"
      (is (= 1800M (:amount (get-in slip [:t4/boxes :box-52])))))))

(deftest aggregator-routes-qc-passthrough-boxes
  (let [qc-fact (-> (monthly-fact :emp/sophie)
                    (assoc :gross 5500M :net 3760M
                           :components
                           [{:kind :base-wage         :amount 5500M    :employer-side? false}
                            {:kind :income-tax-withheld :amount -560M :employer-side? false}
                            {:kind :employee-qc-itx   :amount -260M  :employer-side? false}
                            {:kind :employee-qpp      :amount -353M  :employer-side? false}
                            {:kind :employee-qpip     :amount -53M   :employer-side? false}
                            {:kind :employee-ei       :amount -82M   :employer-side? false}])
                    (assoc :jurisdiction-specific-codes
                           {:qpip-insurable-earnings 5500M}))
        facts (vec (repeat 12 qc-fact))
        slip (t4b/payroll-facts->t4-slip
              {:facts facts
               :rp-bn15 "123456782RP0001"
               :person {:family-name "Lavoie" :given-name "Sophie"
                        :national-id-sin "123456782"
                        :province-of-employment "QC"}})]
    (testing "Box 17 (QPP) = 12 × 353"
      (is (= 4236M (:amount (get-in slip [:t4/boxes :box-17])))))
    (testing "Box 55 (QPIP premiums) = 12 × 53"
      (is (= 636M (:amount (get-in slip [:t4/boxes :box-55])))))
    (testing "Box 56 (QPIP insurable earnings)"
      (is (= 66000M (:amount (get-in slip [:t4/boxes :box-56])))))))

(deftest aggregator-includes-taxable-benefits-in-box-14
  (let [fact (-> (monthly-fact :emp/jane)
                 (assoc :gross 6500M
                        :components
                        [{:kind :base-wage              :amount 6250M :employer-side? false}
                         {:kind :taxable-benefit-auto    :amount 250M  :employer-side? false}
                         {:kind :income-tax-withheld     :amount -880M :employer-side? false}]))
        facts (vec (repeat 12 fact))
        slip (t4b/payroll-facts->t4-slip
              {:facts facts
               :rp-bn15 "123456782RP0001"
               :person {:family-name "Doe" :given-name "Jane"
                        :national-id-sin "123456782"
                        :province-of-employment "ON"}})]
    (testing "Box 14 includes the taxable benefit (gross + benefit)"
      ;; (6250 + 250) × 12 = 78000
      (is (= 78000M (:amount (get-in slip [:t4/boxes :box-14])))))
    (testing "Box-40 'Other Information' subtotal = 12 × 250 = 3000"
      (is (= 3000M (:amount (:t4/box-40-other-info slip)))))))

(deftest missing-province-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":province-of-employment"
                        (t4b/payroll-facts->t4-slip
                         {:facts [(monthly-fact :emp/jane)]
                          :rp-bn15 "123456782RP0001"
                          :person {:family-name "Doe" :given-name "Jane"
                                   :national-id-sin "123456782"}}))))

(deftest missing-sin-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":national-id-sin"
                        (t4b/payroll-facts->t4-slip
                         {:facts [(monthly-fact :emp/jane)]
                          :rp-bn15 "123456782RP0001"
                          :person {:family-name "Doe" :given-name "Jane"
                                   :province-of-employment "ON"}}))))

;; ============================================================================
;; build-t4-return-submission → XML round-trip → XSD validation
;; ============================================================================

(deftest full-submission-validates-against-xsd
  (let [jane-facts (vec (repeat 12 (assoc-in (monthly-fact :emp/jane)
                                             [:jurisdiction-specific-codes
                                              :province-of-employment] "ON")))
        james-facts (vec (repeat 12 (-> (monthly-fact :emp/james)
                                        (assoc :employment :emp/james)
                                        (assoc-in [:jurisdiction-specific-codes
                                                   :province-of-employment] "BC"))))
        facts (concat jane-facts james-facts)
        ;; Synthetic in-memory db; our test :persons-by-emp dodges it.
        db (d/db (kontor.core/create-test-db))
        persons-by-emp {:emp/jane  {:family-name "Doe" :given-name "Jane"
                                    :initial "A"
                                    :national-id-sin "123456782"
                                    :address {:line-1 "1 Pine St" :city "Toronto"
                                              :province "ON" :country "CAN"
                                              :postal-code "M5G2C8"}
                                    :province-of-employment "ON"}
                        :emp/james {:family-name "MacDonald" :given-name "James"
                                    :national-id-sin "123456790"
                                    :address {:line-1 "5 Maple Ave" :city "Vancouver"
                                              :province "BC" :country "CAN"
                                              :postal-code "V6B1A1"}
                                    :province-of-employment "BC"}}
        sub (t4b/build-t4-return-submission
             db
             {:facts facts
              :rp-bn15 "123456782RP0001"
              :tax-year 2026
              :employer-name "Acme Canada Inc."
              :employer-address {:line-1 "100 Bay St."
                                 :city "Toronto" :province "ON"
                                 :country "CAN" :postal-code "M5J2T3"}
              :transmitter {:transmitter/account-number "123456782RP0001"
                            :transmitter/country-code "CAN"
                            :transmitter/contact {:name "A. Payroll"
                                                  :phone "416-555-0100"
                                                  :email "payroll@acme.ca"}
                            :submission/reference-id "ACME001"}
              :persons-by-emp persons-by-emp
              :default-province "ON"
              :language :en
              :report-type :original})
        xml-str (xt4/emit-string sub)]
    (testing "Submission renders to non-empty XML"
      (is (string? xml-str))
      (is (pos? (count xml-str))))
    (testing "Both T4 slips present"
      (is (re-find #"<sin>123456782</sin>" xml-str))
      (is (re-find #"<sin>123456790</sin>" xml-str)))
    (testing "Province codes appear correctly"
      (is (re-find #"<empt_prov_cd>ON</empt_prov_cd>" xml-str))
      (is (re-find #"<empt_prov_cd>BC</empt_prov_cd>" xml-str)))
    (testing "T619 lang_cd is E"
      (is (re-find #"<lang_cd>E</lang_cd>" xml-str)))
    (when (xsd-present?)
      (let [{:keys [valid? errors]} (v/validate xsd-path xml-str)]
        (when-not valid?
          (println "=== XSD ERRORS ===")
          (doseq [e errors] (println e))
          (println "=== XML ==="))
        (is valid? "Aggregator output must validate against CRA 2026V4 T619_T4.xsd")))))

(deftest bilingual-fr-flag-sets-lang-cd-f
  (let [db (datahike.api/db (kontor.core/create-test-db))
        sub (t4b/build-t4-return-submission
             db
             {:facts (vec (repeat 12 (monthly-fact :emp/sophie)))
              :rp-bn15 "123456782RP0001"
              :tax-year 2026
              :employer-name "Acme Canada Inc."
              :transmitter {:transmitter/account-number "123456782RP0001"
                            :transmitter/country-code "CAN"
                            :transmitter/contact {:name "A. Payroll"
                                                  :phone "514-555-0100"
                                                  :email "payroll@acme.ca"}
                            :submission/reference-id "ACME002"}
              :persons-by-emp {:emp/sophie {:family-name "Lavoie" :given-name "Sophie"
                                            :national-id-sin "123456782"
                                            :province-of-employment "QC"}}
              :default-province "QC"
              :language :fr})
        xml-str (xt4/emit-string sub)]
    (testing "T619 lang_cd is F"
      (is (re-find #"<lang_cd>F</lang_cd>" xml-str)))))
