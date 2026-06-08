(ns kontor.l10n-jp.investment-income-provider-test
  "Tests for the JP investment-income provider.

   Covers:
   - Three-election framework (申告不要 / 申告分離 / 総合).
   - 復興 2.1 % surtax fires on national pass (REUSED from JP CGT).
   - NISA / iDeCo exemption — drop slice entirely.
   - 3 % 大口株主 cliff — election validator rejects 申告分離 / 申告不要.
   - 配当控除 10 % / 5 % gradation on the income threshold.
   - §95 foreign-tax-credit reduces 申告分離 liability.
   - Worked examples from(Mr Tanaka, Mr Suzuki)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.cgt-statute :as cgt-statute]
            [kontor.l10n-jp.investment-income-provider :as inv]
            [kontor.l10n-jp.investment-income-statute :as inv-statute]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with the JP CGT statute (for the 復興 provision) + the
   JP investment-income statute installed."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "JPY" :kontor.commodity/name "Japanese Yen"
                       :kontor.commodity/precision 0}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- run-provider
  "Build an individual provider, call `period-tax-facts`."
  [conn sources & [extra-inputs extra-ctx]]
  (let [provider (inv/jp-individual-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity nil
             :period p2026
             :inputs (merge {:investment-income-sources sources}
                            extra-inputs)}
            extra-ctx))))

(defn- run-corp-provider
  [conn sources]
  (let [provider (inv/jp-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     {:db (d/db conn)
      :entity nil
      :period p2026
      :inputs {:investment-income-sources sources}})))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(defn- component-by-source [facts source-id]
  (->> (:components facts)
       (filter #(= source-id (get-in % [:jurisdiction-specific-codes :source-id])))
       first))

;; ============================================================================
;; §1. 申告不要 — informational component with WHT as final
;; ============================================================================

(deftest shinkokufuyō-default-listed-dividend
  (testing "申告不要 listed dividend → composite 20.315 %, withheld = liability"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "toyota"
                   :asset-class :jp-listed-non-major
                   :amount      1000000M
                   :withheld    203150M}]
                 {:jp-dividend-elections {"toyota" :申告不要}})
          c     (component-by-source facts "toyota")]
      (is (some? c))
      (is (= :申告不要 (:regime c)))
      ;; 15 % × 1,000,000 = 150,000 national; +2.1 % = 153,150;
      ;; +5 % × 1,000,000 = 50,000 local; total = 203,150
      (is (== 203150M (-> c :liability :amount)))
      (is (== 203150M (-> c :prepaid :amount)) "withheld becomes prepaid"))))

;; ============================================================================
;; §2. 申告分離 — listed compartment with carry-in
;; ============================================================================

(deftest shinkokubunri-with-loss-carry-in
  (testing "申告分離 elected → carry-in reduces base; refundable diff"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "mitsubishi"
                   :asset-class :jp-listed-non-major
                   :amount      400000M
                   :withheld    81260M}]
                 {:jp-dividend-elections {"mitsubishi" :申告分離}
                  :capital-loss-carryforward {:jp-listed-securities 300000M}})
          c     (component-by-source facts "mitsubishi")]
      (is (some? c))
      (is (= :申告分離 (:regime c)))
      ;; Net base = 400k − 300k = 100k; 15 % = 15,000; +2.1 % = 15,315;
      ;; +5 % × 100k = 5,000; total = 20,315
      (is (== 100000M (-> c :base :amount)))
      (is (== 20315M (-> c :liability :amount)))
      (is (== 81260M (-> c :prepaid :amount))))))

(deftest shinkokubunri-no-carry
  (testing "申告分離 with no carry behaves identically to 申告不要 rate-wise"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "src"
                   :asset-class :jp-listed-non-major
                   :amount      1000000M
                   :withheld    203150M}]
                 {:jp-dividend-elections {"src" :申告分離}})
          c     (component-by-source facts "src")]
      (is (== 1000000M (-> c :base :amount)))
      (is (== 203150M (-> c :liability :amount))))))

