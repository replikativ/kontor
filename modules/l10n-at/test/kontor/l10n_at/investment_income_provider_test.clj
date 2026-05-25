(ns kontor.l10n-at.investment-income-provider-test
  "Tests for the AT investment-income providers (ADR-099 + ADR-101,
   research note 154). Coverage:

   - §1 KESt 27.5 % on dividends (Endbesteuerung) — Frau Huber (note 154 §2.1)
   - §2 KESt 25 % on Sparbuch interest — Frau Huber (note 154 §2.3)
   - §3 Endbesteuerung delta — bank-prepaid matches gross-due → zero liability
   - §4 Regelbesteuerungsoption (§27a Abs 5 EStG) — fold into PIT base
   - §5 DBA-Quellensteuer credit (note 154 §2.4) — Apple US dividend
   - §6 §10 Abs 1 Z 1 KStG domestic dividend exempt (note 154 §2.5)
   - §7 §10 Abs 1 Z 5-6 foreign-portfolio exempt + BFG 2024 lost credit (note 154 §2.6)
   - §8 §10 Abs 3 Option zur Steuerwirksamkeit → fully taxable
   - §9 Foreign-corp guard (note 146 §3.2) — blocks §10 INVERSION
   - §10 §10 Abs 4 switch-over — low-tax jurisdiction (note 154 §2.7)
   - §11 2026 Pillar Two threshold cliff (12.5 % → 15 %)
   - §12 §27 Abs 8 within-year netting flagged as TODO (docstring)
   - §13 Provider plumbing — id, commodity, kind enum."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.l10n-at.investment-income-provider :as inv]
            [kontor.l10n-at.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the AT CGT statute (for KESt rates + §10
   thresholds + CIT rate) and the AT investment-income statute (for
   §10 Abs 4 low-tax threshold) installed."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (inv-statute/install! conn)
    (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro"
                       :commodity/precision 2}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
;; p2025 ends BEFORE the 2026-01-01 Pillar Two threshold cutover —
;; useful for the 12.5 % branch.
(def ^:private p2025 {:from #inst "2025-01-01" :to #inst "2025-12-31"})

(defn- run-individual
  "Build the individual provider; call `period-tax-facts` with pre-
   supplied bases via `:inputs :investment-income-bases`. Extra ctx
   merges on top."
  [conn bases & [extra-ctx]]
  (let [provider (inv/at-kest-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity nil
             :period p2026
             :inputs (merge {:investment-income-bases bases}
                            (:inputs extra-ctx))}
            (dissoc extra-ctx :inputs)))))

(defn- run-corporate
  "Build the corporate provider; call `period-tax-facts`."
  [conn bases & [extra-ctx]]
  (let [provider (inv/at-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity nil
             :period p2026
             :inputs (merge {:investment-income-bases bases}
                            (:inputs extra-ctx))}
            (dissoc extra-ctx :inputs)))))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. KESt 27.5 % on dividends — Frau Huber (note 154 §2.1)
;; ============================================================================

(deftest kest-27-5-on-dividends-note-154-§2-1
  (testing "Frau Huber: €1,500 OMV dividend → 27.5 % × 1500 = €412.50"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends-27-5 1500M}
                                {:inputs {:at-kest-prepaid 412.50M}})
          §27   (component-by-lane facts :at-kest-wertpapier-vermoegen)]
      (is (some? §27) "wertpapier 27.5 % bucket component is present")
      (is (== 1500M (-> §27 :base :amount)))
      (is (== 412.50M (-> §27 :gross-liability :amount))
          "27.5 % × 1500 = 412.50 gross KESt")
      (is (== 0M (-> §27 :liability :amount))
          "Endbesteuerung: bank-withheld 412.50 matches gross-due → 0 net liability")
      (is (== 412.50M (-> §27 :prepaid :amount)))
      (is (= :endbesteuerung (:regime §27))))))

;; ============================================================================
;; §2. KESt 25 % on Sparbuch interest — Frau Huber (note 154 §2.3)
;; ============================================================================

