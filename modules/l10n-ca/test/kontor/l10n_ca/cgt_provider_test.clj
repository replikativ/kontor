(ns kontor.l10n-ca.cgt-provider-test
  "Tests for the CA CGT provider (ADR-102 + ADR-101, research note 127).

   Covers the headline CA CGT mechanics:
     §1  Plumbing — DisposalSource wired; empty case; kind validation
     §2  50% inclusion — the base case (taxable = 0.5 × gain)
     §3  LCGE — QSBC shelter consumption + per-call cap; note 127 §2 Example A
     §4  Principal residence — flag-driven full exemption
     §5  ABIL — diverts to PIT/CIT base deduction at 1/2 rate
     §6  Section 85 rollover — gain excluded from the pool
     §7  CCA recapture — depreciable split (ordinary vs capital)
     §8  Corp net folds into CIT base additions (no LCGE)
     §9  Individual folds into PIT base additions
     §10 Voided disposals excluded
     §11 Superficial loss flag — denied"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-ca.cgt-provider :as ca-cgt]
            [kontor.l10n-ca.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, CA CGT statute, a CAD
   commodity, and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:commodity/symbol "CAD" :commodity/name "Canadian Dollar"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "CA"
                       :entity/functional-commodity [:commodity/symbol "CAD"]}])
    conn))

