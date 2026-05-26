(ns kontor.l10n-au.cgt-provider-test
  "Tests for the AU CGT provider (ADR-102 + ADR-101, research note 129)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-au.cgt-provider :as au-cgt]
            [kontor.l10n-au.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, AU CGT statute, an AUD
   commodity, and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "AUD" :kontor.commodity/name "Australian Dollar"
                       :kontor.commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "AU"
                       :entity/functional-commodity [:kontor.commodity/symbol "AUD"]}])
    conn))

(def ^:private aud [:kontor.commodity/symbol "AUD"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal. Defaults `:kind :sale`, `:subject-kind
   :fixed-asset`. The disposal is owned by HOLDCO; pass any of the
   substrate's other attrs through `opts`."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         aud
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity aud}
                :basis           {:amount 0M :commodity aud}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider for `kind`, call `period-tax-facts`, return the
   resulting facts. `extra-ctx` is merged into the call ctx so the
   tests can pass `:as-of`, `:inputs`, etc."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (au-cgt/au-cgt-provider {:source source :kind kind})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026
  "An FY 2025-26 period (1 Jul 2025 → 30 Jun 2026 — the AU income year)."
  {:from #inst "2025-07-01" :to #inst "2026-07-01"})

(def ^:private p2028
  "An FY 2027-28 period — post the 2027-07-01 discount sunset."
  {:from #inst "2027-07-01" :to #inst "2028-07-01"})

(defn- component
  "Single-component fetch — the AU provider returns at most one
   component per period."
  [facts]
  (first (:components facts)))

;; ============================================================================
;; §1. Plumbing
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn :individual p2026)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider constructor rejects unknown :kind"
    (let [conn (fresh)
          source (disp-source/datahike-source conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (au-cgt/au-cgt-provider {:source source :kind :bogus}))))))

;; ============================================================================
;; §2.1 (note 129) — Marcus, listed shares — $66k LT gain → $33k assessable
;; ============================================================================

(deftest individual-listed-shares-50pct-discount
  (testing "Marcus's BHP shares — $66 000 LT gain, individual, 50 % discount → $33 000 assessable"
    (let [conn (fresh)]
      (record! conn {:external-id "marcus-bhp"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (some? cmp) "an :au-net-capital-gain component exists")
        (is (== 33000M (-> cmp :base :amount))
            "raw gain $66 000 × 50 % discount = $33 000 assessable")
        (is (= [33000M] (get-in cmp [:jurisdiction-specific-codes :pit-base-additions]))
            "PIT base addition surfaces the assessable amount")
        (is (= :individual (get-in cmp [:jurisdiction-specific-codes :holder-kind])))
        (is (zero? (-> cmp :liability :amount))
            "CGT provider does not own a rate — :liability is 0 (folds into PIT)")))))

;; ============================================================================
;; §3. No discount for companies — corp regime
;; ============================================================================

(deftest company-receives-no-discount
  (testing "company holder receives 0 % discount; full gain folds into CIT base"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-1"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (let [facts (run-provider conn :company p2026)
            cmp   (component facts)]
        (is (== 66000M (-> cmp :base :amount))
            "full $66 000 gain — no discount for companies")
        (is (= [66000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-additions]))
            "CIT base addition — corporations fold into CIT not PIT")
        (is (= :company (get-in cmp [:jurisdiction-specific-codes :holder-kind])))))))

;; ============================================================================
;; §4. Super fund — 1/3 discount
;; ============================================================================

(deftest super-fund-receives-one-third-discount
  (testing "complying super fund — 1/3 discount on the $66 000 gain → $44 000.22 assessable"
    (let [conn (fresh)]
      (record! conn {:external-id "super-1"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (let [facts (run-provider conn :super-fund p2026)
            cmp   (component facts)]
        ;; 66000 × (1 − 0.333333) = 66000 × 0.666667 = 44000.022
        (is (== 44000.022M (-> cmp :base :amount))
            "$66 000 × (1 − 1/3) ≈ $44 000.02 — the closest 6dp the
             param-stored 0.333333 produces; semantics check, not
             to-the-cent")
        (is (= [44000.022M] (get-in cmp [:jurisdiction-specific-codes :pit-base-additions])))))))

;; ============================================================================
;; §5. Note 129 §2.2 — Sarah's café — Subdiv 152 cascade $1.6M → $0
;; ============================================================================

(deftest subdiv-152-cascade-zero-assessable
  (testing "Sarah's café — $1.6M gain → 50 % discount → 50 % active-asset → $400k retirement → $0"
    (let [conn (fresh)]
      (record! conn {:external-id      "sarah-cafe"
                     :asset-class      :au-active-business-asset
                     :subject-kind     :business-segment
                     :acquired-on      #inst "2018-04-01"
                     :disposed-on      #inst "2026-03-15"
                     :proceeds         {:amount 2000000M :commodity aud}
                     :basis            {:amount 400000M  :commodity aud}
                     :elective-regime  #{:au-§152-50-active-reduction
                                         :au-§152-retirement-exemption}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:au-retirement-cap-used 0M}})
            cmp   (component facts)]
        (is (== 0M (-> cmp :base :amount))
            "$1 600 000 × 50 % discount = $800 000 × 50 % §152-C = $400 000 − $400 000 §152-D = $0")
        (is (= [0M] (get-in cmp [:jurisdiction-specific-codes :pit-base-additions])))
        ;; The cap-remaining line item should reflect the $400 000 consumed
        ;; → $500 000 − $400 000 = $100 000 residual.
        (let [cap-line (->> cmp :line-items
                            (filter #(= :§152-D-cap-remaining (:line %)))
                            first)]
          (is (== 100000M (-> cap-line :value :amount))
              "$500 000 lifetime cap − $400 000 consumed = $100 000 headroom"))))))

