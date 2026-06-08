(ns kontor.l10n-mx.pit-provider-test
  "MX personal income tax provider tests — ADR-101 substrate's MX
   consumer (ADR-104 template + AT pit template, applied to Mexico).
   Validates that the statute-as-data path (`:parameter` +
   `:parameter-bracket` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold) computes real MX ISR
   personas físicas against published worked examples.

   Worked examples cited:

   - **§1 Single filer @ MX$ 300 000 (2025)** — marginal-form
     bracket fold = MX$ 44 064.033256. RMF 2025 Anexo 8.
   - **§2 Bitemporal swap 2022 vs 2025 brackets** — same income
     against pre-2024 set vs 2025 set; demonstrates 5-year history
     wiring.
   - **§3 Subsidio para el empleo (Q5.5 refundable)** — MX$ 80 000
     income + MX$ 5 700 subsidio → ISR negative (the canonical
     refundability test).
   - **§4 ISR retenido refundable** — drives liability down.
   - **§5 CGT art. 120 base-additions** — folds prior CGT
     real-estate gain.
   - **§6 Investment-income base-additions** — folds investment
     lanes.
   - **§7 Art. 140 factor-credit non-refundable** — clamps at 0.
   - **§8 Bank-WHT refundable** — drives liability negative.
   - **§9 FTC non-refundable** — clamps at 0.
   - **§10 Top-band marginal-rate** — 5 M income → top-band fires.
   - **§11 Install idempotence** — substrate property.
   - **§12 Provenance** — codes recorded.
   - **§13 Missing gross-income** — ex-info."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.pit-provider :as mx-pit]
            [kontor.l10n-mx.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the MX PIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (pit-statute/install! conn)
    conn))

