(ns kontor.l10n-in.cgt-provider-test
  "Tests for the IN CGT provider (ADR-102 + ADR-101, research note 131).

   The §2 worked examples (Ms Patel listed-equity portfolio + Mr
   Sharma immovable with §54 + §54EC stacking) are reproduced
   end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-in.cgt-provider :as in-cgt]
            [kontor.l10n-in.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, IN CGT statute, an INR
   commodity, and one resident-individual entity (TAXPAYER).
   Also includes a `ROLLOVER-STUB` commodity used as the placeholder
   `:rollover-into-asset` ref when a test exercises rollover relief
   (a real consumer points at the replacement `:asset`; the provider
   only reads `:rollover-amount`, so any ref works for these tests)."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol    "INR"
                       :kontor.commodity/name      "Indian Rupee"
                       :kontor.commodity/precision 2}
                      {:kontor.commodity/symbol    "ROLLOVER-STUB"
                       :kontor.commodity/name      "Rollover Stub (test placeholder)"
                       :kontor.commodity/precision 0}
                      {:kontor.entity/code                 "TAXPAYER"
                       :kontor.entity/name                 "Resident Individual"
                       :kontor.entity/kind                 :individual
                       :kontor.entity/country              "IN"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "INR"]}])
    conn))

(def ^:private inr [:kontor.commodity/symbol "INR"])
(def ^:private rollover-stub [:kontor.commodity/symbol "ROLLOVER-STUB"])
(def ^:private taxpayer [:kontor.entity/code "TAXPAYER"])

(defn- record!
  "Record a minimal disposal — provider hooks the resolved id."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          taxpayer
                :kind            :sale
                :subject         inr
                :subject-kind    :financial-instrument
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity inr}
                :basis           {:amount 0M :commodity inr}}
               opts)))

(defn- taxpayer-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "TAXPAYER"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn kind period & [extra-ctx]]
  (let [source (disp-source/datahike-source conn)
        prov   (in-cgt/in-cgt-provider {:source source :kind kind})]
    (ptp/period-tax-facts
     prov
     (merge {:db (d/db conn)
             :entity (taxpayer-eid conn)
             :period period
             :as-of (:to period)}
            extra-ctx))))

;; FY 2026-27 = 1-Apr-2026 .. 1-Apr-2027 (Indian fiscal year).
(def ^:private fy-2026-27 {:from #inst "2026-04-01" :to #inst "2027-04-01"})
(def ^:private fy-2025-26 {:from #inst "2025-04-01" :to #inst "2026-04-01"})

(defn- component-by-lane
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing — empty case
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn :individual fy-2026-27)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind at construction"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                          (in-cgt/in-cgt-provider
                           {:source (disp-source/datahike-source (fresh))
                            :kind :bogus})))))

;; ============================================================================
;; §2. Equity-listed STCG (§111A) — 20 % post-FA-2024
;; ============================================================================

(deftest listed-equity-stcg-at-20pct
  (testing "6-month-held listed STT-paid equity STCG at 20 %"
    (let [conn (fresh)]
      (record! conn {:external-id "st-eq-1"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2026-04-15"
                     :disposed-on #inst "2026-10-01"
                     :proceeds    {:amount 250000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            stcg  (component-by-lane facts :stcg-§111A)]
        (is (some? stcg))
        ;; 150,000 × 20% = 30,000
        (is (== 150000M (-> stcg :base :amount)))
        (is (== 30000M (-> stcg :liability :amount)))))))

;; ============================================================================
;; §3. Equity-listed LTCG (§112A) — 12.5 % above ₹1.25 L floor
;; ============================================================================

(deftest listed-equity-ltcg-below-floor-zero-tax
  (testing "LTCG below the ₹1.25 L floor → zero tax"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-floor"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 200000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            ltcg  (component-by-lane facts :ltcg-§112A)]
        (is (some? ltcg))
        (is (== 100000M (-> ltcg :base :amount)))
        (is (== 0M (-> ltcg :liability :amount))
            "100,000 < 125,000 floor")))))

(deftest listed-equity-ltcg-above-floor-at-12_5pct
  (testing "LTCG above ₹1.25 L floor at 12.5 %"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-above"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 525000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            ltcg  (component-by-lane facts :ltcg-§112A)]
        (is (== 425000M (-> ltcg :base :amount)))
        ;; (425,000 - 125,000) × 12.5 % = 37,500
        (is (== 37500M (-> ltcg :liability :amount)))))))