(deftest kest-25-on-sparbuch-note-154-§2-3
  (testing "Frau Huber: €800 BAWAG interest → 25 % × 800 = €200"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:sparbuch-interest-25 800M}
                                {:inputs {:at-kest-prepaid
                                          {:sparbuch 200M}}})
          §25   (component-by-lane facts :at-kest-sparbuch)
          §27   (component-by-lane facts :at-kest-wertpapier-vermoegen)]
      (is (some? §25) "sparbuch 25 % bucket component is present")
      (is (nil? §27) "no wertpapier component when only Sparbuch interest")
      (is (== 800M (-> §25 :base :amount)))
      (is (== 200M (-> §25 :gross-liability :amount))
          "25 % × 800 = 200 gross KESt")
      (is (== 0M (-> §25 :liability :amount))
          "Endbesteuerung: bank-withheld 200 matches gross-due → 0 net")
      (is (= :sparbuch (get-in §25 [:jurisdiction-specific-codes :kest-bucket]))))))

;; ============================================================================
;; §3. Endbesteuerung delta — bank under-withheld
;; ============================================================================

(deftest endbesteuerung-delta-when-bank-underwithholds
  (testing "Bank prepaid €300 on €412.50-due → liability = €112.50"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends-27-5 1500M}
                                {:inputs {:at-kest-prepaid 300M}})
          §27   (component-by-lane facts :at-kest-wertpapier-vermoegen)]
      (is (== 112.50M (-> §27 :liability :amount))
          "liability = 412.50 gross − 300 prepaid = 112.50")
      (is (== 300M (-> §27 :prepaid :amount))))))

;; ============================================================================
;; §4. Regelbesteuerungsoption — §27a Abs 5 EStG (note 154 §2.2)
;; ============================================================================

(deftest regelbesteuerung-folds-into-pit-base
  (testing ":regelbesteuerung-elected? true → suppress standalone, fold gross into PIT base"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends-27-5 1500M
                                 :sparbuch-interest-25 800M}
                                {:tax-unit {:regelbesteuerung-elected? true}
                                 :inputs   {:at-kest-prepaid
                                            {:wertpapier 412.50M
                                             :sparbuch   200M}}})
          standalone-27-5 (component-by-lane facts :at-kest-wertpapier-vermoegen)
          standalone-25   (component-by-lane facts :at-kest-sparbuch)
          fold            (component-by-lane facts :at-regelbesteuerung-fold)]
      (is (nil? standalone-27-5) "standalone 27.5 % component suppressed")
      (is (nil? standalone-25)   "standalone 25 % component suppressed")
      (is (some? fold) "Regelbesteuerung fold component is emitted")
      (is (== 2300M (-> fold :base :amount))
          "fold base = 1500 dividends + 800 interest = 2300")
      (is (== 612.50M (-> fold :prepaid :amount))
          "prepaid sum = 412.50 + 200 = 612.50 (refundable in PIT)")
      (is (= [2300M] (get-in fold [:jurisdiction-specific-codes :pit-base-additions])))
      (is (= 612.50M (get-in fold [:jurisdiction-specific-codes :pit-credits :at-kest-prepaid])))
      (is (true? (get-in fold [:jurisdiction-specific-codes :pit-credits :refundable?])))
      (is (= :regelbesteuerung (:regime fold))))))

;; ============================================================================
;; §5. DBA-Quellensteuer credit — Apple US dividend (note 154 §2.4)
;; ============================================================================

