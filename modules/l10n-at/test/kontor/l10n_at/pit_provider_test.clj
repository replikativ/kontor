(ns kontor.l10n-at.pit-provider-test
  "AT personal income tax provider tests — ADR-101 substrate's AT
   consumer (ADR-104 template, applied to Austria). Validates that the
   statute-as-data path (`:parameter` + `:parameter-bracket` +
   `:provision` rows + `kontor.tax.statute/apply-provisions` fold)
   computes real AT ESt against published worked examples.

   Worked examples cited:

   - **§1 Single filer @ €40 k** — Verkehrsabsetz only; ESt €7 106.10.
     Source: §33 EStG Tarifstufen 2025;
     arbeiterkammer.at Brutto-Netto-Rechner cross-check.
   - **§2 Family of 4 @ €50 k** — Familienbonus + Alleinverdiener +
     Verkehrsabsetz; ESt €6 292.78. Source: WKO Aktuelle Werte 2026 +
     AK calculator.
   - **§3 Regelbesteuerung fold + KESt-prepaid refundable credit** —
     €30 k employment + €5 k dividends + €1 375 KESt prepaid; ESt
     €3 814.70. Source: §27a Abs 5 EStG mechanism.
   - **§4 Bitemporal swap 2023 vs 2025 brackets** — Kalte-Progression-
     Abschaffung — same €40 k taxable assessed as-of 2023-12-31 fires
     the 2023 bracket set, 2025-12-31 fires the 2025 bracket set.
   - **§5 §30 Abs 7 ImmoESt loss carry against §28** — €40 k base +
     €5 k loss-slice → taxable €35 k.
   - **§6 Kindermehrbetrag** — refundable when consumer signals
     eligibility.
   - **§7 Alleinverdiener for 3 children** — picks 2-child amount +
     1 × per-addl-amount.
   - **§8 DBA-Quellensteuer non-refundable** — credit reduces liability
     but does not push below zero from the gross.
   - **§9 Install idempotence** — substrate property.
   - **§10 Provenance** — `:provisions-applied` records the codes.
   - **§11 Missing gross-income** — ex-info."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.pit-provider :as at-pit]
            [kontor.l10n-at.pit-statute :as pit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the AT PIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (pit-statute/install! conn)
    conn))