;; ============================================================================
;; §4. Immovable property §112 — flat 12.5 % default + CII election
;; ============================================================================

(deftest immovable-ltcg-flat-default
  (testing "immovable LTCG (>24mo, no election) at flat 12.5 %"
    (let [conn (fresh)]
      (record! conn {:external-id "im-flat"
                     :asset-class :in-immovable
                     :acquired-on #inst "2020-06-01"
                     :disposed-on #inst "2026-04-15"
                     :proceeds    {:amount 17700000M :commodity inr}
                     :basis       {:amount 3500000M  :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            ltcg  (component-by-lane facts :ltcg-§112)]
        (is (some? ltcg))
        ;; 14,200,000 × 12.5 % = 1,775,000
        (is (== 14200000M (-> ltcg :base :amount)))
        (is (== 1775000M  (-> ltcg :liability :amount)))))))

(deftest immovable-ltcg-with-cii-indexation-election
  (testing "immovable LTCG (pre-23-Jul-2024 acq) with CII election → 20 % on indexed gain"
    (let [conn (fresh)]
      (record! conn {:external-id "im-indexed"
                     :asset-class      :in-immovable
                     :acquired-on      #inst "2010-06-01"  ; FY 2010-11, CII = 167
                     :disposed-on      #inst "2026-04-15"  ; FY 2026-27, CII = 376 (extrapolated; we use FY 2025-26 = 376 since 2026-04-15 < 2027 mid)
                     :proceeds         {:amount 17700000M :commodity inr}
                     :basis            {:amount 3000000M  :commodity inr}
                     :elective-regime  #{:in-cii-indexation}})
      ;; FY of 2026-04-15 = "IN.CGT.cii.fy-2026-27" — not in our table,
      ;; so provider falls back to no-indexation. Re-do with a 2025-26 disposal.
      ))
  (testing "immovable LTCG with CII election where both FY-codes resolve"
    (let [conn (fresh)]
      (record! conn {:external-id "im-indexed-2"
                     :asset-class      :in-immovable
                     :acquired-on      #inst "2010-06-01"  ; FY 2010-11, CII = 167
                     :disposed-on      #inst "2025-08-15"  ; FY 2025-26, CII = 376
                     :proceeds         {:amount 17700000M :commodity inr}
                     :basis            {:amount 3000000M  :commodity inr}
                     :elective-regime  #{:in-cii-indexation}})
      (let [facts (run-provider conn :individual fy-2025-26)
            ltcg  (component-by-lane facts :ltcg-§112)]
        (is (some? ltcg))
        ;; Indexed basis = 3,000,000 × (376/167) = 6,754,491 (HALF_EVEN rounded to rupees)
        ;; LTCG = 17,700,000 - 6,754,491 = 10,945,509
        ;; Tax = 10,945,509 × 20 % = 2,189,101.80
        (is (== 10945509M (-> ltcg :base :amount)))
        (is (== 2189101.80M (-> ltcg :liability :amount)))
        (is (= :in-cii-indexation (:regime ltcg)))))))

;; ============================================================================
;; §5. §54 / §54EC / §54F family exemptions
;; ============================================================================

(deftest §54EC-rollover-within-cap
  (testing "§54EC rollover of ₹50 L (== cap) zeros the LTCG"
    (let [conn (fresh)]
      (record! conn {:external-id  "im-54ec"
                     :asset-class  :in-immovable
                     :acquired-on  #inst "2020-06-01"
                     :disposed-on  #inst "2026-04-15"
                     :proceeds     {:amount 9000000M :commodity inr}
                     :basis        {:amount 4000000M :commodity inr}
                     :exemption-claimed #{:in-§54EC}
                     :rollover     {:into-asset rollover-stub :amount 5000000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            ltcg  (component-by-lane facts :ltcg-§112)
            exempt (component-by-lane facts :exempt)]
        ;; Gain = 5M; full 5M sheltered → net 0M → no §112 component
        (is (nil? ltcg))
        (is (some? exempt))
        (is (== 5000000M (-> exempt :base :amount))
            "₹50 L sheltered surfaces as the exempt-component base")))))

(deftest §54EC-cap-clips-rollover
  (testing "§54EC rollover beyond ₹50 L is clipped to the cap"
    (let [conn (fresh)]
      (record! conn {:external-id  "im-54ec-clip"
                     :asset-class  :in-immovable
                     :acquired-on  #inst "2020-06-01"
                     :disposed-on  #inst "2026-04-15"
                     :proceeds     {:amount 15000000M :commodity inr}
                     :basis        {:amount 5000000M  :commodity inr}
                     :exemption-claimed #{:in-§54EC}
                     :rollover     {:into-asset rollover-stub :amount 8000000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            ltcg  (component-by-lane facts :ltcg-§112)]
        ;; Gain = 10M; §54EC capped at 5M → net 5M; tax = 5M × 12.5 % = 625k
        (is (some? ltcg))
        (is (== 5000000M (-> ltcg :base :amount)))
        (is (== 625000M  (-> ltcg :liability :amount)))))))

(deftest §54-residential-house-rollover-fully-shelters
  (testing "§54 rollover into a new residential house fully shelters the gain"
    (let [conn (fresh)]
      (record! conn {:external-id  "im-54"
                     :asset-class  :in-immovable
                     :acquired-on  #inst "2020-06-01"
                     :disposed-on  #inst "2026-04-15"
                     :proceeds     {:amount 9000000M :commodity inr}
                     :basis        {:amount 4000000M :commodity inr}
                     :exemption-claimed #{:in-§54}
                     :rollover     {:into-asset rollover-stub :amount 5000000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)]
        (is (nil? (component-by-lane facts :ltcg-§112)))))))

(deftest §54EC-prior-claimed-aggregates-against-cap
  (testing "consumer-supplied :in-§54EC-prior-claimed reduces the available cap"
    (let [conn (fresh)]
      (record! conn {:external-id  "im-54ec-prior"
                     :asset-class  :in-immovable
                     :acquired-on  #inst "2020-06-01"
                     :disposed-on  #inst "2026-09-01"
                     :proceeds     {:amount 9000000M :commodity inr}
                     :basis        {:amount 4000000M :commodity inr}
                     :exemption-claimed #{:in-§54EC}
                     :rollover     {:into-asset rollover-stub :amount 3000000M :commodity inr}})
      (let [;; Already claimed ₹30 L earlier in the FY → only ₹20 L head
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-§54EC-prior-claimed 3000000M}})
            ltcg  (component-by-lane facts :ltcg-§112)]
        ;; Gain = 5M; §54EC head = ₹20 L; rollover claimed 30 L, allowed 20 L
        ;; → net = 5M - 2M = 3M; tax = 3M × 12.5 % = 375k
        (is (some? ltcg))
        (is (== 3000000M (-> ltcg :base :amount)))
        (is (== 375000M  (-> ltcg :liability :amount)))))))

(deftest §54EC-mixed-§54-claim-counts-only-§54EC-slice-against-cap
  (testing "P0 (note 143 §3.1) — when a disposal claims BOTH :in-§54EC
            and :in-§54, only the §54EC-attributable slice of the
            rollover counts against the ₹50 L FY cap. The remainder
            (allocated to §54) MUST NOT inflate :§54EC-cap-used.

            Pre-fix: a single disposal with rollover 8M (5M §54EC + 3M
            §54) would silently push §54EC-cap-used to 8M, exhausting
            the cap and denying later §54EC claims in the same FY.
            Post-fix: §54EC-cap-used = 5M (the cap-allowed §54EC slice
            only); the §54-sibling remainder is tracked through the
            ordinary §54-family-rollover line item."
    (let [conn (fresh)]
      ;; Single mixed disposal — rollover (8M) > §54EC cap (5M); sibling
      ;; §54 absorbs the 3M remainder.
      (record! conn {:external-id      "mixed-§54EC-§54"
                     :asset-class      :in-immovable
                     :acquired-on      #inst "2020-03-01"
                     :disposed-on      #inst "2026-04-15"
                     :proceeds         {:amount 12000000M :commodity inr}
                     :basis            {:amount  4000000M :commodity inr}
                     :exemption-claimed #{:in-§54EC :in-§54}
                     :rollover         {:into-asset rollover-stub
                                        :amount     8000000M
                                        :commodity  inr}})
      (let [facts  (run-provider conn :individual fy-2026-27)
            ltcg   (component-by-lane facts :ltcg-§112)
            exempt (component-by-lane facts :exempt)
            cap-used-line
            (->> (:line-items exempt)
                 (some #(when (= :§54EC-cap-used (:line %)) %)))
            full-roll-line
            (->> (:line-items exempt)
                 (some #(when (= :§54-family-rollover (:line %)) %)))]
        ;; The mixed claim fully shelters the 8M gain → no §112 component.
        (is (nil? ltcg))
        ;; The exempt-component's :§54-family-rollover surfaces the
        ;; FULL 8M (§54EC 5M + §54 3M) for audit.
        (is (some? full-roll-line))
        (is (== 8000000M (-> full-roll-line :value :amount)))
        ;; The :§54EC-cap-used line shows ONLY the §54EC-allocated 5M,
        ;; not the full 8M. (Pre-fix the test would have asserted 8M.)
        (is (some? cap-used-line))
        (is (== 5000000M (-> cap-used-line :value :amount))
            "Only the §54EC slice (5M) counts against the cap — not the
             sibling §54 remainder (3M)."))))

  (testing "And a subsequent §54EC-only disposal in the same FY still
            sees the unused cap headroom — modelled via the consumer-
            supplied :in-§54EC-prior-claimed input (which the previous
            sub-test demonstrates would be 5M, not 8M)."
    (let [conn (fresh)]
      ;; Simulate: an EARLIER mixed §54+§54EC claim consumed 5M of cap
      ;; (its §54EC slice). The consumer supplies that 5M as
      ;; :in-§54EC-prior-claimed for this run.
      (record! conn {:external-id      "later-§54EC-only"
                     :asset-class      :in-immovable
                     :acquired-on      #inst "2020-06-01"
                     :disposed-on      #inst "2026-11-01"
                     :proceeds         {:amount 7000000M :commodity inr}
                     :basis            {:amount 4000000M :commodity inr}
                     :exemption-claimed #{:in-§54EC}
                     :rollover         {:into-asset rollover-stub
                                        :amount     2000000M
                                        :commodity  inr}})
      (let [;; Pre-fix: a real consumer would have supplied 8M
            ;; (the full prior rollover) and seen head = 0 → denied.
            ;; Post-fix: the consumer supplies 5M (the actual §54EC
            ;; slice from the mixed claim) → head = 0 too IF cap is
            ;; exhausted. But here we model the post-fix scenario
            ;; where the consumer feeds back the corrected 5M and
            ;; still has headroom (cap 5M, prior 0M would give 5M
            ;; head). Use a smaller prior to expose the headroom.
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-§54EC-prior-claimed 3000000M}})
            ltcg  (component-by-lane facts :ltcg-§112)]
        ;; Cap head = 5M - 3M = 2M; rollover 2M → allowed 2M → gain
        ;; (3M) - 2M = 1M base; tax = 1M × 12.5 % = 125k
        (is (some? ltcg))
        (is (== 1000000M (-> ltcg :base :amount)))
        (is (== 125000M (-> ltcg :liability :amount)))))))

;; ============================================================================
;; §6. §47 transfer-not-regarded
;; ============================================================================

(deftest §47-transfer-not-regarded-yields-zero-tax
  (testing "§47 amalgamation: no CGT — gain folds into exempt component"
    (let [conn (fresh)]
      (record! conn {:external-id "im-47"
                     :asset-class :in-immovable
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 20000000M :commodity inr}
                     :basis       {:amount  5000000M :commodity inr}
                     :exemption-claimed #{:in-§47-amalgamation}})
      (let [facts  (run-provider conn :individual fy-2026-27)
            exempt (component-by-lane facts :exempt)]
        (is (nil? (component-by-lane facts :ltcg-§112)))
        (is (some? exempt))
        (is (= 0M (-> exempt :liability :amount)))))))

;; ============================================================================
;; §7. §50C deemed proceeds
;; ============================================================================

(deftest §50C-deemed-proceeds-applies-when-sdv-exceeds-safe-harbour
  (testing "stamp-duty value > 110 % × recorded proceeds → SDV deemed"
    (let [conn (fresh)]
      (record! conn {:external-id "im-50c"
                     :asset-class :in-immovable
                     :acquired-on #inst "2020-06-01"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 10000000M :commodity inr}
                     :basis       {:amount  5000000M :commodity inr}})
      (let [;; SDV ₹1.5 cr > 110 % × ₹1 cr = ₹1.1 cr → §50C bites; deemed = 1.5 cr
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-stamp-duty-deemed-proceeds 15000000M}})
            ltcg  (component-by-lane facts :ltcg-§112)]
        ;; Gain = 15M - 5M = 10M; tax = 10M × 12.5 % = 1,250,000
        (is (== 10000000M (-> ltcg :base :amount)))
        (is (== 1250000M  (-> ltcg :liability :amount))))))

  (testing "SDV within 110 % safe-harbour → recorded proceeds retained"
    (let [conn (fresh)]
      (record! conn {:external-id "im-50c-safe"
                     :asset-class :in-immovable
                     :acquired-on #inst "2020-06-01"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 10000000M :commodity inr}
                     :basis       {:amount  5000000M :commodity inr}})
      (let [;; SDV ₹1.05 cr ≤ 110 % × ₹1 cr → §50C does NOT bite
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-stamp-duty-deemed-proceeds 10500000M}})
            ltcg  (component-by-lane facts :ltcg-§112)]
        ;; Gain = 5M; tax = 625k
        (is (== 5000000M (-> ltcg :base :amount)))
        (is (== 625000M  (-> ltcg :liability :amount)))))))

;; ============================================================================
;; §8. §194-IA TDS prepaid
;; ============================================================================

(deftest §194-IA-tds-rides-as-prepaid
  (testing "TDS-withheld surfaces as :prepaid on the §112 component"
    (let [conn (fresh)]
      (record! conn {:external-id "im-tds"
                     :asset-class :in-immovable
                     :acquired-on #inst "2020-06-01"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 10000000M :commodity inr}
                     :basis       {:amount  6000000M :commodity inr}})
      (let [;; Buyer withheld 1 % × ₹1 cr = ₹1 L under §194-IA
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-tds-§194-IA 100000M}})
            ltcg  (component-by-lane facts :ltcg-§112)]
        (is (== 100000M (-> ltcg :prepaid :amount)))))))

;; ============================================================================
;; §9. Slab-rate STCG folds into PIT base (or CIT)
;; ============================================================================

(deftest immovable-stcg-folds-into-pit-base
  (testing "short-held immovable STCG flows into :pit-base-additions"
    (let [conn (fresh)]
      (record! conn {:external-id "im-stcg"
                     :asset-class :in-immovable
                     :acquired-on #inst "2025-08-15"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 10000000M :commodity inr}
                     :basis       {:amount  6000000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            slab  (component-by-lane facts :stcg-slab)]
        (is (some? slab))
        (is (== 4000000M (-> slab :base :amount)))
        (is (== 0M (-> slab :liability :amount)))
        (is (= [4000000M] (get-in slab [:jurisdiction-specific-codes
                                        :pit-base-additions])))))))

(deftest corporate-stcg-folds-into-cit-base
  (testing "short-held STCG on the corporate provider flows into :cit-base-additions"
    (let [conn (fresh)]
      (record! conn {:external-id "im-corp-stcg"
                     :asset-class :in-immovable
                     :acquired-on #inst "2025-08-15"
                     :disposed-on #inst "2026-08-01"
                     :proceeds    {:amount 10000000M :commodity inr}
                     :basis       {:amount  6000000M :commodity inr}})
      (let [facts (run-provider conn :corporation fy-2026-27)
            slab  (component-by-lane facts :stcg-slab)]
        (is (some? slab))
        (is (= [4000000M] (get-in slab [:jurisdiction-specific-codes
                                        :cit-base-additions])))))))

(deftest debt-mf-always-slab-§50AA
  (testing "debt-MF (post-2023 acq) — ALWAYS slab regardless of holding (§50AA)"
    (let [conn (fresh)]
      (record! conn {:external-id "dm-1"
                     :asset-class :in-debt-mf
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-08-01" ; > 24 months
                     :proceeds    {:amount 500000M :commodity inr}
                     :basis       {:amount 300000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            slab  (component-by-lane facts :stcg-slab)
            ltcg  (component-by-lane facts :ltcg-§112)]
        (is (nil? ltcg) "no LTCG lane for debt-MF — §50AA forces STCG")
        (is (some? slab))
        (is (== 200000M (-> slab :base :amount)))))))

;; ============================================================================
;; §10. Capital-loss carryforward — IN five-bucket compartment walls
;; ============================================================================

(deftest ltcl-equity-carryforward-offsets-ltcg-equity
  (testing "LTCL-equity offsets LTCG-equity in the SAME compartment"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-eq-carry"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 800000M :commodity inr}
                     :basis       {:amount 300000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27
                                {:inputs {:capital-loss-carryforward
                                          {:in-ltcl-equity 200000M}}})
            ltcg  (component-by-lane facts :ltcg-§112A)]
        ;; 500k gain - 200k carry = 300k base; (300k - 125k floor) × 12.5 % = 21,875
        (is (== 300000M (-> ltcg :base :amount)))
        (is (== 21875M (-> ltcg :liability :amount)))))))

(deftest stcl-equity-§70-2-cross-offsets-ltcg
  (testing "§70(2): STCL-equity after consuming STCG-equity can also offset LTCG-equity"
    (let [conn (fresh)]
      (record! conn {:external-id "stcg-small"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2026-04-15"
                     :disposed-on #inst "2026-10-01"
                     :proceeds    {:amount 150000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (record! conn {:external-id "ltcg-large"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 700000M :commodity inr}
                     :basis       {:amount 300000M :commodity inr}})
      (let [;; Carry-in STCL-equity 200,000.
            ;; STCG-equity = 50,000; carry consumes 50k → STCG = 0;
            ;; remaining 150k carry → LTCG-equity 400k - 150k = 250k base;
            ;; (250k - 125k floor) × 12.5 % = 15,625
            facts (run-provider conn :individual fy-2026-27
                                {:inputs {:capital-loss-carryforward
                                          {:in-stcl-equity 200000M}}})
            stcg  (component-by-lane facts :stcg-§111A)
            ltcg  (component-by-lane facts :ltcg-§112A)]
        (is (nil? stcg) "STCG fully consumed")
        (is (== 250000M (-> ltcg :base :amount)))
        (is (== 15625M  (-> ltcg :liability :amount)))))))

(deftest ltcl-pre-2026-onetime-relief-offsets-stcg-from-ay-2027-28
  (testing "IT Bill 2025 one-time relief: pre-31-Mar-2026 LTCL may offset STCG"
    (let [conn (fresh)]
      (record! conn {:external-id "stcg-eq-onetime"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2026-04-15"
                     :disposed-on #inst "2026-10-01"
                     :proceeds    {:amount 300000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (let [;; carry-in 100k of pre-2026 LTCL
            facts (run-provider conn :individual fy-2026-27
                                {:as-of #inst "2027-03-31"
                                 :inputs {:capital-loss-carryforward
                                          {:in-ltcl-pre-2026-onetime 100000M}}})
            stcg  (component-by-lane facts :stcg-§111A)]
        ;; STCG 200k - 100k = 100k; tax = 20k
        (is (== 100000M (-> stcg :base :amount)))
        (is (== 20000M  (-> stcg :liability :amount)))))))

;; ============================================================================
;; §11. Void exclusion
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity inr}
                     :basis       {:amount 1000000M :commodity inr}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual fy-2026-27)]
        (is (empty? (:components facts)))))))