(deftest dba-quellensteuer-credit-us-treaty-cap
  (testing "Foreign dividend €900 + €135 US WHT @ treaty 15 % → credit €135 (= cap)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends-27-5 900M           ; foreign dividend in 27.5 % bucket
                                 :foreign-dividends-27-5 900M
                                 :foreign-tax-withheld 135M
                                 :foreign-treaty-rate 0.15M}
                                {:inputs {:at-kest-prepaid 247.50M}})
          §27   (component-by-lane facts :at-kest-wertpapier-vermoegen)
          dba   (component-by-lane facts :at-dba-quellensteuer-credit)]
      (is (some? §27))
      (is (== 247.50M (-> §27 :gross-liability :amount))
          "27.5 % × 900 = 247.50 AT KESt")
      (is (some? dba) "DBA credit component emitted")
      (is (= 135M (get-in dba [:jurisdiction-specific-codes :pit-credits-non-refundable
                               :at-dba-quellensteuer]))
          "DBA credit = min(135 actual, 135 cap = 0.15 × 900)"))))

(deftest dba-quellensteuer-credit-capped-when-foreign-wht-exceeds-treaty
  (testing "Foreign WHT €200 on €900 dividend, treaty 15 % cap → credit €135 (= cap)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends-27-5 900M
                                 :foreign-dividends-27-5 900M
                                 :foreign-tax-withheld 200M    ; over the treaty
                                 :foreign-treaty-rate 0.15M}
                                {:inputs {:at-kest-prepaid 247.50M}})
          dba   (component-by-lane facts :at-dba-quellensteuer-credit)]
      (is (= 135M (get-in dba [:jurisdiction-specific-codes :pit-credits-non-refundable
                               :at-dba-quellensteuer]))
          "credit capped at treaty-rate × gross = 0.15 × 900 = 135 (the €65 excess is NOT creditable in AT — must be reclaimed at source)"))))

;; ============================================================================
;; §6. §10 Abs 1 Z 1 KStG domestic dividend — exempt (note 154 §2.5)
;; ============================================================================

(deftest §10-domestic-dividend-exempt-note-154-§2-5
  (testing "Müller-Holding receives €100,000 OMV dividend → §10 Abs 1 Z 1 exempt"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 100000M
                                  :§10-classification :domestic
                                  :ownership-fraction 0.05M}]})
          cmp   (component-by-lane facts :at-§10-exempt-dividend)]
      (is (some? cmp))
      (is (= :§10-default-exempt (:regime cmp)))
      (is (== 0M (-> cmp :liability :amount))
          "default exemption — zero CIT impact at this provider")
      (is (= [100000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))
          "€100,000 flows to :cit-base-deductions — INVERSION removes gross from CIT base")
      (is (= :domestic (get-in cmp [:jurisdiction-specific-codes :§10-classification]))))))

;; ============================================================================
;; §7. §10 Abs 1 Z 5-6 foreign-portfolio exempt + BFG 2024 lost credit (note 154 §2.6)
;; ============================================================================

(deftest §10-foreign-portfolio-exempt-bfg-2024-lost-credit-note-154-§2-6
  (testing "Müller-Holding receives DAX-corp €50,000 dividend, €7,500 DE WHT → exempt + DBA cap=0 (BFG 2024)"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :foreign-tax-withheld 7500M
                                  :foreign-treaty-rate 0.15M
                                  :foreign-corp-etr 0.30M}]})  ; DE > 15% threshold
          cmp   (component-by-lane facts :at-§10-exempt-dividend)]
      (is (some? cmp))
      (is (= :§10-default-exempt (:regime cmp)))
      (is (= [50000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))
          "€50,000 removed from CIT base")
      (is (= 7500M (get-in cmp [:jurisdiction-specific-codes :dba-credit-lost]))
          "BFG 2024: when §10 exempts, the €7,500 DE WHT is LOST at AT (must be reclaimed at source)")
      ;; No `:cit-credits-non-refundable :at-dba-quellensteuer` because the dividend is exempt.
      (is (nil? (get-in cmp [:jurisdiction-specific-codes :cit-credits-non-refundable]))
          "no DBA credit emitted in the default-exempt branch (BFG 2024)"))))

;; ============================================================================
;; §8. §10 Abs 3 Option zur Steuerwirksamkeit — fully taxable
;; ============================================================================

