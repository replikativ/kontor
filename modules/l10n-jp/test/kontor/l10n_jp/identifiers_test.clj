(ns kontor.l10n-jp.identifiers-test
  "Tests for Japanese Corporate Number + Qualified Invoice Issuer
   Registration Number validators.

   Test fixtures use Corporate Numbers from the NTA's published
   `国の機関 (行政機関)` list (administrative-agency Corporate Numbers,
   publicly searchable on the Corporate Number Publication Site at
   houjin-bangou.nta.go.jp). They are real, government-assigned, and
   independently verifiable — but contain no private-sector business
   PII. The worked-example fixture `8700110005901` comes from the
   NTA's own check-digit-calculation spec PDF
   (houjin-bangou.nta.go.jp/documents/checkdigit.pdf)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-jp.identifiers :as id]))

;; ============================================================================
;; Corporate Number (法人番号) — 13-digit MOD-9
;; ============================================================================

(deftest corporate-number-valid-nta-worked-example
  (testing "NTA's own worked example from the published spec PDF"
    (is (id/valid-corporate-number? "8700110005901")
        "Base 700110005901 → check 8 (per NTA checkdigit.pdf)")))

(deftest corporate-number-valid-nta-agency-fixtures
  (testing "Real Corporate Numbers from NTA's published 国の機関 list.
            These are verifiable on the public Corporate Number search
            site (houjin-bangou.nta.go.jp); no business PII embedded."
    (is (id/valid-corporate-number? "3000012010001") "内閣官房")
    (is (id/valid-corporate-number? "2000012010002") "人事院")
    (is (id/valid-corporate-number? "1000012010003") "内閣法制局")
    (is (id/valid-corporate-number? "9000012010004") "国家安全保障会議")
    (is (id/valid-corporate-number? "8000012010005") "高度情報通信ネットワーク戦略本部")
    (is (id/valid-corporate-number? "7000012010006") "都市再生本部")
    (is (id/valid-corporate-number? "6000012010007") "構造改革特別区域推進本部")
    (is (id/valid-corporate-number? "5000012010008") "知的財産戦略本部")
    (is (id/valid-corporate-number? "4000012010009") "地球温暖化対策推進本部")
    (is (id/valid-corporate-number? "2000012010010") "地域再生本部")
    (is (id/valid-corporate-number? "1000012010011") "郵政民営化推進本部")
    (is (id/valid-corporate-number? "9000012010012") "中心市街地活性化本部")))

(deftest corporate-number-tolerates-formatting
  (testing "Hyphen-separated forms occasionally appear on PR material"
    (is (id/valid-corporate-number? "8700-1100-0590-1"))
    (is (id/valid-corporate-number? "8700 1100 0590 1"))
    (is (id/valid-corporate-number? "  8700110005901  "))))

(deftest corporate-number-rejects-wrong-check
  (testing "Tampering with any digit breaks the MOD-9 check"
    (is (not (id/valid-corporate-number? "9700110005901"))
        "Check 9 instead of 8")
    (is (not (id/valid-corporate-number? "7700110005901"))
        "Check 7 instead of 8")
    (is (not (id/valid-corporate-number? "8700110005902"))
        "Last base digit flipped")
    (is (not (id/valid-corporate-number? "3000012010002"))
        "内閣官房 with last digit tampered")))

(deftest corporate-number-rejects-leading-zero
  (testing "Leading 0 is structurally impossible — the algorithm
            produces a check digit in 1..9, never 0"
    (is (not (id/valid-corporate-number? "0700110005901"))
        "Leading 0 — invalid by spec")
    (is (not (id/valid-corporate-number? "0000012010001"))
        "All-zero except final digit — leading 0 → reject")))

(deftest corporate-number-rejects-wrong-length
  (testing "Length must be exactly 13 digits"
    (is (not (id/valid-corporate-number? "")))
    (is (not (id/valid-corporate-number? "870011000590"))
        "12 digits — could be misread as a 会社法人等番号 fragment")
    (is (not (id/valid-corporate-number? "87001100059010"))
        "14 digits")
    (is (not (id/valid-corporate-number? "870011")))))

(deftest corporate-number-rejects-non-digit
  (testing "Non-digit characters (other than allowed formatting) reject"
    (is (not (id/valid-corporate-number? "8700110005901X")))
    (is (not (id/valid-corporate-number? "T8700110005901"))
        "T prefix belongs on the QIS number, not the bare Corporate Number")
    (is (not (id/valid-corporate-number? "8.7001100.05901"))
        "Period is not a recognised separator")))

(deftest corporate-number-rejects-nil-and-non-string
  (is (not (id/valid-corporate-number? nil)))
  (is (not (id/valid-corporate-number? 8700110005901)))
  (is (not (id/valid-corporate-number? :8700110005901))))