;; ============================================================================
;; §3. 復興 surtax — reused JP CGT provision fires here too
;; ============================================================================

(deftest fukko-surtax-reused-from-cgt
  (testing "復興 2.1 % surtax fires via the reused JP CGT statute provision"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "src"
                   :asset-class :jp-listed-non-major
                   :amount      2000000M
                   :withheld    0M}]
                 {:jp-dividend-elections {"src" :申告分離}})
          c     (component-by-source facts "src")
          surtax-line (->> c :line-items
                           (filter #(= :reconstruction-surtax (:line %)))
                           first)
          surtax-item (->> c :surtaxes
                           (filter #(= :reconstruction-surtax (:code %)))
                           first)]
      ;; 2M × 15 % × 2.1 % = 6,300
      (is (some? surtax-item) "surtax recorded in :surtaxes")
      (is (== 6300M (:amount surtax-item)))
      (is (some? surtax-line) "surtax recorded in :line-items")
      (is (== 6300M (-> surtax-line :value :amount))))))

;; ============================================================================
;; §4. NISA — exempt slice dropped
;; ============================================================================

(deftest nisa-exempt-slice-dropped
  (testing "a NISA-flagged source produces NO component"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "sony-nisa"
                   :asset-class :jp-listed-non-major
                   :amount      100000M
                   :exemption-claimed #{:jp-nisa}}
                  {:source-id  "regular"
                   :asset-class :jp-listed-non-major
                   :amount      500000M
                   :withheld    101575M}]
                 {:jp-dividend-elections {"sony-nisa" :申告不要
                                          "regular"   :申告不要}})]
      (is (nil? (component-by-source facts "sony-nisa"))
          "NISA slice produces no component — fully exempt")
      (is (some? (component-by-source facts "regular"))
          "non-NISA slice still emits a component")
      (is (= 1 (count (:components facts)))))))

(deftest ideco-exempt-slice-dropped
  (testing "an iDeCo-flagged source is also dropped"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "ideco-pos"
                   :asset-class :jp-listed-non-major
                   :amount      500000M
                   :exemption-claimed #{:jp-ideco}}]
                 {:jp-dividend-elections {"ideco-pos" :申告不要}})]
      (is (empty? (:components facts))))))

;; ============================================================================
;; §5. 3 % 大口株主 cliff — election validator forces 総合
;; ============================================================================

(deftest major-shareholder-cliff-rejects-shinkokufuyō
  (testing "≥3 % shareholder cannot elect 申告不要 — validator throws"
    (let [conn (fresh)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"election not permitted"
           (run-provider
            conn
            [{:source-id  "large-stake"
              :asset-class :jp-listed-major-3%
              :amount      5000000M}]
            {:jp-dividend-elections {"large-stake" :申告不要}}))))))

(deftest major-shareholder-cliff-rejects-shinkokubunri
  (testing "≥3 % shareholder cannot elect 申告分離 either"
    (let [conn (fresh)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"election not permitted"
           (run-provider
            conn
            [{:source-id  "large-stake"
              :asset-class :jp-listed-major-3%
              :amount      5000000M}]
            {:jp-dividend-elections {"large-stake" :申告分離}}))))))

(deftest major-shareholder-must-use-sogo
  (testing "≥3 % shareholder MUST use 総合 — succeeds and emits sogo component"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "large-stake"
                   :asset-class :jp-listed-major-3%
                   :amount      5000000M}]
                 {:jp-dividend-elections {"large-stake" :sogo}
                  :total-taxable-income 8000000M})
          c     (component-by-source facts "large-stake")]
      (is (some? c))
      (is (= :sogo (:regime c))))))

;; ============================================================================
;; §6. 配当控除 — 10 % credit on the under-threshold slice
;; ============================================================================

