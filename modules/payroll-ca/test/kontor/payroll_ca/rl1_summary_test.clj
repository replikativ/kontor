(ns kontor.payroll-ca.rl1-summary-test
  "RL-1 Summary (RLZ-1.S) aggregator + submission envelope tests.
   Per ADR-087."
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [kontor.money :as money]
            [kontor.payroll-ca.rl1 :as rl1]
            [kontor.payroll-ca.rl1-summary :as rl1-sum]))

(def sophie-person
  {:given-name "Sophie" :family-name "Lavoie" :initial "M"
   :national-id-sin "123456782"
   :address {:line-1 "100 rue Sainte-Catherine"
             :city "Montréal" :province "QC"
             :country "CAN" :postal-code "H2X1A1"}})

(def jean-person
  {:given-name "Jean" :family-name "Tremblay"
   :national-id-sin "123456790"
   :address {:line-1 "200 rue Saint-Denis"
             :city "Québec" :province "QC"
             :country "CAN" :postal-code "G1R3A1"}})

(defn- qc-fact [emp-eid {:keys [gross qc-itx qpp qpip ei]}]
  {:employment emp-eid
   :gross gross
   :net (- gross qc-itx qpp qpip ei)
   :components [{:kind :base-wage          :amount gross   :employer-side? false}
                {:kind :employee-qc-itx    :amount (- qc-itx) :employer-side? false}
                {:kind :employee-qpp       :amount (- qpp) :employer-side? false}
                {:kind :employee-qpip      :amount (- qpip) :employer-side? false}
                {:kind :employee-ei        :amount (- ei)  :employer-side? false}]
   :jurisdiction-specific-codes
   {:province-of-employment "QC"
    :qpip-insurable-earnings gross
    :cpp-pensionable-earnings gross}})

;; ============================================================================
;; build-summary — totals aggregation
;; ============================================================================

(deftest summary-aggregates-box-totals-across-slips
  (let [sophie-facts (vec (repeat 12 (qc-fact :emp/sophie
                                              {:gross 5500M :qc-itx 260M
                                               :qpp 353M :qpip 53M :ei 82M})))
        jean-facts (vec (repeat 12 (qc-fact :emp/jean
                                            {:gross 6000M :qc-itx 320M
                                             :qpp 384M :qpip 60M :ei 90M})))
        sophie-slip (rl1/payroll-facts->rl1-slip
                     {:facts sophie-facts
                      :employer-neq "1234567890"
                      :person sophie-person})
        jean-slip (rl1/payroll-facts->rl1-slip
                   {:facts jean-facts
                    :employer-neq "1234567890"
                    :person jean-person})
        summary (rl1-sum/build-summary
                 {:slips [sophie-slip jean-slip]
                  :employer-neq "1234567890"
                  :employer-name "Acme Québec Inc."
                  :tax-year 2026
                  :fss-contribution (money/money 4500M :CAD)
                  :employer-qpp (money/money (* 12 (+ 353 384)) :CAD)
                  :employer-qpip (money/money (* 12 (+ 75 80)) :CAD)})]
    (testing "Quebec ITX total = sum across slips × 12"
      ;; Sophie 260*12=3120; Jean 320*12=3840; sum=6960
      (is (= 6960M (:amount (:rlz-1-s/quebec-income-tax-withheld summary)))))
    (testing "QPP employee total"
      ;; 353*12 + 384*12 = 8844
      (is (= 8844M (:amount (:rlz-1-s/qpp-employee summary)))))
    (testing "QPIP employee total"
      ;; 53*12 + 60*12 = 1356
      (is (= 1356M (:amount (:rlz-1-s/qpip-employee summary)))))
    (testing "Slip count"
      (is (= 2 (:rlz-1-s/slip-count summary))))
    (testing "FSS contribution preserved"
      (is (= 4500M (:amount (:rlz-1-s/fss-contribution summary)))))
    (testing "Employer QPP preserved"
      (is (= (BigDecimal/valueOf (* 12 (+ 353 384)))
             (:amount (:rlz-1-s/qpp-employer summary)))))))

(deftest summary-defaults-zero-when-no-employer-contribution-supplied
  (let [sophie-facts (vec (repeat 12 (qc-fact :emp/sophie
                                              {:gross 5500M :qc-itx 260M
                                               :qpp 353M :qpip 53M :ei 82M})))
        sophie-slip (rl1/payroll-facts->rl1-slip
                     {:facts sophie-facts
                      :employer-neq "1234567890"
                      :person sophie-person})
        summary (rl1-sum/build-summary
                 {:slips [sophie-slip]
                  :employer-neq "1234567890"
                  :employer-name "Acme Québec Inc."
                  :tax-year 2026})]
    (testing "FSS defaults to zero CAD when not supplied"
      (is (money/zero? (:rlz-1-s/fss-contribution summary))))
    (testing "Employer QPP defaults to zero"
      (is (money/zero? (:rlz-1-s/qpp-employer summary))))
    (testing "Employer QPIP defaults to zero"
      (is (money/zero? (:rlz-1-s/qpip-employer summary))))))

(deftest summary-validation-throws-on-missing
  (testing "Missing :employer-neq throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":employer-neq"
                          (rl1-sum/build-summary
                           {:slips [] :tax-year 2026
                            :employer-name "Acme"}))))
  (testing "Missing :tax-year throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":tax-year"
                          (rl1-sum/build-summary
                           {:slips [] :employer-neq "1234567890"
                            :employer-name "Acme"})))))

