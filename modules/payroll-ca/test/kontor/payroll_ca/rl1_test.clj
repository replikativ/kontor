(ns kontor.payroll-ca.rl1-test
  "RL-1 slip aggregator tests — payroll-facts → RL-1 slip + box catalog
   + XML round-trip. Per ADR-087.

   NOTE: Revenu Québec's RL-1 XSD is partner-only — we do NOT ship the
   XSD nor a validator against it. The tests verify the slip data shape
   and the emitted XML structure against the public RL-1 form
   documentation; consumers with the partner XSD bundle plug
   `kontor.l10n-ca.xml.validation/validate!` against the file
   out-of-band."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-ca.rl1 :as rl1])
  (:import [java.math BigDecimal]))

(defn- qc-monthly-fact
  "One pay-period of PayrollFacts for one QC employment."
  [emp-eid]
  {:employment emp-eid
   :gross 5500M
   :net 4192M
   :components [{:kind :base-wage          :amount 5500M    :employer-side? false}
                {:kind :income-tax-withheld :amount -560M   :employer-side? false}
                {:kind :employee-qc-itx    :amount -260M    :employer-side? false}
                {:kind :employee-qpp       :amount -353M    :employer-side? false}
                {:kind :employee-qpp2      :amount 0M       :employer-side? false}
                {:kind :employee-qpip      :amount -53M     :employer-side? false}
                {:kind :employee-ei        :amount -82M     :employer-side? false}
                {:kind :union-dues         :amount -20M     :employer-side? false}
                {:kind :charitable-donation-payroll :amount -10M :employer-side? false}
                {:kind :employer-qpp       :amount 353M     :employer-side? true}
                {:kind :employer-qpip      :amount 75M      :employer-side? true}
                {:kind :employer-ei        :amount 114.80M  :employer-side? true}]
   :jurisdiction-specific-codes
   {:province-of-employment "QC"
    :qpip-insurable-earnings 5500M
    :cpp-pensionable-earnings 5500M}})

(def sophie-person
  {:given-name "Sophie" :family-name "Lavoie" :initial "M"
   :national-id-sin "123456782"
   :address {:line-1 "100 rue Sainte-Catherine"
             :city "Montréal" :province "QC"
             :country "CAN" :postal-code "H2X1A1"}})

;; ============================================================================
;; payroll-facts->rl1-slip — load-bearing aggregator
;; ============================================================================

(deftest aggregator-sums-box-a-employment-income
  (let [facts (vec (repeat 12 (qc-monthly-fact :emp/sophie)))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person})]
    (testing "Box A = 12 × 5500 = 66000"
      (is (= 66000M (:amount (get-in slip [:rl1/boxes :a])))))
    (testing "Box B (QPP employee) = 12 × 353"
      (is (= 4236M (:amount (get-in slip [:rl1/boxes :b])))))
    (testing "Box C (EI premiums) = 12 × 82"
      (is (= 984M (:amount (get-in slip [:rl1/boxes :c])))))
    (testing "Box E (Quebec income tax withheld) = 12 × 260"
      (is (= 3120M (:amount (get-in slip [:rl1/boxes :e])))))
    (testing "Box F (union dues) = 12 × 20"
      (is (= 240M (:amount (get-in slip [:rl1/boxes :f])))))
    (testing "Box G (QPP pensionable earnings)"
      (is (= 66000M (:amount (get-in slip [:rl1/boxes :g])))))
    (testing "Box H (QPIP premiums) = 12 × 53"
      (is (= 636M (:amount (get-in slip [:rl1/boxes :h])))))
    (testing "Box I (QPIP insurable earnings)"
      (is (= 66000M (:amount (get-in slip [:rl1/boxes :i])))))
    (testing "Box N (charitable donations) = 12 × 10"
      (is (= 120M (:amount (get-in slip [:rl1/boxes :n])))))))

(deftest aggregator-commission-doubles-to-a-and-m
  (let [comm-fact (-> (qc-monthly-fact :emp/sophie)
                      (assoc :components
                             [{:kind :base-wage  :amount 3000M  :employer-side? false}
                              {:kind :commission :amount 2500M  :employer-side? false}]))
        facts (vec (repeat 12 comm-fact))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person})]
    (testing "Box A includes commission (base 3000 + commission 2500) × 12 = 66000"
      (is (= 66000M (:amount (get-in slip [:rl1/boxes :a])))))
    (testing "Box M = commission only × 12 = 30000"
      (is (= 30000M (:amount (get-in slip [:rl1/boxes :m])))))))