(defn- compute
  "Run the AT PIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (at-pit/at-pit-provider {})
      {:entity   :individual
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- est-component
  "Pull the ESt component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Single filer @ €40 k — Verkehrsabsetz only
;; ============================================================================

(deftest single-filer-40k-2025
  (testing "single Arbeitnehmer, €40 k taxable, no children, no Alleinverdiener,
            2025 brackets → ESt €7 106.10"
    (let [facts (compute {:employment-relationship? true}
                         {:gross-income 40000M})
          c     (est-component facts)]
      (testing "schedule = :progressive-bracket with 7 bands (2025 set)"
        (is (= :progressive-bracket (:kontor.schedule/type (:schedule c))))
        (is (= 7 (count (:brackets (:schedule c)))))
        (is (== 13308M (-> c :schedule :brackets first :upper))
            "first kink is the 2025 0%-bracket top"))
      (testing "base = €40 000 (no base adjustments)"
        (is (== 40000M (:amount (:base c)))))
      (testing "gross-liability = €7 593.10 (bracket fold over 40 000)"
        (is (== 7593.10M (:amount (:gross-liability c)))))
      (testing "credit = Verkehrsabsetzbetrag €487 (non-refundable)"
        (is (= 1 (count (:credits c))))
        (let [vk (first (:credits c))]
          (is (= :at-verkehrsabsetz (:code vk)))
          (is (== 487M (:amount vk)))
          (is (false? (:refundable? vk)))))
      (testing "liability = 7 593.10 − 487 = 7 106.10"
        (is (== 7106.10M (:amount (:liability c))))
        (is (== 7106.10M (total-liability facts)))))))

;; ============================================================================
;; §2. Family of 4 @ €50 k — Familienbonus + Alleinverdiener
;; ============================================================================

(deftest family-of-4-50k-2025-familienbonus-alleinverdiener
  (testing "single-earner, non-working spouse, 2 children < 18, €50 k taxable,
            2025 brackets → tax-before €11 593.10; ESt €6 292.78"
    (let [facts (compute {:employment-relationship? true
                          :alleinverdiener?         true
                          :children-count           2
                          :children-under-18-count  2
                          :children-over-18-count   0}
                         {:gross-income 50000M})
          c     (est-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "gross-liability = €11 593.10"
        (is (== 11593.10M (:amount (:gross-liability c)))))
      (testing "Familienbonus (2 × €2 000.16) = €4 000.32 (non-refundable)"
        (is (contains? credits-by-code :at-familienbonus-plus-under-18))
        (is (== 4000.32M (:amount (credits-by-code :at-familienbonus-plus-under-18))))
        (is (false? (:refundable? (credits-by-code :at-familienbonus-plus-under-18)))))
      (testing "Alleinverdiener (2 children) = €813 (refundable)"
        (is (contains? credits-by-code :at-alleinverdiener))
        (is (== 813M (:amount (credits-by-code :at-alleinverdiener))))
        (is (true? (:refundable? (credits-by-code :at-alleinverdiener)))))
      (testing "Verkehrsabsetz = €487"
        (is (contains? credits-by-code :at-verkehrsabsetz))
        (is (== 487M (:amount (credits-by-code :at-verkehrsabsetz)))))
      (testing "liability = €6 292.78"
        (is (== 6292.78M (:amount (:liability c))))))))

;; ============================================================================
;; §3. Regelbesteuerung fold + KESt-prepaid refundable credit
;; ============================================================================

(deftest regelbesteuerung-fold-with-kest-prepaid
  (testing "Regelbesteuerungsoption: €30 k employment + €5 k dividends fold +
            €1 375 KESt prepaid (refundable) → ESt €3 814.70"
    (let [facts (compute {:employment-relationship? true}
                         {:gross-income                            30000M
                          :investment-pit-base-additions           5000M
                          :investment-pit-credits-kest-prepaid     1375M})
          c     (est-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "base = €30 000 + €5 000 = €35 000 (Regelbesteuerung fold)"
        (is (== 35000M (:amount (:base c)))))
      (testing "base-transform records the Regelbesteuerung fold"
        (let [items (:items (:base-transform c))]
          (is (= 1 (count items)))
          (is (= :at-regelbesteuerung-fold (:code (first items))))
          (is (== 5000M (:amount (first items))))))
      (testing "gross-liability = €5 676.70 (bracket fold over 35 000)"
        (is (== 5676.70M (:amount (:gross-liability c)))))
      (testing "KESt prepaid credit = €1 375 (refundable)"
        (is (contains? credits-by-code :at-kest-prepaid))
        (is (== 1375M (:amount (credits-by-code :at-kest-prepaid))))
        (is (true? (:refundable? (credits-by-code :at-kest-prepaid)))))
      (testing "liability = €3 814.70 (5 676.70 − 1 375 − 487)"
        (is (== 3814.70M (:amount (:liability c))))))))

;; ============================================================================
;; §4. Bitemporal swap — 2023 vs 2025 brackets
;; ============================================================================

(deftest bitemporal-swap-2023-vs-2025-brackets
  (testing "same €40 k taxable assessed against 2023 set vs 2025 set
            — Kalte-Progression-Abschaffung yields different ESt"
    (let [pre  (compute {:employment-relationship? true}
                        {:gross-income 40000M}
                        #inst "2023-12-31")
          post (compute {:employment-relationship? true}
                        {:gross-income 40000M}
                        #inst "2025-12-31")
          pre-c  (est-component pre)
          post-c (est-component post)]
      (testing "pre's first kink (2023 0%-band top) is €11 693"
        (is (== 11693M (-> pre-c :schedule :brackets first :upper))))
      (testing "post's first kink (2025 0%-band top) is €13 308"
        (is (== 13308M (-> post-c :schedule :brackets first :upper))))
      (testing "ESt differs (the 2025 set produces a smaller liability for €40k)"
        ;; 2023: 8 309 × 0.20 + 12 941 × 0.30 + 7 925 × 0.40 = 1 661.80 + 3 882.30 + 3 170.00 = 8 714.10
        ;; minus Verkehrsabsetz 487 (2023 amount differs — but per the indexed
        ;; parameter; v1 ships only 2024-2026 so the as-of 2023-12-31 finds
        ;; NO Verkehrsabsetz value and the provision returns nil; tests just
        ;; confirm gross differs).
        (is (not= (:amount (:gross-liability pre-c))
                  (:amount (:gross-liability post-c)))
            "different bracket sets fire → different gross")))))

;; ============================================================================
;; §5. §30 Abs 7 ImmoESt loss carry against §28 Vermietung
;; ============================================================================

(deftest §30-immo-loss-carry-deducts-from-base
  (testing "€40 k base + :cgt-pit-base-deductions-§28 €5 k → taxable €35 k"
    (let [facts (compute {:employment-relationship? true}
                         {:gross-income                  40000M
                          :cgt-pit-base-deductions-§28   5000M})
          c     (est-component facts)
          items (:items (:base-transform c))]
      (testing "base = €40 000 − €5 000 = €35 000"
        (is (== 35000M (:amount (:base c)))))
      (testing "base-transform records the §30 Abs 7 ImmoESt-loss carry"
        (is (= 1 (count items)))
        (is (= :at-§30-immo-loss-carry (:code (first items))))
        (is (== 5000M (:amount (first items)))))
      (testing "provenance records the §30 Abs 7 provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "AT-EStG-§30-Abs-7-vermietung-loss-carry"))))))

;; ============================================================================
;; §6. Kindermehrbetrag — refundable when consumer signals eligibility
;; ============================================================================

(deftest kindermehrbetrag-fires-when-eligible
  (testing "low income + 2 children + Kindermehrbetrag-eligible? → refundable credit fires"
    (let [facts (compute {:employment-relationship?    true
                          :children-count              2
                          :children-under-18-count     2
                          :kindermehrbetrag-eligible?  true}
                         {:gross-income 15000M})
          c     (est-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "Kindermehrbetrag = 2 × €700 = €1 400 (refundable)"
        (is (contains? credits-by-code :at-kindermehrbetrag))
        (is (== 1400M (:amount (credits-by-code :at-kindermehrbetrag))))
        (is (true? (:refundable? (credits-by-code :at-kindermehrbetrag))))))))

;; ============================================================================
;; §7. Alleinverdiener 3-children amount
;; ============================================================================

(deftest alleinverdiener-3-children-amount
  (testing "3 children → 2-child amount + 1 × per-addl = €813 + €268 = €1 081"
    (let [facts (compute {:employment-relationship? true
                          :alleinverdiener?         true
                          :children-count           3
                          :children-under-18-count  3}
                         {:gross-income 60000M})
          c     (est-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "Alleinverdiener (3 children) = €1 081"
        (is (== 1081M (:amount (credits-by-code :at-alleinverdiener))))))))

;; ============================================================================
;; §8. DBA-Quellensteuer non-refundable
;; ============================================================================

(deftest dba-quellensteuer-credit-non-refundable
  (testing "DBA-Quellensteuer reduces liability but is non-refundable"
    (let [facts (compute {:employment-relationship? true}
                         {:gross-income                                30000M
                          :investment-pit-credits-non-refundable-dba   200M})
          c     (est-component facts)
          credits-by-code (into {} (map (juxt :code identity) (:credits c)))]
      (testing "DBA credit = €200 (non-refundable)"
        (is (contains? credits-by-code :at-dba-quellensteuer))
        (is (== 200M (:amount (credits-by-code :at-dba-quellensteuer))))
        (is (false? (:refundable? (credits-by-code :at-dba-quellensteuer)))))
      (testing "liability is positive (the credit just reduces, doesn't push negative)"
        (is (pos? (:amount (:liability c))))))))

;; ============================================================================
;; §9. Substrate property — install idempotence
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
                                   [(.startsWith ^String ?code "AT.EStG.")]]
                                 (d/db conn) :at))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "AT-EStG-")]]
                                 (d/db conn) :at))
            n-brackets (count (d/q '[:find ?b
                                     :where
                                     [?p :kontor.parameter/code "AT.EStG.§33-Abs-1.brackets"]
                                     [?b :kontor.parameter-bracket/parameter ?p]]
                                   (d/db conn)))]
        (is (= (count pit-statute/parameters) n-params))
        (is (= (count pit-statute/provisions) n-provs))
        (is (= (count pit-statute/parameter-brackets-rows) n-brackets)
            "bracket dedup did not multiply rows")))))

;; ============================================================================
;; §10. Substrate property — provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "family-of-4 case records Familienbonus + Alleinverdiener + Verkehrsabsetz"
    (let [facts (compute {:employment-relationship? true
                          :alleinverdiener?         true
                          :children-count           2
                          :children-under-18-count  2}
                         {:gross-income 50000M})
          c     (est-component facts)]
      (is (= #{"AT-EStG-§33-Abs-3a-familienbonus-under-18"
               "AT-EStG-§33-Abs-4-Z-1-alleinverdiener"
               "AT-EStG-§33-Abs-5-verkehrsabsetzbetrag"}
             (set (-> c :provenance :provisions-applied)))))))

;; ============================================================================
;; §11. Substrate property — missing gross-income raises
;; ============================================================================

(deftest missing-gross-income-raises
  (testing "absent :inputs :gross-income → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gross-income"
                            (ptp/period-tax-facts
                             (at-pit/at-pit-provider {})
                             {:entity   :individual
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-eur-on-every-money
  (let [facts (compute {:employment-relationship? true}
                       {:gross-income 40000M})]
    (is (every? #(= :EUR (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :EUR (:commodity (:liability %)))
                (:components facts)))))
