(ns kontor.payroll-jp.emit-test
  "Tests for JpPayrollEmitProvider + the Gensen audit-doc builder +
   My Number attestation discipline (ADR-084 §1)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.emit :as emit]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; JpPayrollEmitProvider — payroll-run summary audit-doc
;; ============================================================================

(deftest emit-provider-returns-payroll-filing-audit-doc
  (let [facts [{:employment 100
                :gross 340000M
                :net 264960M
                :components []}]
        provider (emit/->JpPayrollEmitProvider {:language :ja})
        docs (pp/emit-payroll-events provider facts
                                     {:pay-period-eid 200 :entity-eid 300})]
    (testing "One audit-doc per payroll run"
      (is (= 1 (count docs))))
    (testing "Category is :payroll-filing per the canonical vocabulary"
      (is (= :payroll-filing (:kontor.audit-doc/category (first docs)))))
    (testing "Language defaults to :ja"
      (is (= :ja (:kontor.audit-doc/language (first docs)))))
    (testing "Code references pay-period + entity"
      (is (str/includes? (:kontor.audit-doc/code (first docs)) "200"))
      (is (str/includes? (:kontor.audit-doc/code (first docs)) "300")))))

(deftest emit-provider-honors-language-override
  (let [provider (emit/->JpPayrollEmitProvider {:language :en})
        docs (pp/emit-payroll-events provider []
                                     {:pay-period-eid 1 :entity-eid 1})]
    (testing "Language flag passes through"
      (is (= :en (:kontor.audit-doc/language (first docs)))))))

;; ============================================================================
;; build-gensen-audit-doc-tx-data
;; ============================================================================

(def sample-statement
  {:gensen/tax-year 2026
   :gensen/employee {:given-name "太郎"
                     :family-name "田中"
                     :my-number-present? true}
   :gensen/employer {:name "Acme株式会社"
                     :corporate-number "8700110005901"}
   :gensen/payment-amount 4080000M
   :gensen/withholding-amount 96000M
   :gensen/social-insurance-paid 588480M})