(deftest §10-option-elected-fully-taxable
  (testing "Option zur Steuerwirksamkeit elected → dividend fully taxable; DBA credit fires"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :schachtelbeteiligung
                                  :ownership-fraction 0.15M
                                  :foreign-tax-withheld 7500M
                                  :foreign-treaty-rate 0.15M
                                  :elective-regime #{:at-§10-tax-effective-option}}]})
          cmp   (component-by-lane facts :at-§10-option-taxable-dividend)
          exempt (component-by-lane facts :at-§10-exempt-dividend)]
      (is (nil? exempt) "default-exempt branch NOT fired when Option elected")
      (is (some? cmp))
      (is (= :§10-tax-effective-option (:regime cmp)))
      (is (nil? (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))
          "no deduction — dividend taxed in CIT as ordinary income")
      (is (= 7500M (get-in cmp [:jurisdiction-specific-codes :cit-credits-non-refundable
                                :at-dba-quellensteuer]))
          "DBA credit fires (no exemption, so BFG 2024 cap-zero does NOT apply)"))))

;; ============================================================================
;; §9. Foreign-corp guard (P0-2, note 146 §3.2) — blocks §10 INVERSION on domestic stakes
;; ============================================================================

(deftest §10-foreign-corp-guard-blocks-inversion-for-domestic-stake
  (testing "P0-2 (note 146 §3.2): :held-entity-domestic? true blocks §10 INVERSION on foreign-portfolio/schachtel"
    (let [conn  (fresh)
          ;; Without the guard, the provider would silently route €50k
          ;; to :cit-base-deductions, understating CIT by 23 % × €50k = €11,500
          ;; on a dividend that should actually be taxable (note 146 §3.2).
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :schachtelbeteiligung
                                  :ownership-fraction 0.15M}]}
                               {:tax-unit {:held-entity-domestic? true}})]
      (is (empty? (:components facts))
          "domestic stake → §10 INVERSION blocked for schachtel → no component (dividend stays in CIT base)"))
    ;; Sanity: WITHOUT the flag (or false), the default-exempt INVERSION fires correctly.
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :schachtelbeteiligung
                                  :ownership-fraction 0.15M}]}
                               {:tax-unit {:held-entity-domestic? false}})
          cmp   (component-by-lane facts :at-§10-exempt-dividend)]
      (is (some? cmp) "explicit false → INVERSION fires as before")
      (is (= [50000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))))))

(deftest §10-foreign-corp-guard-does-NOT-block-domestic-classification
  (testing "Foreign-corp guard only affects foreign-portfolio/schachtel; domestic (§10 Abs 1 Z 1) is unaffected"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 100000M
                                  :§10-classification :domestic
                                  :ownership-fraction 0.05M}]}
                               {:tax-unit {:held-entity-domestic? true}})
          cmp   (component-by-lane facts :at-§10-exempt-dividend)]
      (is (some? cmp)
          "domestic classification stays exempt under §10 Abs 1 Z 1 even when held-entity-domestic? true")
      (is (= [100000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))))))

;; ============================================================================
;; §10. §10 Abs 4 switch-over — low-tax jurisdiction (note 154 §2.7)
;; ============================================================================

(deftest §10-abs-4-switch-over-low-tax-jurisdiction-note-154-§2-7
  (testing "Cyprus IP entity ETR 8 % (< 15 % threshold from 2026) → switch-over fires → fully taxable"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :foreign-corp-etr 0.08M
                                  :foreign-tax-withheld 2500M
                                  :foreign-treaty-rate 0.15M}]})
          cmp    (component-by-lane facts :at-§10-abs-4-switchover-taxable)
          exempt (component-by-lane facts :at-§10-exempt-dividend)]
      (is (nil? exempt) "default-exempt branch NOT fired when switch-over applies")
      (is (some? cmp))
      (is (= :§10-abs-4-switch-over (:regime cmp)))
      (is (= 0.15M (get-in cmp [:jurisdiction-specific-codes :low-tax-threshold]))
          "2026 threshold = 15 % (Pillar Two)")
      (is (= 0.08M (get-in cmp [:jurisdiction-specific-codes :foreign-corp-etr])))
      (is (nil? (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))
          "no deduction — dividend fully taxable")
      (is (= 2500M (get-in cmp [:jurisdiction-specific-codes :cit-credits-non-refundable
                                :at-dba-quellensteuer]))
          "DBA credit fires (exemption switched off)"))))