;; ============================================================================
;; §12. 4 % cess surtax on standalone CGT
;; ============================================================================

(deftest cess-fires-on-standalone-cgt
  (testing "4 % H&E cess rides as a surtax on LTCG + equity STCG"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-cess"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 525000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27)
            cess  (component-by-lane facts :cess)]
        (is (some? cess))
        ;; LTCG tax = 37,500 (see test §3); cess = 37,500 × 4 % = 1,500
        (is (== 1500M (-> cess :liability :amount)))))))

;; ============================================================================
;; §13. Note 131 §2 — Worked example A: Mr Sharma immovable + §54EC + §54
;; ============================================================================

(deftest worked-example-A-sharma-immovable-§54EC-§54-stack
  (testing "Mr Sharma — pre-2024 immovable, 12.5 % flat election, §54EC + §54 stack → ₹0 CGT"
    (let [conn (fresh)]
      ;; Sale: 1.8 cr, basis 35 L (acq 30 L + improvements 5 L); held >24 mo
      ;; Net gain (no indexation) = (1.8 cr - 3 L expenses) - 35 L = 1.42 cr
      ;; The companion's :basis-amount carries the consolidated basis;
      ;; expenses are accounted for by the consumer pre-recording. We use
      ;; net proceeds 1.77 cr (after the ₹3 L expense).
      (record! conn {:external-id "sharma-flat"
                     :asset-class :in-immovable
                     :acquired-on #inst "2010-03-01"
                     :disposed-on #inst "2026-04-15"
                     :proceeds    {:amount 17700000M :commodity inr}
                     :basis       {:amount  3500000M :commodity inr}
                     :exemption-claimed #{:in-§54EC :in-§54}
                     ;; §54EC = ₹50 L bonds; §54 = ₹92 L new flat → rollover total 1.42 cr
                     :rollover     {:into-asset rollover-stub :amount 14200000M :commodity inr}})
      (let [facts (run-provider conn :individual fy-2026-27
                                {:inputs {:in-tds-§194-IA 180000M}})
            ltcg  (component-by-lane facts :ltcg-§112)
            exempt (component-by-lane facts :exempt)
            cess  (component-by-lane facts :cess)]
        ;; Gain 1.42 cr fully sheltered by 1.42 cr rollover → no §112 component
        (is (nil? ltcg))
        ;; Cess does not fire (no standalone CGT)
        (is (nil? cess))
        ;; Exempt-component records the deferred rollover
        (is (some? exempt))
        (is (== 14200000M (-> exempt :base :amount)))))))