;; ============================================================================
;; §6. Main residence exemption (Div 118-B)
;; ============================================================================

(deftest main-residence-fully-exempt
  (testing "principal residence — :residence? true + :au-main-residence claim → 0 assessable"
    (let [conn (fresh)]
      (record! conn {:external-id        "home-1"
                     :asset-class        :au-property-main-residence
                     :subject-kind       :real-estate-private
                     :acquired-on        #inst "2010-06-01"
                     :disposed-on        #inst "2026-03-15"
                     :proceeds           {:amount 1500000M :commodity aud}
                     :basis              {:amount 600000M  :commodity aud}
                     :residence?         true
                     :exemption-claimed  #{:au-main-residence}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)
            exempt-line (->> cmp :line-items
                             (filter #(= :exempt-reason (:line %)))
                             first)]
        (is (== 0M (-> cmp :base :amount)))
        (is (some? exempt-line) "an :exempt-reason line item is emitted")))))

(deftest main-residence-claim-without-residence-flag-not-exempt
  (testing "claim without :residence? true → NOT exempt (the gate fails)"
    (let [conn (fresh)]
      (record! conn {:external-id        "home-bogus"
                     :asset-class        :au-property-investment
                     :subject-kind       :real-estate-private
                     :acquired-on        #inst "2010-06-01"
                     :disposed-on        #inst "2026-03-15"
                     :proceeds           {:amount 1500000M :commodity aud}
                     :basis              {:amount 600000M  :commodity aud}
                     :exemption-claimed  #{:au-main-residence}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (== 450000M (-> cmp :base :amount))
            "$900 000 × 50 % discount = $450 000 assessable")))))

;; ============================================================================
;; §7. Retirement-cap lifetime tracking — partial consumption
;; ============================================================================

(deftest retirement-cap-tracks-prior-consumption
  (testing "prior $300 000 used → cap residual $200 000; current election capped"
    (let [conn (fresh)]
      (record! conn {:external-id     "retire-1"
                     :asset-class     :au-active-business-asset
                     :subject-kind    :business-segment
                     :acquired-on     #inst "2018-04-01"
                     :disposed-on     #inst "2026-03-15"
                     ;; raw gain 1_000_000; ×50% discount = 500_000;
                     ;; no §152-C this disposal; §152-D elected — capped at
                     ;; min(500_000, residual $200_000) = $200_000.
                     :proceeds        {:amount 1100000M :commodity aud}
                     :basis           {:amount 100000M  :commodity aud}
                     :elective-regime #{:au-§152-retirement-exemption}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:au-retirement-cap-used 300000M}})
            cmp   (component facts)]
        (is (== 300000M (-> cmp :base :amount))
            "$500 000 post-discount − $200 000 cap residual = $300 000 assessable")
        (let [cap-line (->> cmp :line-items
                            (filter #(= :§152-D-cap-remaining (:line %)))
                            first)]
          (is (== 0M (-> cap-line :value :amount))
              "lifetime cap fully exhausted"))))))

;; ============================================================================
;; §8. Indexation election raises (v1 unimplemented)
;; ============================================================================

(deftest indexation-election-raises
  (testing "explicit :au-indexation-method election raises :not-yet-implemented (v1)"
    (let [conn (fresh)]
      (record! conn {:external-id     "indx-1"
                     :asset-class     :au-listed-shares
                     :acquired-on     #inst "1995-01-01"
                     :disposed-on     #inst "2026-03-15"
                     :proceeds        {:amount 200000M :commodity aud}
                     :basis           {:amount 50000M  :commodity aud}
                     :elective-regime #{:au-indexation-method}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not yet implemented"
                            (run-provider conn :individual p2026))))))

;; ============================================================================
;; §9. 1 July 2027 sunset — discount disabled for individuals
;; ============================================================================

(deftest discount-sunsets-post-2027-for-individuals
  (testing "post 2027-07-01 + :individual → no discount, full gain assessable"
    (let [conn (fresh)]
      (record! conn {:external-id "marcus-post-sunset"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2027-09-30"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (let [facts (run-provider conn :individual p2028
                                {:as-of #inst "2027-09-30"})
            cmp   (component facts)]
        (is (== 66000M (-> cmp :base :amount))
            "post-sunset: full $66 000 gain — discount repealed for individuals (TODO 30% min effective rate)"))))

  (testing "post 2027-07-01 + :super-fund → 1/3 discount STILL applies"
    (let [conn (fresh)]
      (record! conn {:external-id "super-post-sunset"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2027-09-30"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (let [facts (run-provider conn :super-fund p2028
                                {:as-of #inst "2027-09-30"})
            cmp   (component facts)]
        ;; 66000 × (1 − 0.333333) = 44000.022 — super-fund unchanged.
        (is (== 44000.022M (-> cmp :base :amount))
            "super-fund unaffected by the 2027-07-01 reform")))))

;; ============================================================================
;; §10. Voided disposals excluded
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 198000M :commodity aud}
                     :basis       {:amount 132000M :commodity aud}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2026)]
        (is (empty? (:components facts)))))))