(deftest §10-abs-4-switch-over-via-boolean-flag
  (testing "Consumer attests :low-tax-jurisdiction? true → switch-over fires regardless of ETR"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :low-tax-jurisdiction? true}]})
          cmp   (component-by-lane facts :at-§10-abs-4-switchover-taxable)]
      (is (some? cmp)
          "boolean-flag attestation triggers switch-over without numeric ETR"))))

;; ============================================================================
;; §11. 2026 Pillar Two threshold cliff — 12.5 % → 15 %
;; ============================================================================

(deftest pillar-two-threshold-cliff-pre-2026
  (testing "Pre-2026: threshold is 12.5 %; foreign corp ETR 13 % → NO switch-over (still exempt)"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :foreign-corp-etr 0.13M    ; > 12.5 % pre-2026 threshold
                                  :foreign-tax-withheld 0M}]}
                               {:period p2025})
          exempt (component-by-lane facts :at-§10-exempt-dividend)
          switchover (component-by-lane facts :at-§10-abs-4-switchover-taxable)]
      (is (some? exempt) "ETR 13 % > 12.5 % pre-2026 threshold → exemption stands")
      (is (nil? switchover))
      (is (= [50000M] (get-in exempt [:jurisdiction-specific-codes :cit-base-deductions]))))))

(deftest pillar-two-threshold-cliff-from-2026
  (testing "From 2026: threshold is 15 %; SAME corp ETR 13 % → switch-over fires (Mindestbesteuerungsgesetz)"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :foreign-corp-etr 0.13M    ; < 15 % from-2026 threshold
                                  :foreign-tax-withheld 0M}]}
                               {:period p2026})
          exempt (component-by-lane facts :at-§10-exempt-dividend)
          switchover (component-by-lane facts :at-§10-abs-4-switchover-taxable)]
      (is (nil? exempt))
      (is (some? switchover) "ETR 13 % < 15 % from-2026 threshold → switch-over fires (Pillar Two)")
      (is (= 0.15M (get-in switchover [:jurisdiction-specific-codes :low-tax-threshold]))))))

;; ============================================================================
;; §12. §27 Abs 8 within-year netting — flagged as TODO (orchestrator pattern)
;; ============================================================================

(deftest §27-abs-8-cross-event-type-netting-is-orchestrator-concern
  (testing "Within-year netting between CGT losses and II income is documented as orchestrator scope (note 154 §1.6 / §5.3)"
    ;; The provider docstring explicitly notes that v1 does NOT implement
    ;; the cross-provider netting — it is deferred to a future
    ;; `kontor.l10n-at.kest-orchestrator` helper that composes the AT
    ;; CGT + investment-income providers. This test asserts that the
    ;; provider docstring carries the orchestrator note.
    (let [ns-doc (-> 'kontor.l10n-at.investment-income-provider
                     find-ns meta :doc str)]
      (is (re-find #"(?s)§27 Abs 8|within-year|orchestrator|kest-orchestrator" ns-doc)
          "ns docstring flags the deferred §27 Abs 8 orchestrator scope")
      ;; Concrete behaviour assertion: provider does NOT read CGT-loss
      ;; carryforward (the orchestrator would inject it).
      (let [conn  (fresh)
            ;; Even with a (consumer-supplied, ignored) CGT loss carry,
            ;; the provider taxes dividends in full.
            facts (run-individual conn
                                  {:dividends-27-5 1500M}
                                  {:inputs {:capital-loss-carryforward
                                            {:at-kest 1000M}}})
            §27   (component-by-lane facts :at-kest-wertpapier-vermoegen)]
        (is (== 412.50M (-> §27 :gross-liability :amount))
            "v1 provider DOES NOT net CGT losses against II — full 27.5 % × 1500 on dividends")))))

;; ============================================================================
;; §13. Provider plumbing
;; ============================================================================

(deftest components-use-investment-income-tax-kind
  (testing "all emitted components carry :kind :investment-income-tax"
    (let [conn  (fresh)
          ind   (run-individual conn
                                {:dividends-27-5 1500M
                                 :sparbuch-interest-25 800M
                                 :foreign-dividends-27-5 500M
                                 :foreign-tax-withheld 75M
                                 :foreign-treaty-rate 0.15M})
          corp  (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 100000M
                                  :§10-classification :domestic
                                  :ownership-fraction 0.05M}
                                 {:gross 50000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.03M
                                  :foreign-corp-etr 0.30M}]})]
      (is (seq (:components ind)))
      (is (seq (:components corp)))
      (is (every? #(= :investment-income-tax (:kind %)) (:components ind))
          "individual: every component carries the period-tax kind")
      (is (every? #(= :investment-income-tax (:kind %)) (:components corp))
          "corporate: every component carries the period-tax kind"))))