(defn- compute
  "Run the MX PIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (mx-pit/mx-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- isr-pf-component
  "Pull the ISR personas físicas component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Single filer @ MX$ 300 000 (2025 set, marginal form)
;; ============================================================================

(deftest single-filer-300k-2025-no-children
  (testing "single filer, MX$ 300 000 taxable, 2025 brackets → ISR
            MX$ 44 064.033256 (substrate-native marginal-form integral)"
    (let [facts (compute {} {:gross-income 300000M})
          c     (isr-pf-component facts)]
      (testing "schedule = :progressive-bracket with 11 bands (2025 set)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 11 (count (:brackets (:schedule c)))))
        (is (== 8952.49M (-> c :schedule :brackets first :upper))
            "first kink is the 2025 0.0192-bracket top"))
      (testing "base = MX$ 300 000 (no base adjustments)"
        (is (== 300000M (:amount (:base c)))))
      (testing "gross-liability = MX$ 44 064.033256 (marginal-bracket fold)"
        (is (== 44064.033256M (:amount (:gross-liability c)))))
      (testing "liability = gross (no credits)"
        (is (== 44064.033256M (:amount (:liability c))))
        (is (== 44064.033256M (total-liability facts)))))))

;; ============================================================================
;; §2. Bitemporal swap — 2022 vs 2025 brackets
;; ============================================================================

(deftest bitemporal-swap-2022-vs-2025-brackets
  (testing "same MX$ 300 000 assessed against pre-2024 set vs 2025 set
            — INPC ≥ 10 % adjustment yields different ISR"
    (let [pre  (compute {} {:gross-income 300000M} #inst "2022-12-31")
          post (compute {} {:gross-income 300000M} #inst "2025-12-31")
          pre-c  (isr-pf-component pre)
          post-c (isr-pf-component post)]
      (testing "pre-2024 first kink = MX$ 7 735 (pre-reform 0%-band top)"
        (is (== 7735M (-> pre-c :schedule :brackets first :upper))))
      (testing "2025 first kink = MX$ 8 952.49 (post-reform 0%-band top)"
        (is (== 8952.49M (-> post-c :schedule :brackets first :upper))))
      (testing "pre-2024 ISR = MX$ 46 786.094152"
        (is (== 46786.094152M (:amount (:liability pre-c)))))
      (testing "2025 ISR = MX$ 44 064.033256"
        (is (== 44064.033256M (:amount (:liability post-c)))))
      (testing "the post-reform liability is lower (the reform widened thresholds)"
        (is (< (:amount (:liability post-c))
               (:amount (:liability pre-c))))))))

;; ============================================================================
;; §3. Subsidio para el empleo (Q5.5 — refundable, drives liability negative)
;; ============================================================================

(deftest subsidio-empleo-fires-refundable-credit-drives-liability-negative
  (testing "MX$ 80 000 taxable + :inputs :subsidio-empleo MX$ 5 700
            → tax-before MX$ 4 898.820608; ISR MX$ -801.179392
            (refundable subsidio drives below zero — the canonical Q5.5 test)"
    (let [facts (compute {} {:gross-income     80000M
                             :subsidio-empleo  5700M})
          c     (isr-pf-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "gross-liability = MX$ 4 898.820608"
        (is (== 4898.820608M (:amount (:gross-liability c)))))
      (testing "subsidio credit fires, refundable? true"
        (is (contains? credits-by-code :mx-subsidio-empleo))
        (is (== 5700M (:amount (credits-by-code :mx-subsidio-empleo))))
        (is (true? (:refundable? (credits-by-code :mx-subsidio-empleo)))))
      (testing "liability drove NEGATIVE — refundable credit honoured"
        (is (== -801.179392M (:amount (:liability c))))
        (is (neg? (:amount (:liability c)))
            ":refundable? true lets liability go below zero per ADR-101"))
      (testing "provenance records the subsidio provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-art-96-bis-subsidio-empleo"))))))

;; ============================================================================
;; §4. ISR retenido refundable
;; ============================================================================

(deftest isr-retenido-prepaid-credit-drives-liability-down
  (testing "MX$ 80 000 + :isr-retenido MX$ 10 000 → tax-before MX$ 4 898.82;
            refundable credit drives liability to MX$ -5 101.179392"
    (let [facts (compute {} {:gross-income  80000M
                             :isr-retenido  10000M})
          c     (isr-pf-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "isr-retenido credit fires, refundable? true"
        (is (contains? credits-by-code :mx-isr-retenido))
        (is (== 10000M (:amount (credits-by-code :mx-isr-retenido))))
        (is (true? (:refundable? (credits-by-code :mx-isr-retenido)))))
      (testing "liability drove negative — withholding exceeded annual ISR"
        (is (== -5101.179392M (:amount (:liability c))))))))

;; ============================================================================
;; §5. CGT art. 120 fold flows through
;; ============================================================================

(deftest cgt-art-120-acumulable-folds-into-pit-base
  (testing "MX$ 300 000 wage + :cgt-pit-base-additions MX$ 100 000 → base MX$ 400 000"
    (let [facts (compute {} {:gross-income            300000M
                             :cgt-pit-base-additions  100000M})
          c     (isr-pf-component facts)
          items (:items (:base-transform c))]
      (testing "base = 300 000 + 100 000 = 400 000"
        (is (== 400000M (:amount (:base c)))))
      (testing "base-transform records the CGT fold"
        (is (= 1 (count items)))
        (is (= :mx-cgt-art-120-fold (:code (first items))))
        (is (== 100000M (:amount (first items)))))
      (testing "liability = bracket-fold(400 000) = MX$ 65 967.535048"
        (is (== 65967.535048M (:amount (:liability c)))))
      (testing "provenance records the CGT fold provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-art-120-cgt-pit-fold"))))))

;; ============================================================================
;; §6. Investment-income fold
;; ============================================================================

(deftest investment-pit-base-additions-fold
  (testing "MX$ 300 000 wage + :investment-pit-base-additions MX$ 50 000 → base 350 000"
    (let [facts (compute {} {:gross-income                   300000M
                             :investment-pit-base-additions   50000M})
          c     (isr-pf-component facts)]
      (testing "base = 350 000"
        (is (== 350000M (:amount (:base c)))))
      (testing "liability = bracket-fold(350 000) = MX$ 54 744.033256"
        (is (== 54744.033256M (:amount (:liability c)))))
      (testing "provenance records the investment fold"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-art-140-investment-pit-fold"))))))

;; ============================================================================
;; §7. Art. 140 factor-credit non-refundable — clamps at zero
;; ============================================================================

(deftest art-140-factor-credit-non-refundable-clamps-at-zero
  (testing "low income + large factor-credit clamps liability at zero
            (non-refundable per `:refundable? false`)"
    (let [facts (compute {} {:gross-income                    10000M
                             :investment-pit-credits-factor   5000M})
          c     (isr-pf-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "factor-credit recorded as non-refundable"
        (is (contains? credits-by-code :mx-art-140-factor-credit))
        (is (false? (:refundable? (credits-by-code :mx-art-140-factor-credit)))))
      (testing "liability = max(0, gross − 5 000) = 0 (clamped)"
        (is (== 0M (:amount (:liability c)))
            "non-refundable credit cannot drive liability negative")))))

;; ============================================================================
;; §8. Bank-WHT refundable drives liability negative
;; ============================================================================

(deftest bank-wht-credit-refundable-drives-liability-negative
  (testing "MX$ 10 000 wage + :investment-pit-credits-bank-wht MX$ 200
            → liability MX$ 38.928448; gross MX$ 238.928448"
    (let [facts (compute {} {:gross-income                     10000M
                             :investment-pit-credits-bank-wht   200M})
          c     (isr-pf-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "bank-WHT credit fires, refundable? true"
        (is (contains? credits-by-code :mx-bank-wht-credit))
        (is (true? (:refundable? (credits-by-code :mx-bank-wht-credit)))))
      (testing "liability = gross − 200 (refundable; can go negative)"
        (is (== 38.928448M (:amount (:liability c))))))))

;; ============================================================================
;; §9. FTC non-refundable clamps at zero
;; ============================================================================

(deftest foreign-tax-credit-non-refundable-clamps-at-zero
  (testing "low income + FTC clamps liability at zero (non-refundable)"
    (let [facts (compute {} {:gross-income                10000M
                             :investment-pit-credits-ftc   5000M})
          c     (isr-pf-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "FTC recorded as non-refundable"
        (is (contains? credits-by-code :mx-foreign-tax-credit))
        (is (false? (:refundable? (credits-by-code :mx-foreign-tax-credit)))))
      (testing "liability clamped at 0"
        (is (== 0M (:amount (:liability c))))))))

;; ============================================================================
;; §10. Top-band fires at high income
;; ============================================================================

(deftest top-band-marginal-rate-5m-income-2025
  (testing "MX$ 5 000 000 taxable → top-band (35 %) fires; substrate
            computes the exact integral MX$ 1 585 850.295196"
    (let [facts (compute {} {:gross-income 5000000M})
          c     (isr-pf-component facts)]
      (testing "top band rate = 0.35"
        (is (== 0.35M (-> c :schedule :brackets last :rate))))
      (testing "top band :upper is nil (open top)"
        (is (nil? (-> c :schedule :brackets last :upper))))
      (testing "liability = MX$ 1 585 850.295196"
        (is (== 1585850.295196M (:amount (:liability c))))))))

;; ============================================================================
;; §11. Substrate property — install idempotence (with bracket dedup)
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
                                   [(.startsWith ^String ?code "MX.PIT.")]]
                                 (d/db conn) :mx))
            ;; Only PIT statute is installed by `fresh` — every MX
            ;; provision in the DB is a PIT provision.
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code _]]
                                 (d/db conn) :mx))
            n-brackets (count (d/q '[:find ?b
                                     :where
                                     [?p :kontor.parameter/code "MX.PIT.art-152.brackets"]
                                     [?b :kontor.parameter-bracket/parameter ?p]]
                                   (d/db conn)))]
        (is (= (count pit-statute/parameters) n-params))
        (is (= (count pit-statute/provisions) n-provs))
        (is (= (count pit-statute/parameter-brackets) n-brackets)
            "bracket dedup did not multiply rows; 33 rows total")))))

