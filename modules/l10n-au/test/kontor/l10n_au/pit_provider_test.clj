(ns kontor.l10n-au.pit-provider-test
  "AU personal income tax provider tests — ADR-101 substrate's AU
   consumer. Validates that the statute-as-data path
   (`:parameter` + `:parameter-bracket` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold) computes real AU PIT
   against published worked examples.

   Worked examples cited:

   - **§1 Resident single $80k FY 2024-25** — bracket $14,788 +
     Medicare $1,600 + LITO $0 = PIT $16,388. Source: ATO
     `individual-income-tax-rates` + PayCalculator.
   - **§2 Stage-3 cliff** — same $80k pre vs post 2024-07-01 →
     $18,067 vs $16,388 (= $1,679 saving).
   - **§3 Low income $35k** — bracket $2,688 + Medicare $700
     (capped from 2 % flat) + LITO $700 = PIT $2,688
     (cancellation by policy intent).
   - **§4 Full coupling $52,357.14** — employment + franking gross-
     up + CGT net gain + refundable franking + TFN-prepaid; PIT
     $6,270.50.
   - **§5 Medicare shade-in $30k** — shade 10 % × ($30k − $27,222)
     = $277.80 (< 2 % flat $600).
   - **§6 LITO band-3 $50k** — $325 − 0.015 × ($50k − $45k) =
     $250.
   - **§7 LITO above $66,667** — credit zeroed.
   - **§8 Refundable credit drives negative** — TI $25k + $2k
     franking refundable; net liability < 0 (refund).
   - **§9 FITO non-refundable floors at zero** — $10k FITO with
     small bracket tax; liability = 0, excess lost.
   - **§10 Install idempotence** — substrate property.
   - **§11 Provenance** — `:provisions-applied` records the codes.
   - **§12 Missing gross-income** — ex-info."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.pit-provider :as au-pit]
            [kontor.l10n-au.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the AU PIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (pit-statute/install! conn)
    conn))

