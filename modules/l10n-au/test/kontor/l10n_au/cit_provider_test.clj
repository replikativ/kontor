(ns kontor.l10n-au.cit-provider-test
  "AU corporate income tax provider tests — ADR-101 substrate's AU
   consumer (ADR-105 FR template, applied to Australia). Validates
   that the statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold + `:schedule-override`
   for the BRE rate) computes real AU company tax against published
   worked examples.

   Worked examples cited:

   - **§1 Standard company @ $200k** — `:base-rate-entity? false` +
     TI $200,000 → CIT $60,000 (flat 30 %). Source: ATO
     `changes-to-company-tax-rates`; published authority worked example.
   - **§2 BRE company @ $200k** — `:base-rate-entity? true` + TI
     $200,000 → CIT $50,000 (flat 25 %); `:schedule-override` fires.
     Source: ATO + LCR 2019/5.
   - **§3 Bitemporal swap** — BRE company with $200k assessed as-of
     2018-06-30 (27.5 %), 2020-06-30 (still 27.5 %), 2020-12-31
     (26 %), 2025-06-30 (25 %) → $55k / $55k / $52k / $50k. Tests
     the four-step BRE rate cliff history.
   - **§4 Franking coupling** — standard-rate company with $200k +
     $3k franking gross-up + $3k franking credit (non-refundable)
     → CIT $57,900.
   - **§5 CGT coupling** — baseline $100k + $50k CGT (no Div 115
     discount for companies) → base $150k; CIT $45,000.
   - **§6 FITO non-refundable** — $100k baseline + $200 FITO →
     CIT $29,800.
   - **§7 BRE flag absent** — no `:base-rate-entity?` ⇒ default
     30 % schedule (no override fires).
   - **§8 Install idempotence** — substrate property.
   - **§9 Provenance** — `:provisions-applied` records the codes.
   - **§10 Missing book-profit** — ex-info with diagnostic."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.cit-provider :as au-cit]
            [kontor.l10n-au.cit-statute :as cit-statute]
            [kontor.l10n-au.investment-income-statute :as inv-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the AU CIT + investment-income statutes installed
   (the latter ships the corporate-rate parameters CIT references)."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the AU CIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-06-30 (post-Stage-3 era)."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-06-30"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (au-cit/au-cit-provider {})
      {:entity   :pty-ltd
       :period   {:from #inst "2024-07-01" :to #inst "2025-07-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- cit-component
  "Pull the AU CIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Standard-rate company @ $200k — flat 30 %
;; ============================================================================

(deftest standard-rate-company-200k
  (testing "non-BRE company, TI $200k, as-of 2025-06-30: flat 30 % → $60,000"
    (let [facts (compute {:base-rate-entity? false} {:book-profit 200000M})
          c     (cit-component facts)]
      (testing "schedule = flat 30 %"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.30M (:rate (:schedule c)))))
      (testing "base = book-profit (no adjustments fire)"
        (is (== 200000M (:amount (:base c)))))
      (testing "gross-liability = $60,000"
        (is (== 60000M (:amount (:gross-liability c)))))
      (testing "liability = $60,000"
        (is (== 60000M (:amount (:liability c))))
        (is (== 60000M (total-liability facts))))
      (testing "no schedule-override fires"
        (is (nil? (:regime c)))))))

;; ============================================================================
;; §2. BRE company @ $200k — flat 25 % via :schedule-override
;; ============================================================================

(deftest bre-company-200k
  (testing "BRE company, TI $200k, as-of 2025-06-30: flat 25 % → $50,000"
    (let [facts (compute {:base-rate-entity? true} {:book-profit 200000M})
          c     (cit-component facts)]
      (testing "schedule swapped to flat 25 %"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.25M (:rate (:schedule c)))))
      (testing "regime records the BRE override"
        (is (= :au-bre-rate (:regime c))))
      (testing "gross + liability = $50,000"
        (is (== 50000M (:amount (:gross-liability c))))
        (is (== 50000M (:amount (:liability c)))))
      (testing "provenance records the BRE provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "AU-ITRA-1986-§23AA-bre-schedule"))))))

;; ============================================================================
;; §3. Bitemporal swap — 27.5 % → 26 % → 25 %
;; ============================================================================

(deftest bitemporal-swap-bre-rate-history
  (testing "BRE rate stepped 27.5 → 26 → 25 over 2017-07-01 / 2020-07-01 / 2021-07-01"
    (let [c1 (cit-component (compute {:base-rate-entity? true} {:book-profit 200000M} #inst "2018-06-30"))
          c2 (cit-component (compute {:base-rate-entity? true} {:book-profit 200000M} #inst "2020-06-30"))
          c3 (cit-component (compute {:base-rate-entity? true} {:book-profit 200000M} #inst "2020-12-31"))
          c4 (cit-component (compute {:base-rate-entity? true} {:book-profit 200000M} #inst "2025-06-30"))]
      (testing "FY 2017-18 BRE rate = 27.5 % → $55,000"
        (is (== 0.275M (:rate (:schedule c1))))
        (is (== 55000M (:amount (:liability c1)))))
      (testing "FY 2019-20 still BRE 27.5 % → $55,000"
        (is (== 0.275M (:rate (:schedule c2))))
        (is (== 55000M (:amount (:liability c2)))))
      (testing "FY 2020-21 BRE rate = 26 % → $52,000"
        (is (== 0.26M (:rate (:schedule c3))))
        (is (== 52000M (:amount (:liability c3)))))
      (testing "FY 2024-25 BRE rate = 25 % → $50,000"
        (is (== 0.25M (:rate (:schedule c4))))
        (is (== 50000M (:amount (:liability c4))))))))

;; ============================================================================
;; §4. Franking-credit coupling (gross-up base-add + non-refundable credit)
;; ============================================================================

(deftest franking-coupling-cit-200k-plus-gross-up
  (testing "standard-rate $200k + $3k franking gross-up + $3k franking credit (non-refundable)"
    (let [facts (compute {:base-rate-entity? false}
                         {:book-profit                       200000M
                          :au-investment-cit-base-additions  3000M
                          :au-franking-credit-cit-credit     3000M})
          c     (cit-component facts)]
      (testing "base = 200,000 + 3,000 = 203,000"
        (is (== 203000M (:amount (:base c)))))
      (testing "base-transform records the investment-income add"
        (let [items (:items (:base-transform c))]
          (is (= 1 (count items)))
          (is (= :au-investment-base-fold (:code (first items))))))
      (testing "gross-liability = 203,000 × 30 % = 60,900"
        (is (== 60900M (:amount (:gross-liability c)))))
      (testing "non-refundable franking credit of $3,000"
        (is (= 1 (count (:credits c))))
        (let [fc (first (:credits c))]
          (is (= :au-franking-cit-credit (:code fc)))
          (is (== 3000M (:amount fc)))
          (is (false? (:refundable? fc)))))
      (testing "net CIT liability = 60,900 − 3,000 = 57,900"
        (is (== 57900M (:amount (:liability c))))))))

;; ============================================================================
;; §5. CGT coupling — company gets no Div 115 discount, full gain folds
;; ============================================================================

(deftest cgt-coupling-cit-base-flows-through
  (testing "$100k baseline + $50k CGT → base $150k → CIT $45,000"
    (let [facts (compute {:base-rate-entity? false}
                         {:book-profit                100000M
                          :au-cgt-cit-base-additions  50000M})
          c     (cit-component facts)]
      (testing "base = 100,000 + 50,000 = 150,000 (no discount for companies)"
        (is (== 150000M (:amount (:base c)))))
      (testing "liability = 150,000 × 30 % = 45,000"
        (is (== 45000M (:amount (:liability c)))))
      (testing "provenance records the CGT base-add provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "AU-cgt-cit-base-additions"))))))

;; ============================================================================
;; §6. FITO non-refundable credit
;; ============================================================================

(deftest fito-non-refundable-flows-through
  (testing "$100k baseline + $200 FITO → CIT $29,800 (= $30k gross − $200)"
    (let [facts (compute {:base-rate-entity? false}
                         {:book-profit         100000M
                          :au-fito-cit-credit  200M})
          c     (cit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "FITO credit = $200, non-refundable"
        (is (contains? credits-by-code :au-fito-cit-credit))
        (is (== 200M (:amount (credits-by-code :au-fito-cit-credit))))
        (is (false? (:refundable? (credits-by-code :au-fito-cit-credit)))))
      (testing "liability = 30,000 − 200 = 29,800"
        (is (== 29800M (:amount (:liability c))))))))

;; ============================================================================
;; §7. BRE flag absent → default 30 % holds
;; ============================================================================

(deftest bre-flag-absent-defaults-to-30pct
  (testing "absent :base-rate-entity? ⇒ default 30 % flat schedule"
    (let [facts (compute {} {:book-profit 100000M})
          c     (cit-component facts)]
      (testing "schedule = default 30 %"
        (is (== 0.30M (:rate (:schedule c)))))
      (testing "no schedule-override fires"
        (is (nil? (:regime c))))
      (testing "no provisions applied (no driver facts)"
        (is (empty? (-> c :provenance :provisions-applied)))))))

;; ============================================================================
;; §8. Substrate property — install idempotence
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (inv-statute/install! conn)
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "AU.CIT.")]]
                                 (d/db conn) :au))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "AU-")]]
                                 (d/db conn) :au))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

;; ============================================================================
;; §9. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "BRE case records the §23AA schedule-override provision"
    (let [facts (compute {:base-rate-entity? true} {:book-profit 200000M})
          c     (cit-component facts)]
      (is (contains? (set (-> c :provenance :provisions-applied))
                     "AU-ITRA-1986-§23AA-bre-schedule")))))

;; ============================================================================
;; §10. Substrate property — missing book-profit raises
;; ============================================================================

(deftest missing-book-profit-raises
  (testing "absent :inputs :book-profit → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (au-cit/au-cit-provider {})
                             {:entity   :pty-ltd
                              :period   {:from #inst "2024-07-01" :to #inst "2025-07-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts (AUD on every Money)
;; ============================================================================

(deftest functional-commodity-is-aud-on-every-money
  (let [facts (compute {:base-rate-entity? false} {:book-profit 200000M})]
    (is (every? #(= :AUD (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :AUD (:commodity (:liability %)))
                (:components facts)))))