;; ============================================================================
;; summary->element — XML round-trip
;; ============================================================================

(deftest summary-element-emits-required-fields
  (let [sophie-facts (vec (repeat 12 (qc-fact :emp/sophie
                                              {:gross 5500M :qc-itx 260M
                                               :qpp 353M :qpip 53M :ei 82M})))
        sophie-slip (rl1/payroll-facts->rl1-slip
                     {:facts sophie-facts
                      :employer-neq "1234567890"
                      :person sophie-person})
        summary (rl1-sum/build-summary
                 {:slips [sophie-slip]
                  :employer-neq "1234567890"
                  :employer-id-number "NP123456"
                  :employer-name "Acme Québec Inc."
                  :employer-address {:line-1 "100 boul. René-Lévesque"
                                     :city "Montréal" :province "QC"
                                     :country "CAN" :postal-code "H3B1A1"}
                  :tax-year 2026
                  :fss-contribution (money/money 4500M :CAD)
                  :employer-qpp (money/money 4236M :CAD)
                  :employer-qpip (money/money 900M :CAD)
                  :contact {:name "P. Payroll"
                            :phone "514-555-0100"
                            :email "payroll@acme.qc.ca"}
                  :reference-number "SOM-2026-001"})
        xml-str (xml/emit-str (rl1-sum/summary->element summary))]
    (testing "NEQ emitted"
      (is (re-find #"<NEQ>1234567890</NEQ>" xml-str)))
    (testing "Numero identification emitted"
      (is (re-find #"<NumeroIdentification>NP123456</NumeroIdentification>"
                   xml-str)))
    (testing "Annee (year) emitted"
      (is (re-find #"<Annee>2026</Annee>" xml-str)))
    (testing "NombreReleves emitted"
      (is (re-find #"<NombreReleves>1</NombreReleves>" xml-str)))
    (testing "Box 21 (Quebec ITX) emitted"
      (is (re-find #"<Case21>3120\.00</Case21>" xml-str)))
    (testing "Box 22 (QPP employee) emitted"
      (is (re-find #"<Case22>4236\.00</Case22>" xml-str)))
    (testing "Box 23 (QPIP employee) emitted"
      (is (re-find #"<Case23>636\.00</Case23>" xml-str)))
    (testing "Box 27 (QPP employer) emitted"
      (is (re-find #"<Case27>4236\.00</Case27>" xml-str)))
    (testing "Box 28 (QPIP employer) emitted"
      (is (re-find #"<Case28>900\.00</Case28>" xml-str)))
    (testing "Box 30 (FSS) emitted"
      (is (re-find #"<Case30>4500\.00</Case30>" xml-str)))
    (testing "Employer name emitted"
      (is (re-find #"<Ligne1>Acme Québec Inc\.</Ligne1>" xml-str)))
    (testing "Reference number emitted"
      (is (re-find #"<NumeroSommaire>SOM-2026-001</NumeroSommaire>" xml-str)))
    (testing "Contact emitted"
      (is (re-find #"<Telephone>514-555-0100</Telephone>" xml-str)))))

(deftest summary-amended-emits-code-a
  (let [summary (rl1-sum/build-summary
                 {:slips []
                  :employer-neq "1234567890"
                  :employer-name "Acme"
                  :tax-year 2026
                  :report-type :amended})
        xml-str (xml/emit-str (rl1-sum/summary->element summary))]
    (testing "Code is A for amended"
      (is (re-find #"<CodeReleve>A</CodeReleve>" xml-str)))))

;; ============================================================================
;; submission envelope
;; ============================================================================

(deftest full-submission-envelope-shape
  (let [sophie-facts (vec (repeat 12 (qc-fact :emp/sophie
                                              {:gross 5500M :qc-itx 260M
                                               :qpp 353M :qpip 53M :ei 82M})))
        sophie-slip (rl1/payroll-facts->rl1-slip
                     {:facts sophie-facts
                      :employer-neq "1234567890"
                      :person sophie-person})
        summary (rl1-sum/build-summary
                 {:slips [sophie-slip]
                  :employer-neq "1234567890"
                  :employer-name "Acme Québec Inc."
                  :tax-year 2026
                  :fss-contribution (money/money 4500M :CAD)})
        sub (rl1-sum/submission
             {:transmitter {:transmetteur/np-number "NP000001"
                            :transmetteur/neq "1234567890"
                            :transmetteur/name "Acme Québec Inc."
                            :transmetteur/contact
                            {:name "P. Payroll"
                             :phone "514-555-0100"
                             :email "payroll@acme.qc.ca"}}
              :slips [(rl1/slip->element sophie-slip)]
              :summary summary})
        xml-str (rl1-sum/emit-string sub)]
    (testing "Root is <Releves>"
      (is (re-find #"<Releves>" xml-str)))
    (testing "Transmitter present"
      (is (re-find #"<Transmetteur>" xml-str))
      (is (re-find #"<NumeroTransmetteur>NP000001</NumeroTransmetteur>"
                   xml-str)))
    (testing "Releve1 slip present"
      (is (re-find #"<Releve1>" xml-str)))
    (testing "Sommaire1 present"
      (is (re-find #"<Sommaire1>" xml-str)))
    (testing "Slip + Summary box totals match"
      ;; Slip Box E = 3120; Summary Box 21 = 3120 (single slip)
      (is (re-find #"<CaseE>3120\.00</CaseE>" xml-str))
      (is (re-find #"<Case21>3120\.00</Case21>" xml-str)))))