(defn- compute
  "Run the AU PIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-06-30 (FY 2024-25 post-
   Stage-3 era)."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-06-30"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (au-pit/au-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2024-07-01" :to #inst "2025-07-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- pit-component
  "Pull the AU PIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Resident single @ $80k FY 2024-25
;; ============================================================================

(deftest resident-single-80k-fy2024-25
  (testing "TI $80,000 FY 2024-25 (Stage-3 set) → bracket $14,788 + Medicare $1,600 + LITO 0 → $16,388"
    (let [facts (compute {} {:gross-income 80000M})
          c     (pit-component facts)]
      (testing "schedule = :progressive-bracket with 5 bands (Stage-3 set)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 5 (count (:brackets (:schedule c)))))
        (is (== 18200M (-> c :schedule :brackets first :upper))
            "first kink = $18,200 tax-free threshold"))
      (testing "base = $80,000 (no adjustments)"
        (is (== 80000M (:amount (:base c)))))
      (testing "gross-liability = $14,788"
        (is (== 14788M (:amount (:gross-liability c)))))
      (testing "Medicare Levy surtax = $1,600 (2 % flat, TI > shade-in zone)"
        (is (= 1 (count (:surtaxes c))))
        (let [ml (first (:surtaxes c))]
          (is (= :au-medicare-levy (:code ml)))
          (is (== 1600M (:amount ml)))))
      (testing "no LITO (TI > $66,667)"
        (is (empty? (:credits c))))
      (testing "liability = $16,388"
        (is (== 16388M (:amount (:liability c))))
        (is (== 16388M (total-liability facts)))))))

;; ============================================================================
;; §2. Bitemporal swap — Stage-3 cliff
;; ============================================================================

(deftest bitemporal-swap-stage-3-cliff-80k
  (testing "$80k assessed pre vs post 2024-07-01 (Stage-3) → $18,067 vs $16,388"
    (let [pre  (pit-component (compute {} {:gross-income 80000M} #inst "2024-06-30"))
          post (pit-component (compute {} {:gross-income 80000M} #inst "2025-06-30"))]
      (testing "pre-Stage-3 second bracket rate = 19 %"
        (is (== 0.19M (-> pre :schedule :brackets second :rate))))
      (testing "post-Stage-3 second bracket rate = 16 %"
        (is (== 0.16M (-> post :schedule :brackets second :rate))))
      (testing "pre-Stage-3 bracket tax = $16,467 (19 % + 32.5 % bands)"
        (is (== 16467M (:amount (:gross-liability pre)))))
      (testing "post-Stage-3 bracket tax = $14,788 (16 % + 30 % bands)"
        (is (== 14788M (:amount (:gross-liability post)))))
      (testing "pre-Stage-3 liability = $18,067"
        (is (== 18067M (:amount (:liability pre)))))
      (testing "post-Stage-3 liability = $16,388 ($1,679 saving)"
        (is (== 16388M (:amount (:liability post))))))))

;; ============================================================================
;; §3. Low income $35k — LITO + Medicare cancellation
;; ============================================================================

(deftest low-income-35k-lito-and-medicare-shade
  (testing "TI $35,000 → bracket $2,688; Medicare $700 (capped); LITO $700; PIT $2,688"
    (let [facts (compute {} {:gross-income 35000M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "bracket tax = ($35k − $18.2k) × 16 % = $2,688"
        (is (== 2688M (:amount (:gross-liability c)))))
      (testing "Medicare Levy = $700 (min of 2 % × $35k = $700 vs shade-in 10 % × $7,778 = $777.80)"
        (is (== 700M (:amount (first (:surtaxes c))))))
      (testing "LITO = $700 (full, TI ≤ $37,500)"
        (is (contains? credits-by-code :au-lito))
        (is (== 700M (:amount (credits-by-code :au-lito))))
        (is (false? (:refundable? (credits-by-code :au-lito)))))
      (testing "liability = $2,688 (Medicare + LITO cancel by policy intent)"
        (is (== 2688M (:amount (:liability c))))))))

;; ============================================================================
;; §4. Full coupling — employment + dividend + CGT + TFN
;; ============================================================================

(deftest full-coupling-50k-employment+dividend+capgain+tfn
  (testing "Resident individual, $50k employment + $857.14 gross-up + $1.5k CGT + $200 TFN; PIT $6,270.50"
    (let [facts (compute {} {:gross-income                      50000M
                             :au-investment-pit-base-additions   857.14M
                             :au-cgt-pit-base-additions          1500M
                             :au-franking-credit-pit-credit      857.14M
                             :au-tfn-prepaid-pit-credit          200M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "base = 50,000 + 857.14 + 1,500 = 52,357.14"
        (is (== 52357.14M (:amount (:base c)))))
      (testing "base-transform records 2 items (CGT + investment-income lanes)"
        (let [items (:items (:base-transform c))]
          (is (= 2 (count items)))
          (is (= #{:au-investment-pit-base-fold :au-cgt-pit-base-fold}
                 (set (map :code items))))))
      (testing "Medicare Levy = 2 % × 52,357.14 ≈ $1,047.14"
        (is (== 1047.1428M (:amount (first (:surtaxes c))))))
      (testing "Franking credit = $857.14 (refundable)"
        (is (contains? credits-by-code :au-franking-pit-credit))
        (is (true? (:refundable? (credits-by-code :au-franking-pit-credit)))))
      (testing "TFN prepaid = $200 (refundable)"
        (is (contains? credits-by-code :au-tfn-prepaid-pit-credit))
        (is (true? (:refundable? (credits-by-code :au-tfn-prepaid-pit-credit)))))
      (testing "LITO = $325 − 0.015 × (52,357.14 − 45,000) = $214.6429"
        (is (== 214.64290M (:amount (credits-by-code :au-lito)))))
      ;; Substrate-computed liability $6,270.5019 = published $6,270.50 to the cent
      (testing "liability = $6,270.50 (rounded to cents)"
        (is (== 6270.50M (.setScale ^java.math.BigDecimal (:amount (:liability c)) 2 java.math.RoundingMode/HALF_EVEN)))))))

;; ============================================================================
;; §5. Medicare shade-in zone $30k
;; ============================================================================

(deftest medicare-shade-in-zone-30k
  (testing "TI $30k (between low-income $27,222 and upper-shade $34,027) — shade-in fires"
    (let [facts (compute {} {:gross-income 30000M})
          c     (pit-component facts)]
      (testing "Medicare shade = 10 % × ($30k − $27,222) = $277.80 (< 2 % flat $600)"
        (is (== 277.80M (:amount (first (:surtaxes c))))))
      (testing "bracket = ($30k − $18.2k) × 16 % = $1,888"
        (is (== 1888M (:amount (:gross-liability c)))))
      (testing "LITO = $700 (TI ≤ $37,500)"
        (let [credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
          (is (== 700M (:amount (credits-by-code :au-lito))))))
      (testing "liability = $1,888 + $277.80 − $700 = $1,465.80"
        (is (== 1465.80M (:amount (:liability c))))))))

;; ============================================================================
;; §6. LITO band-3 shade at $50k
;; ============================================================================

(deftest lito-band-3-shade-50k
  (testing "TI $50,000 → LITO band-3 shade $325 − 0.015 × ($50k − $45k) = $250"
    (let [facts (compute {} {:gross-income 50000M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "bracket = $4,288 + ($50k − $45k) × 30 % = $5,788"
        (is (== 5788M (:amount (:gross-liability c)))))
      (testing "Medicare = 2 % × $50k = $1,000"
        (is (== 1000M (:amount (first (:surtaxes c))))))
      (testing "LITO = $250 (band-3)"
        (is (== 250M (:amount (credits-by-code :au-lito)))))
      (testing "liability = $5,788 + $1,000 − $250 = $6,538"
        (is (== 6538M (:amount (:liability c))))))))

;; ============================================================================
;; §7. LITO above $66,667 zeroed
;; ============================================================================

(deftest lito-above-66667-zeroed
  (testing "TI $70,000 → LITO = 0 (provision condition does not fire)"
    (let [facts (compute {} {:gross-income 70000M})
          c     (pit-component facts)]
      (testing "no LITO in credits (provision conditioned on TI < $66,667)"
        (is (empty? (filter #(= :au-lito (:code %)) (:credits c)))))
      (testing "bracket = $4,288 + ($70k − $45k) × 30 % = $11,788"
        (is (== 11788M (:amount (:gross-liability c)))))
      (testing "liability = $11,788 + $1,400 = $13,188"
        (is (== 13188M (:amount (:liability c))))))))

;; ============================================================================
;; §8. Refundable credit drives liability negative
;; ============================================================================

(deftest refundable-credits-drive-liability-negative
  (testing "TI $25k + $2k refundable franking credit → liability < 0 (refund)"
    (let [facts (compute {} {:gross-income                   25000M
                             :au-franking-credit-pit-credit  2000M})
          c     (pit-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "bracket = ($25k − $18.2k) × 16 % = $1,088"
        (is (== 1088M (:amount (:gross-liability c)))))
      (testing "Medicare = 0 (TI < low-income threshold $27,222)"
        (is (== 0M (:amount (first (:surtaxes c))))))
      (testing "LITO = $700 (non-refundable; floors at 0 within its slot)"
        (is (== 700M (:amount (credits-by-code :au-lito)))))
      (testing "Franking refundable = $2,000"
        (is (contains? credits-by-code :au-franking-pit-credit))
        (is (true? (:refundable? (credits-by-code :au-franking-pit-credit)))))
      (testing "liability is NEGATIVE — refundable credit pushes below zero"
        (is (neg? (:amount (:liability c))))))))

;; ============================================================================
;; §9. FITO non-refundable floors at zero (excess lost)
;; ============================================================================

(deftest fito-non-refundable-floors-at-zero
  (testing "TI $50k + $10k FITO (non-refundable) → liability floors at 0 (excess lost)"
    (let [facts (compute {} {:gross-income       50000M
                             :au-fito-pit-credit 10000M})
          c     (pit-component facts)]
      (testing "FITO present and non-refundable"
        (let [credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
          (is (contains? credits-by-code :au-fito-pit-credit))
          (is (false? (:refundable? (credits-by-code :au-fito-pit-credit))))))
      (testing "liability floors at 0M (non-refundable apply-adjustments)"
        (is (== 0M (:amount (:liability c))))))))

;; ============================================================================
;; §10. Substrate property — install idempotence
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
                                   [(.startsWith ^String ?code "AU.PIT.")]]
                                 (d/db conn) :au))
            ;; The PIT provisions are identified by their `:code` —
            ;; we pull all AU provisions and intersect with the set
            ;; the statute file declares (avoids datahike-incompatible
            ;; `or-join` for code-prefix discrimination).
            au-prov-codes (->> (d/q '[:find ?code
                                      :in $ ?juris
                                      :where
                                      [?p :kontor.provision/jurisdiction ?juris]
                                      [?p :kontor.provision/code ?code]]
                                    (d/db conn) :au)
                               (map first)
                               set)
            pit-codes (set (map :kontor.provision/code pit-statute/provisions))
            n-provs   (count (clojure.set/intersection au-prov-codes pit-codes))
            n-brackets (count (d/q '[:find ?b
                                     :where
                                     [?p :kontor.parameter/code "AU.PIT.brackets"]
                                     [?b :kontor.parameter-bracket/parameter ?p]]
                                   (d/db conn)))]
        (is (= (count pit-statute/parameters) n-params))
        (is (= (count pit-statute/provisions) n-provs))
        (is (= (count pit-statute/parameter-brackets) n-brackets)
            "bracket dedup did not multiply rows")))))

;; ============================================================================
;; §11. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "low-income $35k case records Medicare + LITO codes"
    (let [facts (compute {} {:gross-income 35000M})
          c     (pit-component facts)
          applied (set (-> c :provenance :provisions-applied))]
      (is (contains? applied "AU-MedicareLevyAct-§7-medicare-levy"))
      (is (contains? applied "AU-ITAA-1997-§159N-LITO")))))

;; ============================================================================
;; §12. Substrate property — missing gross-income raises
;; ============================================================================

(deftest missing-gross-income-raises
  (testing "absent :inputs :gross-income → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gross-income"
                            (ptp/period-tax-facts
                             (au-pit/au-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2024-07-01" :to #inst "2025-07-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts (AUD on every Money)
;; ============================================================================

(deftest functional-commodity-is-aud-on-every-money
  (let [facts (compute {} {:gross-income 80000M})]
    (is (every? #(= :AUD (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :AUD (:commodity (:liability %)))
                (:components facts)))))