;; ============================================================================
;; §12. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "subsidio scenario records the subsidio provision"
    (let [facts (compute {} {:gross-income    80000M
                             :subsidio-empleo  5700M})
          c     (isr-pf-component facts)]
      (is (contains? (set (-> c :provenance :provisions-applied))
                     "MX-LISR-art-96-bis-subsidio-empleo"))
      (is (= :mx-pit (-> c :provenance :provider-id))))))

;; ============================================================================
;; §13. Substrate property — missing gross-income raises
;; ============================================================================

(deftest missing-gross-income-raises
  (testing "absent :inputs :gross-income → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gross-income"
                            (ptp/period-tax-facts
                             (mx-pit/mx-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-mxn-on-every-money
  (let [facts (compute {} {:gross-income 300000M})]
    (is (every? #(= :MXN (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :MXN (:commodity (:liability %)))
                (:components facts)))))

;; ============================================================================
;; subsidio-empleo-input — substrate-aware magnitude helper
;; ============================================================================

(deftest subsidio-empleo-input-reads-substrate-parameters
  (testing "the substrate-aware helper reads UMA-month × factor parameters"
    (let [conn (fresh)
          db   (d/db conn)
          ;; 2025 UMA-month is MX$ 496; factor is 0.1182
          full-year (mx-pit/subsidio-empleo-input 12 #inst "2025-06-30" db)
          half-year (mx-pit/subsidio-empleo-input 6  #inst "2025-06-30" db)
          ;; 2024 UMA-month is MX$ 475; factor is 0.1182
          full-year-2024 (mx-pit/subsidio-empleo-input 12 #inst "2024-06-30" db)]
      (is (== 703.5264M full-year)
          "12 × 496 × 0.1182 — uses 2025 UMA-month")
      (is (== 351.7632M half-year)
          "scales linearly with month count")
      (is (== 673.74M full-year-2024)
          "12 × 475 × 0.1182 — uses 2024 UMA-month at as-of 2024-06-30"))))