(deftest haitō-kōjo-10pct-low-income
  (testing "総合 elected; total income under ¥10M → 10 % credit + 2.8 % jūmin"
    (let [conn (fresh)
          ;; Mr Tanaka — ¥1M dividend; pre-existing income places total
          ;; ≈ ¥6.18M (under ¥10M threshold). Credit = 10 % of dividend.
          facts (run-provider
                 conn
                 [{:source-id  "toyota-sogo"
                   :asset-class :jp-listed-non-major
                   :amount      1000000M}]
                 {:jp-dividend-elections {"toyota-sogo" :sogo}
                  :total-taxable-income 6180000M})
          c     (component-by-source facts "toyota-sogo")
          national-credit (->> c :line-items
                               (filter #(= :haitō-kōjo-national (:line %)))
                               first :value :amount)
          local-credit (->> c :line-items
                            (filter #(= :haitō-kōjo-jūmin (:line %)))
                            first :value :amount)
          pit-credits (get-in c [:jurisdiction-specific-codes :pit-credits])]
      (is (some? c))
      (is (= :sogo (:regime c)))
      ;; National credit = 10 % × 1M = 100,000 (negative in line-items)
      (is (== -100000M national-credit))
      ;; Local credit = 2.8 % × 1M = 28,000
      (is (== -28000M local-credit))
      (is (= 2 (count pit-credits)) "national + jūmin pit-credits emitted")
      (is (= [1000000M] (get-in c [:jurisdiction-specific-codes :pit-base-additions]))))))

(deftest haitō-kōjo-5pct-on-high-income-slice
  (testing "総合 elected; total taxable >¥10M → 10 % below / 5 % above the slice"
    (let [conn (fresh)
          ;; ¥3M dividend with ¥12M total taxable income. Non-dividend
          ;; portion = ¥9M; the dividend slice that pushes above ¥10M:
          ;; below = max(0, 10M − 9M) = 1M; above = 3M − 1M = 2M.
          ;; Credit = 1M × 10 % + 2M × 5 % = 100,000 + 100,000 = 200,000
          facts (run-provider
                 conn
                 [{:source-id  "high-div"
                   :asset-class :jp-listed-non-major
                   :amount      3000000M}]
                 {:jp-dividend-elections {"high-div" :sogo}
                  :total-taxable-income 12000000M})
          c     (component-by-source facts "high-div")
          national-credit (->> c :line-items
                               (filter #(= :haitō-kōjo-national (:line %)))
                               first :value :amount)]
      (is (== -200000M national-credit)
          "1M × 10 % + 2M × 5 % = ¥200,000 national credit"))))

(deftest haitō-kōjo-skipped-for-j-reit
  (testing "J-REIT distribution NOT eligible for 配当控除 even on 総合"
    (let [conn (fresh)
          facts (run-provider
                 conn
                 [{:source-id  "daiwa-reit"
                   :asset-class :j-reit
                   :amount      800000M}]
                 {:jp-dividend-elections {"daiwa-reit" :sogo}
                  :total-taxable-income 5000000M})
          c     (component-by-source facts "daiwa-reit")
          national-credit (->> c :line-items
                               (filter #(= :haitō-kōjo-national (:line %)))
                               first :value :amount)]
      (is (some? c))
      (is (== 0M national-credit) "no 配当控除 for J-REIT")
      (is (= [800000M] (get-in c [:jurisdiction-specific-codes :pit-base-additions]))
          "still folds into PIT base"))))

;; ============================================================================
;; §7. §95 foreign-tax credit
;; ============================================================================

(deftest foreign-tax-credit-reduces-shinkokubunri
  (testing "foreign-source 申告分離 — §95 credit caps at JP tax, reduces liability"
    (let [conn (fresh)
          ;; ¥300,000 Apple dividend; US WHT ¥4,500 treaty rate.
          ;; JP tax on slice: 300k × 15 % = 45,000 + 2.1 % = 45,945;
          ;; + 5 % × 300k = 15,000 local; total = ¥60,945.
          ;; FTC allowed = min(4500, 60945) = 4500; liability =
          ;; 60,945 − 4,500 = 56,445.
          facts (run-provider
                 conn
                 [{:source-id  "apple-adr"
                   :asset-class :foreign
                   :amount      300000M
                   :foreign-tax-paid 4500M}]
                 {:jp-dividend-elections {"apple-adr" :申告分離}})
          c     (component-by-source facts "apple-adr")]
      (is (some? c))
      (is (== 60945M (-> c :gross-liability :amount)))
      (is (== 56445M (-> c :liability :amount))
          "JP tax reduced by §95 foreign-tax credit")
      (is (== 4500M (get-in c [:jurisdiction-specific-codes
                               :foreign-tax-credit-allowed])))
      (is (== 0M (get-in c [:jurisdiction-specific-codes
                            :foreign-tax-carryforward-out]))))))

(deftest foreign-tax-credit-excess-carries-forward
  (testing "foreign WHT exceeds JP tax → excess goes to :foreign-tax-carryforward-out"
    (let [conn (fresh)
          ;; Tiny dividend, huge WHT (a hypothetical to exercise the cap).
          facts (run-provider
                 conn
                 [{:source-id  "high-wht"
                   :asset-class :foreign
                   :amount      10000M
                   :foreign-tax-paid 50000M}]
                 {:jp-dividend-elections {"high-wht" :申告分離}})
          c     (component-by-source facts "high-wht")
          jp-tax (-> c :gross-liability :amount)
          ftc-allowed (get-in c [:jurisdiction-specific-codes
                                 :foreign-tax-credit-allowed])
          carry (get-in c [:jurisdiction-specific-codes
                           :foreign-tax-carryforward-out])]
      (is (== jp-tax ftc-allowed) "credit caps at JP tax")
      (is (== 0M (-> c :liability :amount)) "liability floored at zero")
      (is (== (- 50000M jp-tax) carry)
          "excess WHT carries forward"))))

;; ============================================================================
;; §8. Bank interest — locked at 申告不要
;; ============================================================================

(deftest bank-interest-locked-at-shinkokufuyō
  (testing "bank interest can only elect 申告不要; any other election throws"
    (let [conn (fresh)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"election not permitted"
           (run-provider
            conn
            [{:source-id  "bank-1"
              :asset-class :bank-interest
              :amount      50000M
              :withheld    10158M}]
            {:jp-dividend-elections {"bank-1" :sogo}})))
      (let [facts (run-provider
                   conn
                   [{:source-id  "bank-1"
                     :asset-class :bank-interest
                     :amount      50000M
                     :withheld    10158M}]
                   {:jp-dividend-elections {"bank-1" :申告不要}})
            c     (component-by-source facts "bank-1")]
        (is (some? c))
        (is (= :申告不要 (:regime c)))))))

;; ============================================================================
;; §9. Mr Suzuki mixed-portfolio worked example
;; ============================================================================

(deftest mr-suzuki-mixed-portfolio
  (testing "multi-lane fan-out + NISA exemption + foreign credit"
    (let [conn (fresh)
          ;; The full Mr Suzuki portfolio from.
          sources [{:source-id "daiwa-reit"
                    :asset-class :j-reit
                    :amount 800000M
                    :withheld 162520M}
                   {:source-id "mitsubishi"
                    :asset-class :jp-listed-non-major
                    :amount 400000M
                    :withheld 81260M}
                   {:source-id "apple-adr"
                    :asset-class :foreign
                    :amount 300000M
                    :foreign-tax-paid 4500M}
                   {:source-id "jgb"
                    :asset-class :listed-bond-interest
                    :amount 200000M
                    :withheld 40630M}
                   {:source-id "sony-nisa"
                    :asset-class :jp-listed-non-major
                    :amount 100000M
                    :exemption-claimed #{:jp-nisa}}
                   {:source-id "bank-int"
                    :asset-class :bank-interest
                    :amount 50000M
                    :withheld 10158M}]
          facts (run-provider
                 conn
                 sources
                 {:jp-dividend-elections
                  {"daiwa-reit" :申告不要
                   "mitsubishi" :申告分離
                   "apple-adr"  :申告分離
                   "jgb"        :申告不要
                   "bank-int"   :申告不要}
                  :capital-loss-carryforward {:jp-listed-securities 300000M}})
          mitsubishi (component-by-source facts "mitsubishi")
          apple (component-by-source facts "apple-adr")]
      ;; NISA slice dropped: 5 components (6 sources − 1 NISA).
      (is (= 5 (count (:components facts))))
      (is (nil? (component-by-source facts "sony-nisa")))
      ;; Mitsubishi: 400k − 300k carry = 100k base × 20.315 % = 20,315
      (is (== 100000M (-> mitsubishi :base :amount)))
      (is (== 20315M (-> mitsubishi :liability :amount)))
      ;; Apple: 60,945 gross − 4,500 FTC = 56,445
      (is (== 56445M (-> apple :liability :amount))))))

;; ============================================================================
;; §10. Corporation — 受取配当等の益金不算入
;; ============================================================================

(deftest corp-100pct-exclusion-for-wholly-owned-subsidiary
  (testing "wholly-owned domestic subsidiary dividend → 100 % excluded"
    (let [conn (fresh)
          facts (run-corp-provider
                 conn
                 [{:source-id "wholly"
                   :asset-class :jp-listed-non-major
                   :amount 10000000M
                   :stake-pct 1.00M}])
          c (first (:components facts))]
      (is (some? c))
      (is (== 0M (-> c :base :amount)) "100 % exclusion → ¥0 taxable")
      (is (= [0M] (get-in c [:jurisdiction-specific-codes :cit-base-additions]))))))

(deftest corp-50pct-exclusion-for-mid-stake
  (testing ">5 % – <⅓ stake → 50 % exclusion"
    (let [conn (fresh)
          facts (run-corp-provider
                 conn
                 [{:source-id "mid"
                   :asset-class :jp-listed-non-major
                   :amount 10000000M
                   :stake-pct 0.10M}])
          c (first (:components facts))]
      (is (== 5000000M (-> c :base :amount))
          "50 % exclusion on ¥10M → ¥5M taxable"))))

(deftest corp-foreign-dividend-fully-taxable
  (testing "foreign-corporation dividend → 0 % exclusion (fully taxable)"
    (let [conn (fresh)
          facts (run-corp-provider
                 conn
                 [{:source-id "foreign-div"
                   :asset-class :foreign
                   :amount 5000000M
                   :stake-pct 1.00M}])
          c (first (:components facts))]
      (is (== 5000000M (-> c :base :amount))
          "foreign dividend fully taxable — no §23 exclusion"))))

;; ============================================================================
;; §11. Component kind + plumbing
;; ============================================================================

(deftest all-components-use-investment-income-tax-kind
  (let [conn (fresh)
        facts (run-provider
               conn
               [{:source-id  "src"
                 :asset-class :jp-listed-non-major
                 :amount      1000000M
                 :withheld    203150M}]
               {:jp-dividend-elections {"src" :申告不要}})]
    (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
        "every component uses :investment-income-tax")))

(deftest empty-sources-returns-zero-components
  (testing "no sources → empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn [])]
      (is (empty? (:components facts))))))

(deftest invalid-kind-throws
  (testing "an unknown :kind raises"
    (let [conn (fresh)
          bad (inv/->JpInvestmentIncomeTaxProvider :bogus :jp-nta :JPY "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn)
                                  :entity nil
                                  :period p2026
                                  :inputs {:investment-income-sources []}}))))))

(deftest unknown-asset-class-throws
  (testing "validator rejects an unknown :dividend-class"
    (let [conn (fresh)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unknown :dividend-class"
           (run-provider
            conn
            [{:source-id  "bogus"
              :asset-class :totally-made-up
              :amount      100M}]
            {:jp-dividend-elections {"bogus" :sogo}}))))))