(deftest gensen-audit-doc-has-payroll-filing-category-and-ja-language
  (let [[doc] (emit/build-gensen-audit-doc-tx-data
               {:statement sample-statement})]
    (testing "Category is :payroll-filing"
      (is (= :payroll-filing (:kontor.audit-doc/category doc))))
    (testing "Language is :ja by default"
      (is (= :ja (:kontor.audit-doc/language doc))))
    (testing "Code is deterministic from employer + tax-year + name"
      (is (str/includes? (:kontor.audit-doc/code doc) "8700110005901"))
      (is (str/includes? (:kontor.audit-doc/code doc) "2026"))
      (is (str/includes? (:kontor.audit-doc/code doc) "田中")))
    (testing "Title includes the Gensen banner"
      (is (str/includes? (:kontor.audit-doc/title doc) "源泉徴収票")))
    (testing "Description does not contain the My Number value"
      (let [desc (:kontor.audit-doc/description doc)]
        (is (not (re-find #"\d{12}" desc))
            "12-digit My Number must not appear in description")))))

(deftest gensen-audit-doc-honors-language
  (let [[doc] (emit/build-gensen-audit-doc-tx-data
               {:statement sample-statement
                :language :en})]
    (testing "EN language honored"
      (is (= :en (:kontor.audit-doc/language doc)))
      (is (str/includes? (:kontor.audit-doc/title doc) "EN")))))

(deftest gensen-audit-doc-attaches-storage-uri
  (let [[doc] (emit/build-gensen-audit-doc-tx-data
               {:statement sample-statement
                :storage-uri "s3://archive/2026/gensen/E001.pdf"})]
    (testing "Storage URI present"
      (is (= "s3://archive/2026/gensen/E001.pdf"
             (:kontor.audit-doc/storage-uri doc))))))

(deftest build-gensen-submission-audit-docs-emits-per-statement
  (let [statements [sample-statement
                    (assoc-in sample-statement [:gensen/employee :family-name] "鈴木")]
        docs (emit/build-gensen-submission-audit-docs-tx-data
              {:statements statements})]
    (testing "One audit-doc per statement"
      (is (= 2 (count docs))))
    (testing "Each carries :payroll-filing + :ja"
      (is (every? #(= :payroll-filing (:kontor.audit-doc/category %)) docs))
      (is (every? #(= :ja (:kontor.audit-doc/language %)) docs)))))

;; ============================================================================
;; record-my-number-attestation-tx-data (ADR-084 §1 PII discipline)
;; ============================================================================

(deftest my-number-attestation-is-pii-sensitive-hr-personnel
  (let [[doc] (emit/record-my-number-attestation-tx-data
               {:person 100
                :tax-year 2026
                :document-type :my-number-card
                :attested-at #inst "2026-01-15"
                :attested-by-uid :uid/hr-officer-001})]
    (testing "Category is :hr-personnel (ADR-084 §1)"
      (is (= :hr-personnel (:kontor.audit-doc/category doc))))
    (testing "Privilege is :pii-sensitive (ADR-051 facet)"
      (is (= :pii-sensitive (:kontor.audit-doc/privilege doc))))
    (testing "Language is :ja by default"
      (is (= :ja (:kontor.audit-doc/language doc))))
    (testing "Type is :pii-attestation"
      (is (= :pii-attestation (:kontor.audit-doc/type doc))))
    (testing "Audit-doc records the attestation date"
      (is (= #inst "2026-01-15" (:kontor.audit-doc/uploaded-at doc))))
    (testing "uploaded-by-uid stamped"
      (is (= :uid/hr-officer-001 (:kontor.audit-doc/uploaded-by-uid doc))))
    (testing "Description carries the document-type label NOT a My Number value"
      (let [desc (:kontor.audit-doc/description doc)]
        (is (str/includes? desc "マイナンバーカード"))
        ;; No 12-digit number string in the description.
        (is (not (re-find #"\d{12}" desc)))))))

(deftest my-number-attestation-code-is-deterministic
  (let [[doc1] (emit/record-my-number-attestation-tx-data
                {:person 100
                 :tax-year 2026
                 :document-type :my-number-card
                 :attested-at #inst "2026-01-15"
                 :attested-by-uid :uid/hr})
        [doc2] (emit/record-my-number-attestation-tx-data
                {:person 100
                 :tax-year 2026
                 :document-type :my-number-card
                 :attested-at #inst "2026-02-15" ; later date
                 :attested-by-uid :uid/hr})]
    (testing "Two attestations for the same (person, tax-year) share the same :code"
      (is (= (:kontor.audit-doc/code doc1) (:kontor.audit-doc/code doc2))))))

(deftest my-number-attestation-rejects-missing-mandatory-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":document-type required"
                        (emit/record-my-number-attestation-tx-data
                         {:person 100
                          :tax-year 2026
                          ;; missing :document-type
                          :attested-at #inst "2026-01-15"
                          :attested-by-uid :uid/hr}))))

(deftest my-number-attestation-honors-storage-uri
  (let [[doc] (emit/record-my-number-attestation-tx-data
               {:person 100
                :tax-year 2026
                :document-type :my-number-card
                :attested-at #inst "2026-01-15"
                :attested-by-uid :uid/hr
                :storage-uri "vault://hr/my-numbers/100"})]
    (testing "Storage URI lands on the audit-doc"
      (is (= "vault://hr/my-numbers/100" (:kontor.audit-doc/storage-uri doc))))))

(deftest my-number-attestation-supports-open-set-document-types
  (let [[doc] (emit/record-my-number-attestation-tx-data
               {:person 100
                :tax-year 2026
                ;; Consumer-extended doc-type beyond the standard 4.
                :document-type :gaikokujin-toroku-card
                :attested-at #inst "2026-01-15"
                :attested-by-uid :uid/hr})]
    (testing "Falls back to the keyword name when not in standard map"
      (is (str/includes? (:kontor.audit-doc/title doc) "gaikokujin-toroku-card")))))

;; ============================================================================
;; My Number leak detection — pii-employees-in-facts
;; ============================================================================

(deftest pii-detector-finds-leaked-my-number
  (let [clean-facts [{:employment :emp/clean
                      :gross 340000M :net 264960M
                      :components []
                      :jurisdiction-specific-codes {:engine :freee}}]
        leaky-facts [{:employment :emp/leaky
                      :gross 340000M :net 264960M
                      :components []
                      :jurisdiction-specific-codes
                      {:engine :freee
                       :my-number "123456789012"}}]]
    (testing "Clean facts produce empty leak-set"
      (is (empty? (emit/pii-employees-in-facts clean-facts))))
    (testing "Leaky fact surfaced"
      (is (= #{:emp/leaky} (emit/pii-employees-in-facts leaky-facts))))))

(deftest pii-detector-recognizes-kanji-key
  (let [leaky-facts [{:employment :emp/leaky-kanji
                      :gross 340000M :net 264960M
                      :components []
                      :jurisdiction-specific-codes
                      {:engine :freee
                       :個人番号 "123456789012"}}]]
    (testing "Kanji-keyed leak surfaced too"
      (is (= #{:emp/leaky-kanji} (emit/pii-employees-in-facts leaky-facts))))))

(deftest warn-if-leaked-returns-offenders
  (let [leaky-facts [{:employment :emp/leaky
                      :gross 340000M :net 264960M
                      :components []
                      :jurisdiction-specific-codes
                      {:my-number "123456789012"}}]
        offenders (emit/warn-if-my-number-leaked! leaky-facts)]
    (testing "Warning produces the offending set"
      (is (= #{:emp/leaky} offenders)))))