(def ^:private cad [:commodity/symbol "CAD"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         cad
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity cad}
                :basis           {:amount 0M :commodity cad}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (case kind
                   :individual  (ca-cgt/ca-individual-cgt-provider  {:source source})
                   :corporation (ca-cgt/ca-corporate-cgt-provider   {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- only-component
  "Return the (single) component the CA CGT provider always produces."
  [facts]
  (first (:components facts)))

(defn- summary
  "Pull the `:cgt-summary` map out of the component."
  [facts]
  (get-in (only-component facts) [:jurisdiction-specific-codes :cgt-summary]))

;; ============================================================================
;; §1. Plumbing
;; ============================================================================

(deftest empty-source-returns-zeroed-component
  (testing "an entity with no disposals returns one component with all-zero summary"
    (let [conn  (fresh)
          facts (run-provider conn :individual p2026)
          comp  (only-component facts)]
      (is (= 1 (count (:components facts))) "provider always returns ONE component")
      (is (== 0M (-> comp :base :amount)))
      (is (nil? (:schedule comp)) "no own schedule — flows into PIT/CIT")
      (let [s (summary facts)]
        (is (== 0M (:gross-capital s)))
        (is (== 0M (:taxable-capital s)))
        (is (== 0M (:ordinary-recapture s)))
        (is (== 0M (:abil-deduction s)))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn   (fresh)
          source (disp-source/datahike-source conn)
          bad    (ca-cgt/->CACapitalGainsTaxProvider :bogus source :ca-cra :CAD "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :entity (holdco-eid conn) :period p2026}))))))

;; ============================================================================
;; §2. 50% inclusion — the base case
;; ============================================================================

(deftest fifty-percent-inclusion-base-case
  (testing "$10,000 capital gain → $5,000 taxable capital gain (50% inclusion)"
    (let [conn (fresh)]
      (record! conn {:external-id "base-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 50000M :commodity cad}
                     :basis    {:amount 40000M :commodity cad}})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 10000M (:gross-capital s)))
        (is (== 10000M (:net-capital s)))
        (is (== 5000M  (:taxable-capital s)))
        (is (== 5000M  (-> comp :base :amount)))
        (is (= [5000M] (get-in comp [:jurisdiction-specific-codes :pit-base-additions])))))))

;; ============================================================================
;; §3. LCGE — Note 127 §2 Example A (Mr. Singh CCPC QSBC sale, $1.5M proceeds)
;; ============================================================================

(deftest lcge-worked-example-singh-qsbc-2026
  (testing "Note 127 §2 Ex.A — Mr. Singh, ON resident, sells QSBC for $1,500,000
            (basis $100), $0 prior LCGE → $1,275,000 sheltered, $224,900 net,
            $112,450 taxable capital gain"
    (let [conn (fresh)]
      (record! conn {:external-id "singh-qsbc"
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :subject-form :corp
                     :proceeds {:amount 1500000M :commodity cad}
                     :basis    {:amount 100M :commodity cad}
                     :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:lcge-claimed-prior 0M}})
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 1499900M (:gross-capital s)) "gain = 1,500,000 − 100 = 1,499,900")
        (is (== 1275000M (:lcge-applied s)) "LCGE 2026 cap fully consumed")
        (is (== 224900M  (:net-capital s)) "net after LCGE")
        (is (== 112450M  (:taxable-capital s)) "50% × 224,900 = 112,450")
        (is (== 112450M  (-> comp :base :amount)))
        (is (= [112450M] (get-in comp [:jurisdiction-specific-codes :pit-base-additions])))
        (is (== 0M (get-in comp [:jurisdiction-specific-codes :cgt-summary :lcge-cap-remaining]))
            "the 2026 cap is fully consumed in this single disposal")))))

(deftest lcge-prior-claim-reduces-remaining-pool
  (testing "consumer-supplied :lcge-claimed-prior reduces the available cap;
            disposal claiming LCGE only gets what remains"
    (let [conn (fresh)]
      (record! conn {:external-id "qsbc-after-prior"
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :proceeds {:amount 1500000M :commodity cad}
                     :basis    {:amount 100M :commodity cad}
                     :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [;; Consumer has already claimed $700,000 in prior years.
            facts (run-provider conn :individual p2026
                                {:inputs {:lcge-claimed-prior 700000M}})
            s     (summary facts)
            available (- 1275000M 700000M)] ; 575,000
        (is (== available (:lcge-applied s))
            "only the remaining $575k of the lifetime cap is available")
        (is (== (- 1499900M available) (:net-capital s)))
        (is (== (* 0.5M (- 1499900M available)) (:taxable-capital s)))))))

(deftest lcge-pool-exhausted-floors-at-zero
  (testing "claiming more than the lifetime cap → 0 remaining; no LCGE shelter
            for the disposal"
    (let [conn (fresh)]
      (record! conn {:external-id "qsbc-exhausted"
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :proceeds {:amount 600000M :commodity cad}
                     :basis    {:amount 100M :commodity cad}
                     :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [;; Prior claims already exceed the cap (a defensive cap of 0).
            facts (run-provider conn :individual p2026
                                {:inputs {:lcge-claimed-prior 2000000M}})
            s     (summary facts)]
        (is (== 0M       (:lcge-applied s)) "remaining cap floored at 0")
        (is (== 599900M  (:net-capital s)) "full gain enters the pool")
        (is (== 299950M  (:taxable-capital s)) "50% × 599,900")))))

;; ============================================================================
;; §4. Principal residence — flag-driven full exemption
;; ============================================================================

(deftest principal-residence-exempts-the-full-gain
  (testing "residence? + :ca-principal-residence regime → gain dropped"
    (let [conn (fresh)]
      (record! conn {:external-id "home-sale"
                     :acquired-on #inst "2010-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-principal-residence
                     :proceeds {:amount 1200000M :commodity cad}
                     :basis    {:amount 400000M  :commodity cad}
                     :residence? true
                     :elective-regime #{:ca-principal-residence}})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 0M (:gross-capital s)))
        (is (== 0M (:taxable-capital s)))
        (is (== 0M (-> comp :base :amount)))
        (is (some #(= :principal-residence-exempt (:role %))
                  (:line-items comp))
            "line-items records the exemption")))))

(deftest residence-flag-without-regime-still-taxable
  (testing "residence? alone (no regime elected) does NOT exempt — the consumer
            must elect :ca-principal-residence explicitly"
    (let [conn (fresh)]
      (record! conn {:external-id "home-no-election"
                     :acquired-on #inst "2010-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 1200000M :commodity cad}
                     :basis    {:amount 400000M  :commodity cad}
                     :residence? true})
      (let [s (summary (run-provider conn :individual p2026))]
        (is (== 800000M (:gross-capital s))
            "no exemption elected → full gain enters the pool")
        (is (== 400000M (:taxable-capital s)))))))

;; ============================================================================
;; §5. ABIL — 1/2 deducts against ANY income (PIT/CIT base deduction)
;; ============================================================================

(deftest abil-folds-to-pit-base-deduction
  (testing "an ABIL loss diverts 1/2 to PIT base-deductions (not the capital pool)"
    (let [conn (fresh)]
      ;; Loss of $100,000 on a small-biz investment.
      (record! conn {:external-id "abil-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :proceeds {:amount 0M :commodity cad}
                     :basis    {:amount 100000M :commodity cad}
                     :loss-bucket :ca-abil})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 0M (:gross-capital s))
            "ABIL detours OUT of the capital pool")
        (is (== 50000M (:abil-deduction s))
            "1/2 × 100,000 ABIL deduction (s.38(c))")
        (is (= [50000M] (get-in comp [:jurisdiction-specific-codes :pit-base-deductions]))
            "ABIL surfaces as PIT base DEDUCTION (against ANY income)")
        (is (some #(= :abil-deduction-half (:role %)) (:line-items comp)))))))

;; ============================================================================
;; §6. Section 85 rollover — full gain deferral
;; ============================================================================

(deftest section-85-rollover-excludes-the-gain
  (testing "an s.85 rollover excludes the entire gain from the period's pool"
    (let [conn (fresh)]
      (record! conn {:external-id "s85-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :proceeds {:amount 2000000M :commodity cad}
                     :basis    {:amount 500000M  :commodity cad}
                     :elective-regime #{:ca-§85-rollover}})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 0M (:gross-capital s)))
        (is (== 0M (:taxable-capital s)))
        (is (some #(= :rollover-deferred (:role %)) (:line-items comp))
            "audit line records the rollover")))))

(deftest section-73-spousal-rollover-excludes-the-gain
  (testing "s.73 spousal rollover also defers — basis carries to spouse"
    (let [conn (fresh)]
      (record! conn {:external-id "s73-1"
                     :acquired-on #inst "2015-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 300000M :commodity cad}
                     :basis    {:amount 50000M  :commodity cad}
                     :elective-regime #{:ca-§73-spousal}})
      (let [s (summary (run-provider conn :individual p2026))]
        (is (== 0M (:taxable-capital s)))))))

;; ============================================================================
;; §7. CCA recapture — depreciable split
;; ============================================================================

(deftest depreciable-property-splits-recapture-vs-capital
  (testing "depreciable property with proceeds > capital cost:
            recapture = cap-cost − NBV; capital = proceeds − cap-cost (×50%)"
    (let [conn (fresh)]
      ;; capital cost 100k; depreciated to NBV 40k (depreciation 60k);
      ;; sold for 150k → recapture 60k (ordinary), capital 50k (50% → 25k).
      (record! conn {:external-id "cca-1"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-depreciable
                     :proceeds {:amount 150000M :commodity cad}
                     :basis    {:amount 40000M  :commodity cad}
                     :depreciation-taken {:amount 60000M :commodity cad}})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 60000M (:ordinary-recapture s))
            "recapture = min(150k, 100k) − 40k = 60k (the full depreciation)")
        (is (== 50000M (:gross-capital s))
            "capital = max(0, 150k − 100k) = 50k")
        (is (== 25000M (:taxable-capital s))
            "50% × 50k = 25k taxable cap gain")
        (is (= [25000M 60000M]
               (get-in comp [:jurisdiction-specific-codes :pit-base-additions]))
            "PIT base receives both the 25k taxable cap gain AND the 60k recapture")))))

(deftest depreciable-no-recapture-when-proceeds-below-capital-cost
  (testing "proceeds ≤ capital cost: recapture is the depreciation slice
            recovered (no excess capital gain)"
    (let [conn (fresh)]
      ;; capital cost 100k; NBV 40k; sold for 70k → recapture 30k,
      ;; no capital.
      (record! conn {:external-id "cca-2"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-depreciable
                     :proceeds {:amount 70000M :commodity cad}
                     :basis    {:amount 40000M :commodity cad}
                     :depreciation-taken {:amount 60000M :commodity cad}})
      (let [s (summary (run-provider conn :individual p2026))]
        (is (== 30000M (:ordinary-recapture s)) "recapture = 70k − 40k = 30k")
        (is (== 0M (:gross-capital s)) "no capital portion")
        (is (== 0M (:taxable-capital s)))))))

;; ============================================================================
;; §8. Corporation — folds into CIT base; no LCGE
;; ============================================================================

(deftest corporate-folds-into-cit-base-additions
  (testing "corp net cap gain flows into :cit-base-additions"
    (let [conn (fresh)]
      ;; OpsCo sells 1,000 RBC shares for $200k, basis $80k → $120k gain
      ;; → $60k taxable (Example B from note 127 §2).
      (record! conn {:external-id "opsco-rbc"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-04-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 200000M :commodity cad}
                     :basis    {:amount 80000M  :commodity cad}})
      (let [facts (run-provider conn :corporation p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 120000M (:gross-capital s)))
        (is (== 60000M  (:taxable-capital s)) "Note 127 §2 Ex.B: 50% × 120k = 60k")
        (is (== 60000M  (-> comp :base :amount)))
        (is (= [60000M] (get-in comp [:jurisdiction-specific-codes :cit-base-additions]))
            "corp uses :cit-base-additions, not :pit-")
        (is (nil? (get-in comp [:jurisdiction-specific-codes :pit-base-additions]))
            "no :pit- key for corporate kind")))))

(deftest corporate-lcge-not-available
  (testing "corporation cannot claim LCGE — even when disposal flags it, no
            shelter applies"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-tries-lcge"
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-qsbcs
                     :proceeds {:amount 1500000M :commodity cad}
                     :basis    {:amount 100M :commodity cad}
                     :exemption-claimed #{:ca-lcge-qsbcs}})
      (let [s (summary (run-provider conn :corporation p2026
                                     {:inputs {:lcge-claimed-prior 0M}}))]
        (is (== 0M (:lcge-applied s)) "corporations get no LCGE (s.110.6 individuals + trusts only)")
        (is (== 1499900M (:gross-capital s)))
        (is (== 749950M  (:taxable-capital s)) "50% × 1,499,900")))))

;; ============================================================================
;; §9. Individual base-addition routing
;; ============================================================================

(deftest individual-folds-into-pit-base-additions
  (testing "individual net cap gain flows into :pit-base-additions"
    (let [conn (fresh)]
      (record! conn {:external-id "ind-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 100000M :commodity cad}
                     :basis    {:amount 60000M :commodity cad}})
      (let [comp (only-component (run-provider conn :individual p2026))]
        (is (= [20000M] (get-in comp [:jurisdiction-specific-codes :pit-base-additions])))
        (is (nil? (get-in comp [:jurisdiction-specific-codes :cit-base-additions])))))))

;; ============================================================================
;; §10. Voided disposals excluded
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "voided disposals drop out of the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 200000M :commodity cad}
                     :basis    {:amount 50000M  :commodity cad}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [s (summary (run-provider conn :individual p2026))]
        (is (== 0M (:gross-capital s)))
        (is (== 0M (:taxable-capital s)))))))

;; ============================================================================
;; §11. Superficial loss — denied via flag
;; ============================================================================

(deftest superficial-loss-flag-denies-the-loss
  (testing "a loss flagged :ca-superficial-loss is dropped from the pool"
    (let [conn (fresh)]
      ;; A loss disposal flagged superficial → fully denied
      (record! conn {:external-id "super-loss"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 5000M :commodity cad}
                     :basis    {:amount 15000M :commodity cad}
                     :exemption-claimed #{:ca-superficial-loss}})
      (let [facts (run-provider conn :individual p2026)
            comp  (only-component facts)
            s     (summary facts)]
        (is (== 0M (:gross-capital s)) "superficial loss denied")
        (is (some #(= :superficial-loss-denied (:role %)) (:line-items comp)))))))