(deftest aggregator-taxable-benefits-flow-to-a-and-l
  (let [fact (-> (qc-monthly-fact :emp/sophie)
                 (assoc :components
                        [{:kind :base-wage              :amount 5000M :employer-side? false}
                         {:kind :taxable-benefit-auto    :amount 300M :employer-side? false}
                         {:kind :taxable-benefit-other   :amount 50M  :employer-side? false}]))
        facts (vec (repeat 12 fact))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person})]
    (testing "Box A = (5000 + 300 + 50) × 12 = 64200"
      (is (= 64200M (:amount (get-in slip [:rl1/boxes :a])))))
    (testing "Box L = (300 + 50) × 12 = 4200"
      (is (= 4200M (:amount (get-in slip [:rl1/boxes :l])))))))

(deftest aggregator-handles-qpp2
  (let [fact (-> (qc-monthly-fact :emp/sophie)
                 (update :components conj
                         {:kind :employee-qpp2 :amount -25M :employer-side? false}))
        facts (vec (repeat 12 fact))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person})]
    (testing "Box B.A = 12 × 25"
      (is (= 300M (:amount (get-in slip [:rl1/boxes :b.a])))))))

(deftest missing-sin-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":national-id-sin"
                        (rl1/payroll-facts->rl1-slip
                         {:facts [(qc-monthly-fact :emp/sophie)]
                          :employer-neq "1234567890"
                          :person (dissoc sophie-person :national-id-sin)}))))

(deftest missing-employer-neq-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":employer-neq"
                        (rl1/payroll-facts->rl1-slip
                         {:facts [(qc-monthly-fact :emp/sophie)]
                          :person sophie-person}))))

(deftest missing-facts-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":facts required"
                        (rl1/payroll-facts->rl1-slip
                         {:facts []
                          :employer-neq "1234567890"
                          :person sophie-person}))))

;; ============================================================================
;; slip->element — XML round-trip
;; ============================================================================

(defn- xml-emit [el]
  (clojure.data.xml/emit-str el))

