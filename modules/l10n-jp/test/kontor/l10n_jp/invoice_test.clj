(ns kontor.l10n-jp.invoice-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-jp.chart :as chart]
            [kontor.l10n-jp.invoice :as inv]
            [kontor.money :as money]
            [kontor.validation :as v]))

(deftest registration-number-validation
  (testing "Valid: T followed by exactly 13 digits"
    (is (inv/registration-number-valid? "T1234567890123"))
    (is (inv/registration-number-valid? "T0000000000001")))
  (testing "Invalid formats"
    (is (not (inv/registration-number-valid? "T123")))           ; too short
    (is (not (inv/registration-number-valid? "T12345678901234")))  ; too long
    (is (not (inv/registration-number-valid? "X1234567890123")))   ; wrong prefix
    (is (not (inv/registration-number-valid? "1234567890123")))    ; missing T
    (is (not (inv/registration-number-valid? "T123456789012A")))   ; non-digit
    (is (not (inv/registration-number-valid? nil)))
    (is (not (inv/registration-number-valid? "")))))

(deftest assert-throws-on-invalid
  (is (thrown? clojure.lang.ExceptionInfo
               (inv/assert-registration-number! "not-a-number")))
  (is (= "T1234567890123" (inv/assert-registration-number! "T1234567890123"))))

(deftest qis-field-validation-empty
  (testing "Empty invoice → all required fields missing"
    (let [missing (inv/validate-qis-fields {})]
      (is (= (count missing) (count inv/required-fields)))
      (is (every? #(= :missing-or-blank (:issue %)) missing)))))

(deftest qis-field-validation-complete
  (testing "All required fields present → no complaints"
    (let [complete {:issuer/name "Acme KK"
                    :issuer/registration-number "T1234567890123"
                    :transaction/date #inst "2026-01-15"
                    :buyer/name "Beta KK"
                    :line-items/by-rate [{:rate :10pct :amount 1000}]
                    :totals/taxable-amount-by-rate {:10pct 1000}
                    :totals/tax-amount-by-rate {:10pct 100}}]
      (is (empty? (inv/validate-qis-fields complete))))))

;; ============================================================================
;; Posting builder — ADR-071 provider/builder composition (research note 100)
;; ============================================================================

(def jan-15 #inst "2026-01-15T00:00:00Z")

(defn- bootstrap
  "Fresh in-memory DB with the JP chart + an INV journal installed."
  []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                       :journal/type :sale :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- bal
  "JPY balance amount (BigDecimal) on the account with `code`. The
   kernel returns Money keyed by the commodity *eid*; we compare bare
   amounts so the test is agnostic to symbol-vs-eid commodity keys."
  [conn code]
  (let [jpy-eid (:db/id (d/entity (d/db conn) [:kontor.commodity/symbol "JPY"]))]
    (-> (balance/account-balance conn (ace (d/db conn) code))
        (get jpy-eid (money/money 0M :JPY))
        :amount)))

(deftest plan-jp-invoice-single-10pct
  (testing "100,000 JPY sale at 10% → balanced tx with the right legs"
    (let [conn (bootstrap)
          tx   (inv/plan-jp-invoice-tx-data
                (d/db conn)
                {:invoice/external-id "INV-1"
                 :invoice/issue-date jan-15
                 :invoice/lines [{:invoice-line/line-total 100000M
                                  :invoice-line/jct-class :standard}]}
                {})]
      (is (vector? tx))
      (v/transact-with-validation conn tx)
      (is (== 110000M (bal conn "121000")) "AR gross")
      (is (== -100000M (bal conn "411000")) "revenue 10% credit")
      (is (== -10000M (bal conn "215100")) "output JCT 10% credit"))))

(deftest post-jp-invoice-reduced-8pct
  (testing "1,000 JPY food sale at 8% routes to the reduced accounts"
    (let [conn (bootstrap)]
      (inv/post-jp-invoice!
       conn
       {:invoice/external-id "INV-2"
        :invoice/issue-date jan-15
        :invoice/lines [{:invoice-line/quantity 2
                         :invoice-line/unit-price 500M
                         :invoice-line/jct-class :reduced}]})
      (is (== 1080M (bal conn "121000")) "AR gross 1,000 + 80")
      (is (== -1000M (bal conn "412000")) "reduced revenue")
      (is (== -80M (bal conn "215200")) "output JCT 8%"))))

(deftest post-jp-invoice-mixed-rates
  (testing "10% + 8% lines aggregate per account; one balanced tx"
    (let [conn (bootstrap)]
      (inv/post-jp-invoice!
       conn
       {:invoice/external-id "INV-3"
        :invoice/issue-date jan-15
        :invoice/lines [{:invoice-line/line-total 100000M
                         :invoice-line/jct-class :standard}
                        {:invoice-line/line-total 100000M
                         :invoice-line/jct-class :standard}
                        {:invoice-line/line-total 50000M
                         :invoice-line/jct-class :reduced}]})
      ;; gross = 200,000 + 50,000 + 20,000 + 4,000 = 274,000
      (is (== 274000M (bal conn "121000")))
      (is (== -200000M (bal conn "411000")) "two 10% lines summed")
      (is (== -50000M (bal conn "412000")))
      (is (== -20000M (bal conn "215100")) "10% of 200,000")
      (is (== -4000M (bal conn "215200")) "8% of 50,000"))))

(deftest post-jp-invoice-export-exempt-no-jct-leg
  (testing "export-exempt line emits no JCT — gross equals net"
    (let [conn (bootstrap)]
      (inv/post-jp-invoice!
       conn
       {:invoice/external-id "INV-4"
        :invoice/issue-date jan-15
        :invoice/lines [{:invoice-line/line-total 300000M
                         :invoice-line/jct-class :export-exempt}]})
      (is (== 300000M (bal conn "121000")) "no JCT added")
      (is (== -300000M (bal conn "414000")) "zero-rated export revenue")
      (is (== 0M (bal conn "215100")))
      (is (== 0M (bal conn "215200"))))))

(deftest post-jp-invoice-non-taxable-no-jct-leg
  (testing "non-taxable line emits no JCT — routes to 413000"
    (let [conn (bootstrap)]
      (inv/post-jp-invoice!
       conn
       {:invoice/external-id "INV-5"
        :invoice/issue-date jan-15
        :invoice/lines [{:invoice-line/line-total 50000M
                         :invoice-line/jct-class :non-taxable}]})
      (is (== 50000M (bal conn "121000")))
      (is (== -50000M (bal conn "413000")) "non-taxable revenue")
      (is (== 0M (bal conn "215100"))))))

(deftest validate-invoice-flags-bad-class
  (testing "validate-invoice rejects an unknown JCT class"
    (let [complaints (inv/validate-invoice
                      {:invoice/external-id "INV-6"
                       :invoice/issue-date jan-15
                       :invoice/lines [{:invoice-line/line-total 100M
                                        :invoice-line/jct-class :bogus}]})]
      (is (some #(= :invoice-line/jct-class (:field %)) complaints)))))
