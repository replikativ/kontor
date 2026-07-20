(ns kontor.l10n-cn.pit-provider-test
  "CN personal income tax provider tests — ADR-101 substrate's CN
   consumer. Validates that the
   statute-as-data path (`:parameter` + `:parameter-bracket` +
   `:provision` rows + `kontor.tax.statute/apply-provisions` fold)
   computes real CN IIT against published worked examples.

   Worked examples cited:

   - **§1 Single filer @ ¥150 000** — basic + statutory contrib ¥18k →
     taxable ¥72 000; IIT ¥4 680; prepaid ¥4 500; balance ¥180.
     Source: STA published worked example.
   - **§2 High earner @ ¥500 000 with children's education** — basic +
     ¥60k statutory + ¥24k children → taxable ¥356 000; IIT ¥57 080.
     Source: STA published rate table.
   - **§3 Business income ¥200 000** — `:business-income? true` swaps
     to 5-band schedule → IIT ¥29 500.
   - **§4 Bracket boundaries** — top of band 1 (¥36 000 → ¥1 080) +
     top band 7 (¥1M → ¥268 080).
   - **§5 Annual reconciliation balance** — substrate-supported
     `:prepaid` lane (ADR-099 Addendum 3).
   - **§6 Infant care (post-2022)** — STA Ann. 2022 No. 7 provision
     fires for 2022+ assessments.
   - **§7 Install idempotence** — substrate property.
   - **§8 Provenance trail** — `:provisions-applied` records the codes.
   - **§9 Missing gross-income raises** — substrate property."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.pit-provider :as cn-pit]
            [kontor.l10n-cn.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the CN PIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (pit-statute/install! conn)
    conn))

(defn- compute
  "Run the CN PIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (cn-pit/cn-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- iit-component
  "Pull the IIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Single filer @ ¥150 000 (the canonical annual-reconciliation case)
;; ============================================================================

(deftest single-filer-150k-with-prepaid
  (testing "Note 186 §2.5 — gross ¥150 000 − ¥60 000 basic − ¥18 000
            statutory → taxable ¥72 000; tax ¥4 680; prepaid ¥4 500;
            balance ¥180"
    (let [facts (compute {}
                         {:gross-comprehensive-income      150000M
                          :pit-base-deductions-statutory   18000M
                          :prepaid                         4500M})
          c     (iit-component facts)]
      (testing "schedule = :progressive-bracket (7 bands)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 7 (count (:brackets (:schedule c))))))
      (testing "base = 150 000 − 60 000 − 18 000 = 72 000"
        (is (== 72000M (:amount (:base c)))))
      (testing "liability = ¥4 680 (bracket fold)"
        (is (== 4680M (:amount (:liability c)))))
      (testing "prepaid = ¥4 500"
        (is (== 4500M (:amount (:prepaid c)))))
      (testing "balance = liability − prepaid = ¥180"
        (is (== 180M (:amount (ptp/balance facts))))))))

;; ============================================================================
;; §2. High earner ¥500 000 with children's-education deduction
;; ============================================================================

(deftest high-earner-500k-with-childrens-education
  (testing "Note 186 §2.6 — gross ¥500 000 − ¥60 000 basic − ¥60 000
            statutory − ¥24 000 children-education → taxable ¥356 000;
            tax ¥57 080"
    (let [facts (compute {}
                         {:gross-comprehensive-income                500000M
                          :pit-base-deductions-statutory             60000M
                          :pit-base-deductions-children-education    24000M})
          c     (iit-component facts)]
      (testing "base = 500 000 − 60 000 − 60 000 − 24 000 = 356 000"
        (is (== 356000M (:amount (:base c)))))
      (testing "liability = ¥57 080 (quick-deduction check: 356 000 × 25 % − 31 920)"
        (is (== 57080M (:amount (:liability c)))))
      (testing "provenance records basic-deduction + statutory + children-education"
        (let [applied (set (-> c :provenance :provisions-applied))]
          (is (contains? applied "CN-IITLaw-§6-¶1(1)-basic-deduction"))
          (is (contains? applied "CN-IITLaw-§6-¶2-statutory-contributions"))
          (is (contains? applied "CN-STA-2018-60-children-education")))))))

;; ============================================================================
;; §3. Business income ¥200 000 — 5-band schedule swap
;; ============================================================================

(deftest business-income-200k-5-band-schedule
  (testing "Note 186 §2.7 — :business-income? true +
            :business-taxable-income ¥200 000 → 5-band fold → ¥29 500"
    (let [facts (compute {:business-income? true}
                         {:business-taxable-income 200000M})
          c     (iit-component facts)]
      (testing "schedule swapped to 5-band business-income scale"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 5 (count (:brackets (:schedule c)))))
        (is (== 30000M (-> c :schedule :brackets first :upper))
            "first band's upper = ¥30 000 (business-income 5 % band)"))
      (testing "base = 200 000 (consumer pre-computed; basic deduction NOT applied here)"
        (is (== 200000M (:amount (:base c)))))
      (testing "liability = ¥29 500 (quick-deduction check: 200 000 × 20 % − 10 500)"
        (is (== 29500M (:amount (:liability c)))))
      (testing "provenance records the schedule-override"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-IITLaw-§3-¶2-business-income-schedule")))
      (testing "basic-deduction provision did NOT fire (suppressed for business-income)"
        (is (not (contains? (set (-> c :provenance :provisions-applied))
                            "CN-IITLaw-§6-¶1(1)-basic-deduction")))))))

;; ============================================================================
;; §4. Bracket boundary checks (top of band 1, top band 7)
;; ============================================================================

(deftest bracket-boundary-band-1-top
  (testing "exactly ¥36 000 taxable comprehensive income → 36k × 3 % = ¥1 080"
    ;; gross = 36k + 60k basic = 96k, no statutory → taxable = 36k
    (let [facts (compute {} {:gross-comprehensive-income 96000M})
          c     (iit-component facts)]
      (is (== 36000M (:amount (:base c))))
      (is (== 1080M (:amount (:liability c)))))))

(deftest bracket-top-band-45pct
  (testing "exactly ¥1 000 000 taxable → ¥268 080 (top bracket fold)"
    (let [facts (compute {} {:gross-comprehensive-income 1060000M})
          c     (iit-component facts)]
      (is (== 1000000M (:amount (:base c))))
      (testing "tax fold: 1 000 000 × 45 % − 181 920 quick-deduction = 268 080"
        (is (== 268080M (:amount (:liability c))))))))

;; ============================================================================
;; §5. Multiple special-additional deductions fold together
;; ============================================================================

(deftest multiple-special-additional-deductions-fold
  (testing "gross ¥300 000 + ¥24 000 children + ¥18 000 housing-rent →
            taxable ¥198 000"
    (let [facts (compute {}
                         {:gross-comprehensive-income                300000M
                          :pit-base-deductions-children-education    24000M
                          :pit-base-deductions-housing-rent          18000M})
          c     (iit-component facts)]
      (testing "base = 300 000 − 60 000 − 24 000 − 18 000 = 198 000"
        (is (== 198000M (:amount (:base c))))))))

;; ============================================================================
;; §6. Infant-care provision fires post-2022
;; ============================================================================

(deftest infant-care-deduction-post-2022
  (testing "gross ¥200 000 + ¥24 000 infant-care (post-2022)"
    (let [facts (compute {}
                         {:gross-comprehensive-income           200000M
                          :pit-base-deductions-infant-care      24000M})
          c     (iit-component facts)]
      (testing "base = 200 000 − 60 000 − 24 000 = 116 000"
        (is (== 116000M (:amount (:base c)))))
      (testing "provenance records the post-2022 infant-care provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-STA-2022-7-infant-care"))))))