(deftest provider-shape
  (let [ind  (inv/at-kest-investment-income-provider {})
        corp (inv/at-corporate-investment-income-provider {})]
    (is (= :at-investment-income-kest      (ptp/provider-id ind)))
    (is (= :at-investment-income-corporate (ptp/provider-id corp)))
    (is (= :EUR (:commodity ind)))
    (is (= :EUR (:commodity corp)))
    (is (= :at-finanzamt (:authority ind)))
    (is (= :at-finanzamt (:authority corp)))))

(deftest empty-bases-returns-zero-components
  (testing "no investment income at all → no components"
    (let [conn  (fresh)
          ind   (run-individual conn {})
          corp  (run-corporate  conn {})]
      (is (empty? (:components ind)))
      (is (empty? (:components corp))))))

(deftest ordinary-classification-emits-no-component
  (testing ":ordinary classification → no component (dividend taxed via ordinary CIT path)"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :ordinary}]})]
      (is (empty? (:components facts))
          ":ordinary → no §10 path → no provider component (GL booking already in CIT base)"))))

(deftest §10-schachtel-qualification-downgrade
  (testing "schachtel labelled but ownership < 10 % → §10 INVERSION blocked"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :schachtelbeteiligung
                                  :ownership-fraction 0.03M}]})]
      (is (empty? (:components facts))
          ":schachtel + 3 % ownership fails §10 Abs 2 ≥10 % gate → no component")))
  (testing "schachtel labelled but held < 365 days → §10 INVERSION blocked"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 50000M
                                  :§10-classification :schachtelbeteiligung
                                  :ownership-fraction 0.20M
                                  :held-since #inst "2026-06-01"}]}
                               {:as-of #inst "2026-12-15"
                                :period {:from #inst "2026-01-01"
                                         :to   #inst "2026-12-31"}})]
      (is (empty? (:components facts))
          ":schachtel + ~197-day hold fails §10 Abs 2 ≥365-day gate → no component"))))

(deftest §10-abs-4-boundary-triggers-switch-over
  (testing "ETR exactly at threshold (15 % from 2026) triggers switch-over (\"nicht mehr als\" = ≤)"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corporate-dividend-events
                                [{:gross 100000M
                                  :§10-classification :foreign-portfolio
                                  :ownership-fraction 0.05M
                                  :foreign-corp-etr 0.15M}]}
                               {:as-of #inst "2026-06-01"
                                :period {:from #inst "2026-01-01"
                                         :to   #inst "2026-12-31"}})
          cmp   (component-by-lane facts :at-§10-abs-4-switchover-taxable)]
      (is (some? cmp)
          "ETR = 15 % at the 2026 threshold triggers switch-over (boundary is inclusive)"))))