;; ============================================================================
;; §11. Short-term — no discount, full gain assessable
;; ============================================================================

(deftest short-term-no-discount
  (testing "asset held ≤ 12 months → no discount, full gain assessable"
    (let [conn (fresh)]
      (record! conn {:external-id "st-1"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2026-01-01"
                     :disposed-on #inst "2026-06-15"   ; ~5.5 months
                     :proceeds    {:amount 50000M :commodity aud}
                     :basis       {:amount 20000M :commodity aud}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (== 30000M (-> cmp :base :amount))
            "5.5-month hold → no discount → full $30 000 assessable")))))

;; ============================================================================
;; §12. Loss carry-in offsets current gain (single bucket)
;; ============================================================================

(deftest loss-carryforward-offsets
  (testing "$30 000 LT gain − $10 000 carry-in BEFORE discount = $10 000 assessable (s102-5 Steps 1-3)"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-carry"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 130000M :commodity aud}
                     :basis       {:amount 100000M :commodity aud}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:au-capital-loss-carryforward {:capital 10000M}}})
            cmp   (component facts)]
        ;; ITAA 1997 s102-5 Method Statement: losses (Step 1-2) apply
        ;; BEFORE the discount (Step 3). Raw gain $30 000 − $10 000
        ;; carry-in = $20 000 net pre-discount; × 50 % discount =
        ;; $10 000 assessable. (Was previously $5 000 with the
        ;; post-discount-netting bug — P0-1, note 138 §2.4.)
        (is (== 10000M (-> cmp :base :amount)))))))

;; ============================================================================
;; §13. Personal-use threshold — first-element basis under $10k → exempt
;; ============================================================================

(deftest personal-use-under-10k-exempt
  (testing "personal-use asset with basis ≤ $10 000 → s118-10(3) exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "boat-1"
                     :asset-class :au-personal-use
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 25000M :commodity aud}
                     :basis       {:amount 8000M  :commodity aud}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (== 0M (-> cmp :base :amount))
            "basis $8 000 ≤ $10 000 → s118-10(3) personal-use exemption")))))

(deftest personal-use-loss-above-threshold-disregarded
  (testing "above-$10k personal-use LOSS is disregarded per s108-20(1) — does NOT offset other gains"
    (let [conn (fresh)]
      ;; A personal-use boat with basis > $10k so the s118-10(3)
      ;; threshold exemption does NOT fire — but s108-20(1) still
      ;; disregards the loss entirely. Pair it with a discountable
      ;; share gain to assert the loss DOES NOT enter the loss bucket.
      (record! conn {:external-id "boat-loss"
                     :asset-class :au-personal-use
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 12000M :commodity aud}
                     :basis       {:amount 17000M :commodity aud}})
      (record! conn {:external-id "shares-gain"
                     :asset-class :au-listed-shares
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 30000M :commodity aud}
                     :basis       {:amount 10000M :commodity aud}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        ;; If the personal-use loss were (wrongly) bucketed, the share
        ;; gain would be ($20 000 − $5 000) × 50 % = $7 500. The
        ;; law-correct answer: loss disregarded, full $20 000 × 50 % =
        ;; $10 000 assessable. (P0-2, note 138 §2.5.)
        (is (== 10000M (-> cmp :base :amount))
            "s108-20(1) personal-use loss vanishes — share gain attracts full discount only")))))

(deftest collectable-under-500-exempt
  (testing "collectable with basis ≤ $500 → s118-10(1) exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "stamp-1"
                     :asset-class :au-collectable
                     :acquired-on #inst "2022-06-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds    {:amount 5000M :commodity aud}
                     :basis       {:amount 400M  :commodity aud}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (== 0M (-> cmp :base :amount))
            "basis $400 ≤ $500 → s118-10(1) collectable exemption")))))

;; ============================================================================
;; §14. Foreign-resident TAP exemption
;; ============================================================================

(deftest foreign-non-tap-exempt
  (testing "foreign resident's non-TAP holding → Div 855 exclusion → 0 assessable"
    (let [conn (fresh)]
      (record! conn {:external-id        "foreign-1"
                     :asset-class        :au-listed-shares
                     :acquired-on        #inst "2022-06-01"
                     :disposed-on        #inst "2026-03-15"
                     :proceeds           {:amount 198000M :commodity aud}
                     :basis              {:amount 132000M :commodity aud}
                     :exemption-claimed  #{:au-foreign-resident-non-tap}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component facts)]
        (is (== 0M (-> cmp :base :amount)))))))
