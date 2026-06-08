(ns kontor.l10n-us.pit-provider-test
  "US personal income tax provider tests — ADR-101 substrate's US
   consumer (ADR-106 / JP 7-bracket template, applied to the United
   States). Validates that the statute-as-data path (`:parameter` +
   `:parameter-bracket` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold) computes real US §1(j)
   federal PIT against published worked examples to the dollar.

   Worked examples cited:

   - **§1 Single 80k taxable 2025** — itemized? true so std deduction
     is suppressed; bracket fold yields $12 514.00. Source: IRS Rev.
     Proc. 2024-40 §3.01 Table 3.
   - **§2 Single 80k gross 2025** — std deduction $15 000 →
     taxable $65 000 → tax $9 214.00. Source: same +
     §3.16.
   - **§3 MFJ 200k taxable 2025** — itemized? true → $33 828.00. Same
     §3.01 Table 1.
   - **§4 Bitemporal 2024 vs 2025 single 80k** — inflation-adjusted
     thresholds shift; 2024 → $12 653.00; 2025 → $12 514.00.
   - **§5 MFJ 200k + 2 kids 2025** — CTC non-refundable $4 000;
     liability $29 828.00.
   - **§6 ACTC fires low-income single 2 kids** — earned/gross $30 k
     − std $15 k = taxable $15 k → tax $1 561.50 → CTC non-ref absorbs
     all → ACTC residual = $4 000 − $1 561.50 = $2 438.50 (under both
     caps); liability $-2 438.50 (refund).
   - **§7 MFS + HoH @ $500 k 2025** — distinct bracket tables fire.
   - **§8 CGT lane integration** — single 80k + $20 k ST cap gain
     → taxable $100 k → tax $16 914.00.
   - **§9 Investment-income lane positive** — single 80k + $5 k
     ordinary div/int → taxable $85 k.
   - **§10 Investment-income lane negative (§163d)** — single 80k +
     $-3 k net (§163d > investment income) → taxable $77 k.
   - **§11 Itemized = true suppresses std** — verified.
   - **§12 Itemized = true + itemized amount** — uses the supplied
     itemized total.
   - **§13 Unknown filing status raises** — closed-set discipline.
   - **§14 Default filing status is single** — falls back.
   - **§15 Install idempotence** — substrate property.
   - **§16 Provenance** — `:provisions-applied` records the codes.
   - **§17 Missing gross-income** — ex-info."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.pit-provider :as us-pit]
            [kontor.l10n-us.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the US PIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (pit-statute/install! conn)
    conn))

(defn- compute
  "Run the US PIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (us-pit/us-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- pit-component
  "Pull the PIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Single 80k taxable 2025 — pure bracket fold (itemized? true)
;; ============================================================================

(deftest single-80k-taxable-2025-no-deduction
  (testing "single + itemized? true + $80 k → tax $12 514.00 (Rev. Proc. 2024-40 §3.01 Table 3)"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income 80000M})
          c     (pit-component facts)]
      (testing "schedule = :progressive-bracket with 7 bands (TY 2025 single set)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 7 (count (:brackets (:schedule c)))))
        (is (== 11925M (-> c :schedule :brackets first :upper))
            "first kink is the TY 2025 single 10%-band top"))
      (testing "base = $80 000 (no base adjustments)"
        (is (== 80000M (:amount (:base c)))))
      (testing "gross-liability = $12 514.00"
        (is (== 12514.00M (:amount (:gross-liability c)))))
      (testing "liability = $12 514.00 (no credits)"
        (is (== 12514.00M (:amount (:liability c))))
        (is (== 12514.00M (total-liability facts))))
      (testing ":regime records the filing status for audit"
        (is (= :single (:regime c)))))))

;; ============================================================================
;; §2. Single 80k gross 2025 — std deduction
;; ============================================================================