(deftest slip-element-emits-required-fields
  (let [facts (vec (repeat 12 (qc-monthly-fact :emp/sophie)))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :employer-id-number "NP123456"
               :person sophie-person
               :reference-number "RL1-2026-000001"})
        xml-str (xml-emit (rl1/slip->element slip))]
    (testing "NAS / SIN emitted"
      (is (re-find #"<NAS>123456782</NAS>" xml-str)))
    (testing "NEQ emitted"
      (is (re-find #"<NEQ>1234567890</NEQ>" xml-str)))
    (testing "Numero identification (NP-prefixed) emitted"
      (is (re-find #"<NumeroIdentification>NP123456</NumeroIdentification>"
                   xml-str)))
    (testing "Report-type code is R for :original"
      (is (re-find #"<CodeReleve>R</CodeReleve>" xml-str)))
    (testing "Reference number emitted"
      (is (re-find #"<NumeroReleve>RL1-2026-000001</NumeroReleve>" xml-str)))
    (testing "Surname + given-name emitted"
      (is (re-find #"<NomFamille>Lavoie</NomFamille>" xml-str))
      (is (re-find #"<Prenom>Sophie</Prenom>" xml-str)))
    (testing "Address province emitted"
      (is (re-find #"<Province>QC</Province>" xml-str)))
    (testing "Box A amount emitted with 2-decimal scale"
      (is (re-find #"<CaseA>66000\.00</CaseA>" xml-str)))
    (testing "Box B (QPP) emitted"
      (is (re-find #"<CaseB>4236\.00</CaseB>" xml-str)))
    (testing "Box E (Quebec ITX) emitted"
      (is (re-find #"<CaseE>3120\.00</CaseE>" xml-str)))
    (testing "Box H (QPIP) emitted"
      (is (re-find #"<CaseH>636\.00</CaseH>" xml-str)))))

(deftest report-type-amended-emits-code-a
  (let [facts (vec (repeat 12 (qc-monthly-fact :emp/sophie)))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person
               :report-type :amended})
        xml-str (xml-emit (rl1/slip->element slip))]
    (testing "Report-type code is A for :amended"
      (is (re-find #"<CodeReleve>A</CodeReleve>" xml-str)))))

(deftest report-type-cancelled-emits-code-d
  (let [facts (vec (repeat 12 (qc-monthly-fact :emp/sophie)))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person
               :report-type :cancelled})
        xml-str (xml-emit (rl1/slip->element slip))]
    (testing "Report-type code is D for :cancelled (annulé)"
      (is (re-find #"<CodeReleve>D</CodeReleve>" xml-str)))))

(deftest zero-amounts-omitted
  (let [no-qpp-fact (-> (qc-monthly-fact :emp/sophie)
                        (update :components
                                #(filterv (fn [c] (not= :employee-qpp (:kind c))) %)))
        facts (vec (repeat 12 no-qpp-fact))
        slip (rl1/payroll-facts->rl1-slip
              {:facts facts
               :employer-neq "1234567890"
               :person sophie-person})
        xml-str (xml-emit (rl1/slip->element slip))]
    (testing "Box B (QPP) is omitted when zero"
      (is (not (re-find #"<CaseB>" xml-str))))
    (testing "Box A still present"
      (is (re-find #"<CaseA>" xml-str)))))

;; ============================================================================
;; QC filtering + group-facts-for-slips
;; ============================================================================

(deftest group-facts-filters-non-qc
  (let [qc-fact (qc-monthly-fact :emp/sophie)
        on-fact (-> (qc-monthly-fact :emp/james)
                    (assoc-in [:jurisdiction-specific-codes
                               :province-of-employment]
                              "ON"))
        grouped (rl1/group-facts-for-slips nil [qc-fact on-fact])]
    (testing "Only the QC fact's employment is grouped"
      (is (= #{:emp/sophie} (set (keys grouped)))))))

(deftest qc-fact-predicate
  (testing "QC fact is detected"
    (is (rl1/qc-fact? (qc-monthly-fact :emp/sophie))))
  (testing "Non-QC (ON) fact is not"
    (is (not (rl1/qc-fact?
              (assoc-in (qc-monthly-fact :emp/james)
                        [:jurisdiction-specific-codes
                         :province-of-employment] "ON")))))
  (testing "Fact with no province is not (no fallback when db=nil)"
    (is (not (rl1/qc-fact?
              (assoc (qc-monthly-fact :emp/x)
                     :jurisdiction-specific-codes {}))))))

;; ============================================================================
;; rl1-audit-doc-tx-data
;; ============================================================================

(deftest audit-doc-tx-data-carries-fr-language-by-default
  (let [tx (rl1/rl1-audit-doc-tx-data
            {:employer-neq "1234567890" :tax-year 2026 :slip-count 3})]
    (testing "Single audit-doc row"
      (is (= 1 (count tx))))
    (testing "Category is :payroll-filing"
      (is (= :payroll-filing (:kontor.audit-doc/category (first tx)))))
    (testing "Language defaults to :fr"
      (is (= :fr (:kontor.audit-doc/language (first tx)))))
    (testing "Title carries NEQ + year + slip count"
      (is (re-find #"1234567890" (:kontor.audit-doc/title (first tx))))
      (is (re-find #"2026" (:kontor.audit-doc/title (first tx))))
      (is (re-find #"3 slips" (:kontor.audit-doc/title (first tx)))))))

(deftest audit-doc-tx-data-honors-amended-and-storage-uri
  (let [tx (rl1/rl1-audit-doc-tx-data
            {:employer-neq "1234567890" :tax-year 2026 :slip-count 5
             :report-type :amended
             :storage-uri "s3://payroll-archive/rl1-2026-amended.xml"})]
    (testing "Code reflects report-type"
      (is (re-find #"amended" (:kontor.audit-doc/code (first tx)))))
    (testing "Storage URI is preserved"
      (is (= "s3://payroll-archive/rl1-2026-amended.xml"
             (:kontor.audit-doc/storage-uri (first tx)))))))

;; ============================================================================
;; Sanity check on the box-mapping catalog
;; ============================================================================

(deftest box-mapping-covers-load-bearing-kinds
  (testing "Earnings → Box A"
    (doseq [k [:base-wage :overtime :bonus :vacation-pay-paid-out
               :statutory-holiday-pay :retroactive-pay :severance
               :retiring-allowance]]
      (is (= :a (:box (get rl1/box-mapping k)))
          (str k " → :a"))))
  (testing "Quebec-only deductions"
    (is (= :b   (:box (get rl1/box-mapping :employee-qpp))))
    (is (= :b.a (:box (get rl1/box-mapping :employee-qpp2))))
    (is (= :e   (:box (get rl1/box-mapping :employee-qc-itx))))
    (is (= :h   (:box (get rl1/box-mapping :employee-qpip)))))
  (testing "Carry-only (insurable earnings)"
    (is (= :g (:box (get rl1/box-mapping :cpp-pensionable-earnings))))
    (is (= :i (:box (get rl1/box-mapping :qpip-insurable-earnings)))))
  (testing "Commission flows to A AND M"
    (is (= :a (:box (get rl1/box-mapping :commission))))
    (is (= [:m] (:also-boxes (get rl1/box-mapping :commission))))))

(comment BigDecimal)
