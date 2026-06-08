(ns kontor.l10n-cn.cit-provider-test
  "CN corporate income tax provider tests — ADR-101 substrate's CN
   consumer. Validates that the
   statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` two-pass fold) computes real
   CN EIT against published worked examples.

   Worked examples cited:

   - **§1 Standard CN-LLC @ ¥1M book-profit** — flat 25 % × ¥1M
     = ¥250 000. Source: STA published worked example at chinatax.gov.cn.
   - **§2 SLPE @ ¥2.5M taxable** — 5 % effective flat × ¥2.5M
     = ¥125 000. Source: Cai Shui [2023] 12 §1.
   - **§3 HNTE @ ¥1M taxable** — 15 % flat × ¥1M = ¥150 000.
     Source: §28 ¶2 EIT Law.
   - **§4 SLPE cliff (¥3 000 100)** — fails the [:leq 3M] cliff →
     standard 25 % on the WHOLE base = ¥750 025. The canonical
     two-pass-query test.
   - **§5 Bitemporal swap (R&D multiplier 2022 vs 2024)** — qualifying
     expense ¥1M assessed pre-2023 (1.75× → extra deduction ¥750 000)
     vs post-2023 (2.00× → extra ¥1 000 000).
   - **§6 R&D super-deduction general 200 %** — ¥5M qualifying R&D
     reduces base by ¥5M (extra 100 %).
   - **§7 R&D super-deduction IC + machine-tools 220 %** — ¥5M
     qualifying R&D + `:rd-sector :ic-machine-tools` reduces base by
     ¥6M (extra 120 %).
   - **§8 R&D negative-list excludes provision** — `:industry
     :entertainment` blocks R&D super-deduction; base unchanged.
   - **§9 §10 non-deductibles add-back** — book-profit ¥1M + ¥200k
     non-deductibles → base ¥1.2M; EIT ¥300 000.
   - **§10 §26 TRE-dividend exemption** — book-profit ¥1M + ¥100k
     exempt-dividend → base ¥900k; EIT ¥225 000.
   - **§11 Complex case — every lever fires** — ¥10M + ¥2M non-ded
     + ¥5M R&D − ¥1M TRE-dividend + HNTE → integration test.
   - **§12 Install idempotence** — substrate property.
   - **§13 Provenance trail** — `:provisions-applied` records the codes.
   - **§14 Missing book-profit raises** — substrate property."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.l10n-cn.cit-provider :as cn-cit]
            [kontor.l10n-cn.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the CN CIT + CGT statutes installed (CGT ships
   `CN.EIT.standard-rate`; CIT consumes it). Order matches
   `kontor.l10n-cn.preset/install-all!`."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the CN CIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Convenience wrapper. Defaults the `:as-of` to
   2025-12-31 (post-Cai Shui [2023] 7 R&D 200 % regime + the
   Cai Shui [2023] 12 SLPE 5 % flat regime)."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (cn-cit/cn-cit-provider {})
      {:entity   :cn-llc
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- eit-component
  "Pull the EIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Standard CN-LLC @ ¥1M book-profit — flat 25 % EIT
;; ============================================================================

(deftest standard-cn-llc-1m-book-profit
  (testing "standard CN-LLC, book-profit ¥1M, as-of 2025-12-31: flat 25 % EIT ¥250 000"
    (let [facts (compute {} {:book-profit 1000000M})
          c     (eit-component facts)]
      (testing "schedule = flat 25 % (standard rate)"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.25M (:rate (:schedule c)))))
      (testing "base = book-profit (no adjustments fire)"
        (is (== 1000000M (:amount (:base c)))))
      (testing "gross-liability = ¥250 000"
        (is (== 250000M (:amount (:gross-liability c)))))
      (testing "liability = ¥250 000 (no credits)"
        (is (== 250000M (:amount (:liability c))))
        (is (== 250000M (total-liability facts))))
      (testing "no schedule-override fired"
        (is (empty? (-> c :provenance :provisions-applied))
            "no SLPE / HNTE / regional provision fires for a vanilla LLC")))))

;; ============================================================================
;; §2. SLPE @ ¥2.5M taxable — schedule-override fires (cliff holds)
;; ============================================================================

(deftest slpe-2-5m-taxable
  (testing "SLPE @ ¥2.5M: cliff condition holds → 5 % flat → ¥125 000"
    (let [facts (compute {:slpe? true} {:book-profit 2500000M})
          c     (eit-component facts)]
      (testing "SLPE schedule-override fired — rate is 5 %"
        (is (== 0.05M (:rate (:schedule c)))))
      (testing "liability = 2 500 000 × 5 % = 125 000"
        (is (== 125000M (:amount (:liability c)))))
      (testing "provenance records the SLPE provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§28-¶1-slpe"))))))

;; ============================================================================
;; §3. HNTE @ ¥1M taxable — schedule-override fires
;; ============================================================================

(deftest hnte-1m-taxable
  (testing "HNTE @ ¥1M: §28 ¶2 → 15 % flat → ¥150 000"
    (let [facts (compute {:hnte? true} {:book-profit 1000000M})
          c     (eit-component facts)]
      (testing "HNTE schedule-override fired — rate is 15 %"
        (is (== 0.15M (:rate (:schedule c)))))
      (testing "liability = 1 000 000 × 15 % = 150 000"
        (is (== 150000M (:amount (:liability c)))))
      (testing "provenance records the HNTE provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§28-¶2-hnte"))))))

;; ============================================================================
;; §4. SLPE cliff just-over-3M — the canonical two-pass test
;; ============================================================================

(deftest slpe-cliff-just-over-3m
  (testing "Note 186 §2.4 / §6 CN-S1 — book-profit ¥3 000 100 + :slpe? true:
            cliff condition [:leq :taxable-income 3M] FAILS at pass 2;
            standard 25 % applies to the WHOLE base = ¥750 025.
            The two-pass query pattern is what makes this work (else
            the cliff would silently mis-resolve since taxable-income
            doesn't exist on pass 1)."
    (let [facts (compute {:slpe? true} {:book-profit 3000100M})
          c     (eit-component facts)]
      (testing "schedule = flat 25 % (SLPE did NOT fire)"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.25M (:rate (:schedule c)))))
      (testing "liability = 3 000 100 × 25 % = 750 025 (standard, not SLPE)"
        (is (== 750025M (:amount (:liability c)))))
      (testing "SLPE provision DOES NOT appear in provisions-applied"
        (is (not (contains? (set (-> c :provenance :provisions-applied))
                            "CN-EITLaw-§28-¶1-slpe"))
            "the cliff failed — SLPE override did not fire")))))

;; ============================================================================
;; §5. Bitemporal swap — R&D multiplier 2022 (1.75) vs 2024 (2.00)
;; ============================================================================

(deftest bitemporal-swap-rd-multiplier
  (testing "as-of 2022-12-31 fires the 1.75× multiplier (extra 75 % deduction);
            as-of 2024-12-31 fires the 2.00× multiplier (extra 100 %)."
    (let [pre  (compute {:industry :tech}
                        {:book-profit              5000000M
                         :rd-qualifying-expense    1000000M}
                        #inst "2022-12-31")
          post (compute {:industry :tech}
                        {:book-profit              5000000M
                         :rd-qualifying-expense    1000000M}
                        #inst "2024-12-31")
          pre-c  (eit-component pre)
          post-c (eit-component post)]
      (testing "pre-2023 base = 5 000 000 − 1 000 000 × 0.75 = 4 250 000"
        (is (== 4250000M (:amount (:base pre-c)))))
      (testing "post-2023 base = 5 000 000 − 1 000 000 × 1.00 = 4 000 000"
        (is (== 4000000M (:amount (:base post-c)))))
      (testing "different multipliers fire → different liabilities"
        (is (not= (:amount (:liability pre-c))
                  (:amount (:liability post-c))))
        (is (== 1062500M (:amount (:liability pre-c)))
            "4 250 000 × 25 %")
        (is (== 1000000M (:amount (:liability post-c)))
            "4 000 000 × 25 %")))))

;; ============================================================================
;; §6. R&D super-deduction (general sector, 200 %)
;; ============================================================================

(deftest rd-super-deduction-general-200pct
  (testing "¥5M qualifying R&D + general sector → base reduces by ¥5M (extra 100 %);
            EIT computed on reduced base"
    (let [facts (compute {:industry :tech}
                         {:book-profit              10000000M
                          :rd-qualifying-expense    5000000M})
          c     (eit-component facts)]
      (testing "base = 10 000 000 − 5 000 000 × (2.00 − 1.00) = 5 000 000"
        (is (== 5000000M (:amount (:base c)))))
      (testing "liability = 5 000 000 × 25 % = 1 250 000"
        (is (== 1250000M (:amount (:liability c)))))
      (testing "provenance records the R&D-general provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§30-rd-general"))))))

;; ============================================================================
;; §7. R&D super-deduction (IC + machine-tools, 220 %)
;; ============================================================================

(deftest rd-super-deduction-ic-mt-220pct
  (testing "¥5M qualifying R&D + :rd-sector :ic-machine-tools → base
            reduces by ¥6M (extra 120 %)"
    (let [facts (compute {:industry  :tech
                          :rd-sector :ic-machine-tools}
                         {:book-profit              10000000M
                          :rd-qualifying-expense    5000000M})
          c     (eit-component facts)]
      (testing "base = 10 000 000 − 5 000 000 × (2.20 − 1.00) = 4 000 000"
        (is (== 4000000M (:amount (:base c)))))
      (testing "liability = 4 000 000 × 25 % = 1 000 000"
        (is (== 1000000M (:amount (:liability c)))))
      (testing "provenance records the IC + machine-tools provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§30-rd-ic-mt"))))))

;; ============================================================================
;; §8. R&D negative-list — entertainment industry blocks the deduction
;; ============================================================================

(deftest rd-negative-list-blocks-deduction
  (testing "¥5M qualifying R&D + :industry :entertainment → R&D
            provision does NOT fire (negative-list condition fails);
            base unchanged"
    (let [facts (compute {:industry :entertainment}
                         {:book-profit              10000000M
                          :rd-qualifying-expense    5000000M})
          c     (eit-component facts)]
      (testing "base = book-profit (R&D deduction blocked)"
        (is (== 10000000M (:amount (:base c)))))
      (testing "liability = 10 000 000 × 25 % = 2 500 000"
        (is (== 2500000M (:amount (:liability c)))))
      (testing "neither R&D provision appears in provenance"
        (let [applied (set (-> c :provenance :provisions-applied))]
          (is (not (contains? applied "CN-EITLaw-§30-rd-general")))
          (is (not (contains? applied "CN-EITLaw-§30-rd-ic-mt"))))))))

;; ============================================================================
;; §9. §10 non-deductibles add-back
;; ============================================================================

(deftest §10-non-deductibles-add-back
  (testing "book-profit ¥1M + :cn-non-deductibles ¥200 000 → base ¥1.2M; EIT ¥300 000"
    (let [facts (compute {}
                         {:book-profit          1000000M
                          :cn-non-deductibles   200000M})
          c     (eit-component facts)]
      (testing "base = 1 000 000 + 200 000 = 1 200 000"
        (is (== 1200000M (:amount (:base c)))))
      (testing "liability = 1 200 000 × 25 % = 300 000"
        (is (== 300000M (:amount (:liability c)))))
      (testing "provenance records the §10 provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§10-non-deductibles"))))))

;; ============================================================================
;; §10. §26 ¶2 TRE-to-TRE dividend exemption (deduction lane)
;; ============================================================================

(deftest §26-tre-dividend-exemption
  (testing "book-profit ¥1M + :cn-tre-dividend-exemption ¥100 000 → base ¥900k; EIT ¥225 000"
    (let [facts (compute {}
                         {:book-profit                  1000000M
                          :cn-tre-dividend-exemption    100000M})
          c     (eit-component facts)]
      (testing "base = 1 000 000 − 100 000 = 900 000"
        (is (== 900000M (:amount (:base c)))))
      (testing "liability = 900 000 × 25 % = 225 000"
        (is (== 225000M (:amount (:liability c)))))
      (testing "provenance records the §26 provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "CN-EITLaw-§26-¶2-tre-dividend"))))))

;; ============================================================================
;; §11. Complex case — every lever fires (integration)
;; ============================================================================

(deftest complex-case-everything-fires
  (testing "book-profit ¥10M + ¥2M non-ded + ¥1M TRE-dividend + ¥5M R&D
            (general) + HNTE → taxable ¥10M + 2M − 1M − 5M = ¥6M;
            EIT ¥6M × 15 % = ¥900 000"
    (let [facts (compute {:hnte?    true
                          :industry :tech}
                         {:book-profit                  10000000M
                          :cn-non-deductibles            2000000M
                          :cn-tre-dividend-exemption     1000000M
                          :rd-qualifying-expense         5000000M})
          c     (eit-component facts)]
      (testing "base = 10 000 000 + 2 000 000 − 1 000 000 − 5 000 000 = 6 000 000"
        (is (== 6000000M (:amount (:base c)))))
      (testing "HNTE schedule fired (15 %)"
        (is (== 0.15M (:rate (:schedule c)))))
      (testing "liability = 6 000 000 × 15 % = 900 000"
        (is (== 900000M (:amount (:liability c)))))
      (testing "provenance lists all four firing provision codes"
        (let [applied (set (-> c :provenance :provisions-applied))]
          (is (contains? applied "CN-EITLaw-§28-¶2-hnte"))
          (is (contains? applied "CN-EITLaw-§10-non-deductibles"))
          (is (contains? applied "CN-EITLaw-§26-¶2-tre-dividend"))
          (is (contains? applied "CN-EITLaw-§30-rd-general")))))))

;; ============================================================================
;; §12. §23 foreign tax credit (non-refundable)
;; ============================================================================

(deftest §23-foreign-tax-credit-non-refundable
  (testing "book-profit ¥1M + :cn-foreign-tax-credit ¥50 000 → liability ¥200 000"
    (let [facts (compute {}
                         {:book-profit                1000000M
                          :cn-foreign-tax-credit      50000M})
          c     (eit-component facts)]
      (testing "gross = 1 000 000 × 25 % = 250 000"
        (is (== 250000M (:amount (:gross-liability c)))))
      (testing "liability = 250 000 − 50 000 = 200 000"
        (is (== 200000M (:amount (:liability c)))))
      (testing "credit is non-refundable"
        (is (= 1 (count (:credits c))))
        (is (false? (:refundable? (first (:credits c)))))))))

;; ============================================================================
;; §13. Substrate property — install idempotence
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cgt-statute/install! conn)
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "CN.EIT.")]
                                   ;; only the NEW params (excluding the standard-rate
                                   ;; which ships from cgt-statute).
                                   [(not= ?code "CN.EIT.standard-rate")]
                                   [(not= ?code "CN.EIT.non-resident-wht-rate")]
                                   [(not= ?code "CN.EIT.special-restructuring.equity-threshold")]
                                   [(not= ?code "CN.EIT.special-restructuring.equity-payment-threshold")]
                                   [(not= ?code "CN.EIT.special-restructuring.lockup-months")]]
                                 (d/db conn) :cn))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "CN-EITLaw-")]]
                                 (d/db conn) :cn))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

;; ============================================================================
;; §14. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "clean case fires NO provisions (no driver facts present)"
    (let [facts (compute {} {:book-profit 1000000M})
          c     (eit-component facts)]
      (is (empty? (-> c :provenance :provisions-applied))
          "no driver facts → no provisions fire — the silent-no-op posture"))))

;; ============================================================================
;; §15. Substrate property — missing book-profit raises
;; ============================================================================

(deftest missing-book-profit-raises
  (testing "absent :inputs :book-profit → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (cn-cit/cn-cit-provider {})
                             {:entity   :cn-llc
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts (CNY everywhere)
;; ============================================================================

(deftest functional-commodity-is-cny-on-every-money
  (let [facts (compute {} {:book-profit 1000000M})]
    (is (every? #(= :CNY (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :CNY (:commodity (:liability %)))
                (:components facts)))))