(deftest single-80k-gross-2025-std-deduction
  (testing "single + $80 k gross − $15 k std → taxable $65 k → tax $9 214.00"
    (let [facts (compute {:filing-status :single}
                         {:gross-income 80000M})
          c     (pit-component facts)
          items (:items (:base-transform c))]
      (testing "base = 80 000 − 15 000 = 65 000"
        (is (== 65000M (:amount (:base c)))))
      (testing "base-transform records the §63 std deduction"
        (is (= 1 (count items)))
        (is (= :us-§63-std-deduction (:code (first items))))
        (is (== 15000M (:amount (first items)))))
      (testing "liability = $9 214.00"
        (is (== 9214.00M (:amount (:liability c)))))
      (testing "provenance records the §63 std deduction provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "US-IRC-§63-standard-deduction"))))))

;; ============================================================================
;; §3. MFJ 200k taxable 2025
;; ============================================================================

(deftest mfj-200k-2025-no-deduction
  (testing "MFJ + itemized? true + $200 k → tax $33 828.00 (Rev. Proc. 2024-40 Table 1)"
    (let [facts (compute {:filing-status :mfj :itemized? true}
                         {:gross-income 200000M})
          c     (pit-component facts)]
      (testing "first kink = $23 850 (TY 2025 MFJ 10%-band top)"
        (is (== 23850M (-> c :schedule :brackets first :upper))))
      (testing "liability = $33 828.00"
        (is (== 33828.00M (:amount (:liability c))))))))

;; ============================================================================
;; §4. Bitemporal swap 2024 vs 2025 single 80k
;; ============================================================================

(deftest bitemporal-swap-2024-vs-2025-single
  (testing "same single $80 k itemized? true assessed 2024-12-31 vs 2025-12-31
            — inflation-indexed thresholds yield different liability"
    (let [pre  (compute {:filing-status :single :itemized? true}
                        {:gross-income 80000M} #inst "2024-12-31")
          post (compute {:filing-status :single :itemized? true}
                        {:gross-income 80000M} #inst "2025-12-31")
          pre-c  (pit-component pre)
          post-c (pit-component post)]
      (testing "TY 2024 first kink = $11 600"
        (is (== 11600M (-> pre-c :schedule :brackets first :upper))))
      (testing "TY 2025 first kink = $11 925"
        (is (== 11925M (-> post-c :schedule :brackets first :upper))))
      (testing "TY 2024 liability = $12 653.00"
        (is (== 12653.00M (:amount (:liability pre-c)))))
      (testing "TY 2025 liability = $12 514.00"
        (is (== 12514.00M (:amount (:liability post-c)))))
      (testing "Δ = ($139.00) (the inflation-indexing benefit at $80 k single)"
        (is (== -139.00M (- (:amount (:liability post-c))
                            (:amount (:liability pre-c)))))))))

;; ============================================================================
;; §5. MFJ + 2 kids 2025 CTC non-refundable
;; ============================================================================

(deftest mfj-200k-2-children-2025-ctc-non-refundable
  (testing "MFJ + 2 kids + itemized? true + $200 k → tax $33 828 − $4 000 CTC = $29 828"
    (let [facts (compute {:filing-status :mfj :itemized? true
                          :qualifying-children-under-17 2}
                         {:gross-income 200000M :earned-income 200000M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "gross-liability = $33 828.00"
        (is (== 33828.00M (:amount (:gross-liability c)))))
      (testing "CTC non-refundable = $4 000 (2 × $2 000)"
        (is (contains? credits-by-code :us-§24-ctc))
        (is (== 4000M (:amount (credits-by-code :us-§24-ctc))))
        (is (false? (:refundable? (credits-by-code :us-§24-ctc)))))
      (testing "ACTC = $0 (tax-before-credits absorbs the full CTC)"
        (is (contains? credits-by-code :us-§24-actc))
        (is (== 0M (:amount (credits-by-code :us-§24-actc)))))
      (testing "liability = $29 828.00"
        (is (== 29828.00M (:amount (:liability c))))))))

;; ============================================================================
;; §6. ACTC fires low-income single 2 kids → refund
;; ============================================================================

(deftest actc-fires-low-income-single
  (testing "single + 2 kids + $30 k earned / $15 k taxable after std → ACTC refundable"
    (let [facts (compute {:filing-status :single
                          :qualifying-children-under-17 2}
                         {:gross-income  30000M
                          :earned-income 30000M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "base = 30 000 − 15 000 std = 15 000"
        (is (== 15000M (:amount (:base c)))))
      (testing "gross-liability = $1 561.50"
        (is (== 1561.50M (:amount (:gross-liability c)))))
      (testing "CTC non-refundable absorbs tax-before-credits: $1 561.50"
        (is (== 1561.50M (:amount (credits-by-code :us-§24-ctc)))))
      (testing "ACTC residual = $4 000 − $1 561.50 = $2 438.50; under both caps"
        (is (== 2438.50M (:amount (credits-by-code :us-§24-actc))))
        (is (true? (:refundable? (credits-by-code :us-§24-actc)))))
      (testing "liability = $-2 438.50 (refund)"
        (is (== -2438.50M (:amount (:liability c))))))))

;; ============================================================================
;; §7. MFS + HoH @ $500 k 2025 — distinct bracket tables fire
;; ============================================================================

(deftest mfs-and-hoh-tables-are-distinct
  (testing "MFS @ $500 k 2025 fires the MFS top-of-35 % cliff ($375 800) — distinct from single"
    (let [facts (compute {:filing-status :mfs :itemized? true}
                         {:gross-income 500000M})
          c     (pit-component facts)]
      (testing "first kink = $11 925 (same as single)"
        (is (== 11925M (-> c :schedule :brackets first :upper))))
      (testing "6th kink = $375 800 (MFS-specific top-of-35 % cliff)"
        (is (== 375800M (-> c :schedule :brackets (nth 5) :upper))))
      (testing "liability = $147 031.25"
        (is (== 147031.25M (:amount (:liability c)))))))

  (testing "HoH @ $500 k 2025 uses the wider HoH 22 % band (top $103 350)"
    (let [facts (compute {:filing-status :hoh :itemized? true}
                         {:gross-income 500000M})
          c     (pit-component facts)]
      (testing "first kink = $17 000 (HoH 10%-band top)"
        (is (== 17000M (-> c :schedule :brackets first :upper))))
      (testing "liability = $142 809.00"
        (is (== 142809.00M (:amount (:liability c))))))))

;; ============================================================================
;; §8. CGT pit-base-additions lane integration
;; ============================================================================

(deftest cgt-pit-base-additions-flows-through
  (testing "single $80 k + :cgt-pit-base-additions $20 k → taxable $100 k → tax $16 914.00"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income 80000M
                          :cgt-pit-base-additions 20000M})
          c     (pit-component facts)
          items (:items (:base-transform c))]
      (testing "base = $100 000"
        (is (== 100000M (:amount (:base c)))))
      (testing "base-transform records the §1 CGT pit-base-additions"
        (is (some #(= :us-cgt-pit-base-additions (:code %)) items)))
      (testing "liability = $16 914.00"
        (is (== 16914.00M (:amount (:liability c)))))
      (testing "provenance records the lane provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "US-IRC-§1-cgt-pit-base-additions"))))))

;; ============================================================================
;; §9. Investment-income lane positive (ordinary div + interest)
;; ============================================================================

(deftest investment-pit-base-additions-positive
  (testing "single $80 k + :investment-pit-base-additions $5 k → taxable $85 k"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income 80000M
                          :investment-pit-base-additions 5000M})
          c     (pit-component facts)]
      (is (== 85000M (:amount (:base c))))
      ;; tax on $85k 2025 single = 1192.50 + 4386.00 + 8035.50 = 13614.00
      (is (== 13614.00M (:amount (:liability c)))))))

;; ============================================================================
;; §10. Investment-income lane NEGATIVE (§163(d) deduction net)
;; ============================================================================

(deftest investment-pit-base-additions-negative-§163d
  (testing "single $80 k + :investment-pit-base-additions $-3 k (§163d net) → taxable $77 k"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income 80000M
                          :investment-pit-base-additions -3000M})
          c     (pit-component facts)]
      (is (== 77000M (:amount (:base c))))
      ;; tax on $77k 2025 single = 1192.50 + 4386.00 + 6275.50 = 11854.00
      (is (== 11854.00M (:amount (:liability c)))))))

;; ============================================================================
;; §11. itemized? true suppresses std deduction (without :itemized-deductions)
;; ============================================================================

(deftest itemized-true-suppresses-std-deduction
  (testing "itemized? true + no :itemized-deductions → no base deduction; full gross taxed"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income 50000M})
          c     (pit-component facts)]
      (is (= 0 (count (:items (:base-transform c))))
          "no base-transform items — both std-deduction and itemized provisions skipped")
      (is (== 50000M (:amount (:base c)))))))

;; ============================================================================
;; §12. itemized? true + supplied :itemized-deductions
;; ============================================================================

(deftest itemized-true-with-itemized-amount
  (testing "itemized? true + :itemized-deductions $20 k → base $80 k − $20 k = $60 k"
    (let [facts (compute {:filing-status :single :itemized? true}
                         {:gross-income          80000M
                          :itemized-deductions   20000M})
          c     (pit-component facts)
          items (:items (:base-transform c))]
      (is (= 1 (count items)))
      (is (= :us-§63-itemized (:code (first items))))
      (is (== 20000M (:amount (first items))))
      (is (== 60000M (:amount (:base c)))))))

;; ============================================================================
;; §13. Unknown filing status raises (closed-set discipline)
;; ============================================================================

(deftest unknown-filing-status-raises
  (testing ":tax-unit :filing-status :bogus → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"filing-status"
                            (ptp/period-tax-facts
                             (us-pit/us-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-12-31"
                              :tax-unit {:filing-status :bogus :itemized? true}
                              :inputs   {:gross-income 50000M}}))))))