(deftest corporate-number-detects-most-single-digit-errors
  (testing "Flipping any single digit (by +1 mod 10) invalidates the
            Corporate Number EXCEPT for the well-known 0↔9 swap that
            MOD-9 cannot distinguish (9 ≡ 0 mod 9). This is a
            documented limitation of the NTA scheme (see
            digitalforensic.jp/2016/07/25/column422/). For each
            position we check that *some* perturbation is detected;
            for non-9 digits a +1 delta is detected, and we still
            verify position-10's `9` is caught by a +2 delta."
    (let [valid "8700110005901"]
      (doseq [i (range 13)]
        (let [c (.charAt ^String valid i)
              d (- (long c) (int \0))
              ;; Pick a perturbation whose contribution to the
              ;; weighted sum is non-zero mod 9.
              delta (if (= d 9) 2 1)
              new-d (mod (+ d delta) 10)
              tampered (str (subs valid 0 i)
                            (char (+ (int \0) new-d))
                            (subs valid (inc i)))]
          (is (not (id/valid-corporate-number? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest parse-corporate-number-decomposes
  (let [r (id/parse-corporate-number "8700110005901")]
    (is (= "8" (:check r)))
    (is (= "700110005901" (:base r)))
    (is (= "8700110005901" (:canonical r)))
    (testing "Base matches the 12-digit 会社法人等番号 form
              registered corporations get from the MoJ"
      (is (= 12 (count (:base r))))))
  (testing "Formatted input normalises in canonical"
    (let [r (id/parse-corporate-number "8700-1100-0590-1")]
      (is (= "8700110005901" (:canonical r))
          "Hyphens stripped"))))

(deftest parse-corporate-number-invalid-returns-nil
  (is (nil? (id/parse-corporate-number "abc")))
  (is (nil? (id/parse-corporate-number nil)))
  (is (nil? (id/parse-corporate-number "0700110005901"))
      "Leading zero → invalid → nil"))

(deftest assert-corporate-number-throws
  (is (= "8700110005901" (id/assert-corporate-number! "8700110005901")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-corporate-number! "9700110005901")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-corporate-number! nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"法人番号"
                        (id/assert-corporate-number! "invalid"))))

;; ============================================================================
;; Qualified Invoice Issuer Number (適格請求書発行事業者登録番号 / T-number)
;; ============================================================================

(deftest qii-valid-corporate-body
  (testing "QIS T-number where the 13-digit body is a valid Corporate
            Number (the common case for registered corporations)"
    (is (id/valid-qualified-invoice-issuer-number? "T8700110005901"))
    (is (id/valid-qualified-invoice-issuer-number? "T3000012010001")
        "内閣官房 body, hypothetical as QIS issuer")
    (is (id/valid-qualified-invoice-issuer-number? "T9000012010012"))))

(deftest qii-tolerates-formatting
  (testing "T-number with internal hyphens — invoice PDFs sometimes
            render the body grouped 4-4-4-1"
    (is (id/valid-qualified-invoice-issuer-number? "T8700-1100-0590-1"))
    (is (id/valid-qualified-invoice-issuer-number? "T 8700 1100 0590 1"))
    (is (id/valid-qualified-invoice-issuer-number? "  T8700110005901  "))))

(deftest qii-rejects-missing-prefix
  (testing "Bare Corporate Number is NOT a valid QIS number —
            consumption-tax input credit machinery keys off the T."
    (is (not (id/valid-qualified-invoice-issuer-number? "8700110005901"))
        "No T prefix")
    (is (not (id/valid-qualified-invoice-issuer-number? "t8700110005901"))
        "Lowercase t — case matters per NTA spec")))

(deftest qii-rejects-bad-body
  (testing "Body must be a valid Corporate Number"
    (is (not (id/valid-qualified-invoice-issuer-number? "T9700110005901"))
        "Wrong check digit in body")
    (is (not (id/valid-qualified-invoice-issuer-number? "T0700110005901"))
        "Leading 0 in body")
    (is (not (id/valid-qualified-invoice-issuer-number? "T870011000590"))
        "12-digit body, total 13")
    (is (not (id/valid-qualified-invoice-issuer-number? "T87001100059010"))
        "14-digit body")))

(deftest qii-rejects-nil-and-non-string
  (is (not (id/valid-qualified-invoice-issuer-number? nil)))
  (is (not (id/valid-qualified-invoice-issuer-number? "")))
  (is (not (id/valid-qualified-invoice-issuer-number? 8700110005901))))

(deftest parse-qii-decomposes
  (let [r (id/parse-qualified-invoice-issuer-number "T8700110005901")]
    (is (= "8" (:check r)))
    (is (= "700110005901" (:base r)))
    (is (= "8700110005901" (:corporate-number r)))
    (is (= "T8700110005901" (:canonical r))))
  (testing "Hyphenated body normalises in canonical"
    (let [r (id/parse-qualified-invoice-issuer-number "T8700-1100-0590-1")]
      (is (= "T8700110005901" (:canonical r)))
      (is (= "8700110005901" (:corporate-number r))
          "Embedded Corporate Number reconcilable against NTA register"))))

(deftest parse-qii-invalid-returns-nil
  (is (nil? (id/parse-qualified-invoice-issuer-number "8700110005901"))
      "No T prefix → invalid → nil")
  (is (nil? (id/parse-qualified-invoice-issuer-number "T9700110005901"))
      "Bad check digit → nil")
  (is (nil? (id/parse-qualified-invoice-issuer-number nil))))

(deftest assert-qii-throws
  (is (= "T8700110005901"
         (id/assert-qualified-invoice-issuer-number! "T8700110005901")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-qualified-invoice-issuer-number! "8700110005901")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-qualified-invoice-issuer-number! "T9700110005901")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"適格請求書"
                        (id/assert-qualified-invoice-issuer-number! "bogus"))))