;; ============================================================================
;; §7. Install idempotence + bracket dedup
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (identity attrs + bracket dedup)"
    (let [conn (core/create-test-db)]
      (pit-statute/install! conn)
      (pit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "CN.IIT.")]]
                                 (d/db conn) :cn))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   ;; clause-level `or` — the query planner (default
                                   ;; since datahike 0.8.1705, analyzer tightened by
                                   ;; #883/#861) rejects `or` as a predicate-expression
                                   ;; head `[(or (pred) (pred))]`; the disjunction must
                                   ;; be expressed as `(or [pred] [pred])`.
                                   (or [(.startsWith ^String ?code "CN-IITLaw-")]
                                       [(.startsWith ^String ?code "CN-STA-")])]
                                 (d/db conn) :cn))
            n-comp-brackets (count (d/q '[:find ?b
                                          :where
                                          [?p :kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
                                          [?b :kontor.parameter-bracket/parameter ?p]]
                                        (d/db conn)))
            n-biz-brackets  (count (d/q '[:find ?b
                                          :where
                                          [?p :kontor.parameter/code "CN.IIT.business-income.brackets"]
                                          [?b :kontor.parameter-bracket/parameter ?p]]
                                        (d/db conn)))]
        (is (= (count pit-statute/parameters) n-params))
        (is (= (count pit-statute/provisions) n-provs))
        (is (= 7 n-comp-brackets)
            "7 comprehensive-income bracket rows, no duplicates")
        (is (= 5 n-biz-brackets)
            "5 business-income bracket rows, no duplicates")))))

;; ============================================================================
;; §8. Provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "single-filer scenario records basic-deduction + statutory"
    (let [facts (compute {}
                         {:gross-comprehensive-income      150000M
                          :pit-base-deductions-statutory   18000M})
          c     (iit-component facts)]
      (is (= #{"CN-IITLaw-§6-¶1(1)-basic-deduction"
               "CN-IITLaw-§6-¶2-statutory-contributions"}
             (set (-> c :provenance :provisions-applied)))))))

;; ============================================================================
;; §9. Missing gross-income raises
;; ============================================================================

(deftest missing-gross-income-raises
  (testing "absent :inputs :gross-comprehensive-income → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gross-comprehensive-income"
                            (ptp/period-tax-facts
                             (cn-pit/cn-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

(deftest missing-business-taxable-income-raises
  (testing "absent :inputs :business-taxable-income (business-income mode) → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"business-taxable-income"
                            (ptp/period-tax-facts
                             (cn-pit/cn-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:business-income? true}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts (CNY)
;; ============================================================================

(deftest functional-commodity-is-cny-on-every-money
  (let [facts (compute {} {:gross-comprehensive-income 100000M})]
    (is (every? #(= :CNY (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :CNY (:commodity (:liability %)))
                (:components facts)))))