;; ============================================================================
;; §14. Default filing status is :single
;; ============================================================================

(deftest default-filing-status-is-single
  (testing "no :tax-unit :filing-status → defaults to :single (compares to explicit single)"
    (let [a (compute {:itemized? true}                            {:gross-income 80000M})
          b (compute {:filing-status :single :itemized? true}     {:gross-income 80000M})]
      (is (== (total-liability a) (total-liability b))))))

;; ============================================================================
;; §15. Install idempotence
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
                                   [(.startsWith ^String ?code "US.PIT.")]]
                                 (d/db conn) :us))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "US-IRC-")]]
                                 (d/db conn) :us))
            n-brackets (count (d/q '[:find ?b
                                     :where
                                     [?p :kontor.parameter/code ?code]
                                     [(.startsWith ^String ?code "US.PIT.§1.brackets-")]
                                     [?b :kontor.parameter-bracket/parameter ?p]]
                                   (d/db conn)))]
        (is (= (count pit-statute/parameters) n-params))
        (is (= (count pit-statute/provisions) n-provs))
        (is (= (count pit-statute/parameter-brackets-rows) n-brackets)
            "bracket dedup did not multiply rows")
        (is (= 168 n-brackets)
            "4 statuses × 7 bands × 6 years = 168 bracket rows")))))

;; ============================================================================
;; §16. Provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "MFJ + 2 kids case records the std-deduction + CTC + ACTC codes"
    (let [facts (compute {:filing-status :mfj
                          :qualifying-children-under-17 2}
                         {:gross-income 100000M :earned-income 100000M})
          c     (pit-component facts)
          applied (set (-> c :provenance :provisions-applied))]
      (is (contains? applied "US-IRC-§63-standard-deduction"))
      (is (contains? applied "US-IRC-§24-CTC-non-refundable"))
      (is (contains? applied "US-IRC-§24-CTC-refundable-ACTC")))))

;; ============================================================================
;; §17. Missing gross-income raises
;; ============================================================================

(deftest missing-gross-income-raises
  (testing "absent :inputs :gross-income → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gross-income"
                            (ptp/period-tax-facts
                             (us-pit/us-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:filing-status :single}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-usd-on-every-money
  (let [facts (compute {:filing-status :single :itemized? true}
                       {:gross-income 80000M})]
    (is (every? #(= :USD (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :USD (:commodity (:liability %)))
                (:components facts)))))