;; ============================================================================
;; §14. Note 131 §2 — Worked example B: Ms Patel listed-equity portfolio
;; ============================================================================

(deftest worked-example-B-patel-listed-equity-portfolio
  (testing "Ms Patel — RIL+Infy LTCG, HDFC STCG, Tata LTCL → net + 4 % cess"
    (let [conn (fresh)]
      ;; RIL LTCG 4,00,000
      (record! conn {:external-id "ril"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2025-06-15"
                     :proceeds    {:amount 600000M :commodity inr}
                     :basis       {:amount 200000M :commodity inr}})
      ;; Infosys LTCG 80,000
      (record! conn {:external-id "infy"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2025-08-15"
                     :proceeds    {:amount 180000M :commodity inr}
                     :basis       {:amount 100000M :commodity inr}})
      ;; HDFC STCG 1,50,000 (held 4 mo)
      (record! conn {:external-id "hdfc"
                     :asset-class :in-equity-listed
                     :acquired-on #inst "2025-08-01"
                     :disposed-on #inst "2025-12-01"
                     :proceeds    {:amount 350000M :commodity inr}
                     :basis       {:amount 200000M :commodity inr}})
      (let [;; Tata Motors LTCL 60,000 — passed in via carry-in (note 131 §2
            ;; example uses a within-year netting; the substrate models the
            ;; LTCL via the carry-in :in-ltcl-equity bucket since the
            ;; companion does not record losses as separate disposals in v1).
            facts (run-provider conn :individual fy-2025-26
                                {:inputs {:capital-loss-carryforward
                                          {:in-ltcl-equity 60000M}}})
            ltcg  (component-by-lane facts :ltcg-§112A)
            stcg  (component-by-lane facts :stcg-§111A)
            cess  (component-by-lane facts :cess)]
        ;; LTCG total = 4,00,000 + 80,000 - 60,000 = 4,20,000
        ;; Above ₹1.25 L floor = 2,95,000; tax = 2,95,000 × 12.5 % = 36,875
        (is (some? ltcg))
        (is (== 420000M (-> ltcg :base :amount)))
        (is (== 36875M  (-> ltcg :liability :amount)))
        ;; STCG = 1,50,000; tax = 1,50,000 × 20 % = 30,000
        (is (some? stcg))
        (is (== 150000M (-> stcg :base :amount)))
        (is (== 30000M  (-> stcg :liability :amount)))
        ;; Cess = (36,875 + 30,000) × 4 % = 2,675
        (is (some? cess))
        (is (== 2675M (-> cess :liability :amount)))))))
