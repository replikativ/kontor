(ns kontor.l10n-de.cgt-provider-test
  "Tests for the DE CGT providers (ADR-102 + ADR-101, research note 113).

   Two providers under test:
   - `de-corporate-cgt-provider` — §8b 95/5 + §6b rollover.
   - `de-personal-cgt-provider`  — §17 + §20 (Abgeltungsteuer + Soli +
     Günstigerprüfung) + §23 (10y real-estate / 1y movable cutoff +
     €1 000 Freigrenze)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-de.cgt-provider :as de-cgt]
            [kontor.l10n-de.cgt-statute :as cgt-statute]
            [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion + DE CIT statute (for
   DE.Soli.rate / DE.KSt.rate that the CGT statute references) + DE
   CGT statute + EUR commodity + a HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cit-statute/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country "DE"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "EUR"]}])
    conn))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private holdco [:kontor.entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal. Defaults are zero-Money proceeds + basis
   so tests fill what they need."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         eur                     ; throwaway ref
                :subject-kind    :participation
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity eur}
                :basis           {:amount 0M :commodity eur}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (case kind
                   :corporation (de-cgt/de-corporate-cgt-provider {:source source})
                   :individual  (de-cgt/de-personal-cgt-provider  {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- component-by-lane
  "Find the first component whose `:jurisdiction-specific-codes :lane`
   matches `lane`."
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)]
      (is (empty? (:components (run-provider conn :corporation p2026))))
      (is (empty? (:components (run-provider conn :individual  p2026)))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn   (fresh)
          source (disp-source/datahike-source conn)
          bad    (de-cgt/->DECapitalGainsTaxProvider
                  :bogus source :de-finanzamt :EUR "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :entity (holdco-eid conn)
                                  :period p2026}))))))

;; ============================================================================
;; §2. §8b corporate — 95/5 participation exemption
;; ============================================================================

(deftest §8b-corporate-95-5-split-note-113-§2-1
  (testing "note 113 §2.1 worked example: GmbH sells participation,
            €4M gain → 95 % exempt + 5 % (= €200k) into CIT base"
    (let [conn (fresh)]
      (record! conn {:external-id "§8b-headline"
                     :asset-class :de-§8b-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :acquired-on #inst "2020-01-15"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 5000000M :commodity eur}
                     :basis    {:amount 1000000M :commodity eur}})
      (let [facts (run-provider conn :corporation p2026)
            §8b   (component-by-lane facts :de-§8b)]
        (is (some? §8b) "an :de-§8b component exists")
        (is (== 4000000M (-> §8b :base :amount))
            "base reflects the full €4M realized gain")
        (is (== 0M (-> §8b :liability :amount))
            "§8b component itself has no standalone liability")
        (is (= [200000M] (get-in §8b [:jurisdiction-specific-codes :cit-base-additions]))
            "the CIT base receives a +€200k (5 %) addback the CIT provider taxes")
        ;; The 95 % exempt line carries the €3.8M un-recognised slice for audit.
        (is (= [4000000M 3800000M 200000M]
               (mapv (comp :amount :value) (:line-items §8b))))))))

(deftest §8b-corporate-with-loss-carry-in
  (testing "§8b carry-in loss offsets the current §8b gain"
    (let [conn (fresh)]
      (record! conn {:external-id "§8b-with-carry"
                     :asset-class :de-§8b-participation
                     :acquired-on #inst "2020-01-15"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 2000000M :commodity eur}
                     :basis    {:amount 1000000M :commodity eur}})
      (let [facts (run-provider
                   conn :corporation p2026
                   {:inputs {:capital-loss-carryforward {:de-§8b 400000M}}})
            §8b   (component-by-lane facts :de-§8b)]
        ;; Gross gain 1 000 000 − 400 000 carry = 600 000 net into §8b pool.
        ;; 5 % addback = 30 000.
        (is (== 600000M (-> §8b :base :amount)))
        (is (= [30000M] (get-in §8b [:jurisdiction-specific-codes :cit-base-additions])))))))

;; ============================================================================
;; §3. §6b rollover — gain deferred via reserve election
;; ============================================================================

(deftest §6b-rollover-elected-defers-gain
  (testing "when :elective-regime contains :de-§6b-reserve, the gain
            is deferred (no §8b pool inclusion, no current-year tax)"
    (let [conn (fresh)]
      (record! conn {:external-id "§6b-roll"
                     :asset-class :de-§6b-eligible
                     :subject-kind :fixed-asset
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 2000000M :commodity eur}
                     :basis    {:amount 1200000M :commodity eur}
                     :elective-regime #{:de-§6b-reserve}})
      (let [facts    (run-provider conn :corporation p2026)
            deferred (component-by-lane facts :de-§6b-deferred)
            §8b      (component-by-lane facts :de-§8b)
            residual (component-by-lane facts :de-§6b-residual)]
        (is (some? deferred) "the deferred component is recorded for audit")
        (is (== 800000M (-> deferred :base :amount))
            "deferred amount reflects the €800k realized gain")
        (is (= [0M] (get-in deferred [:jurisdiction-specific-codes :cit-base-additions]))
            "no CIT base addition — fully deferred")
        (is (nil? §8b) "§6b assets are NOT participations — no §8b lane")
        (is (nil? residual) "rollover elected → not in the residual lane either")))))

(deftest §6b-eligible-without-rollover-folds-into-cit-base
  (testing "no rollover election → the §6b-eligible gain falls into
            CIT base 1:1 (no §8b exemption — these aren't shares)"
    (let [conn (fresh)]
      (record! conn {:external-id "§6b-no-roll"
                     :asset-class :de-§6b-eligible
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 1000000M :commodity eur}
                     :basis    {:amount  600000M :commodity eur}})
      (let [facts    (run-provider conn :corporation p2026)
            residual (component-by-lane facts :de-§6b-residual)
            deferred (component-by-lane facts :de-§6b-deferred)]
        (is (some? residual))
        (is (== 400000M (-> residual :base :amount)))
        (is (= [400000M] (get-in residual [:jurisdiction-specific-codes :cit-base-additions]))
            "full gain into CIT base — no 95 % exemption")
        (is (nil? deferred))))))

;; ============================================================================
;; §4. §17 Teileinkünfteverfahren + Freibetrag taper
;; ============================================================================

(deftest §17-teileinkünfteverfahren-60-percent-inclusion
  (testing "a €100k §17 gain → 60 % × €100k = €60k Teileinkünfte;
            taper consumes ALL of the €9 060 Freibetrag (gain ≫ €45 160) →
            taxable = €60 000"
    (let [conn (fresh)]
      (record! conn {:external-id "§17-large"
                     :asset-class :de-§17-wesentlich
                     :subject-kind :participation
                     :subject-form :corp
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}
                     :ownership-fraction 0.05M})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)]
        (is (some? §17))
        (is (== 60000M (-> §17 :base :amount))
            "60 % of €100k = €60k; Freibetrag fully tapered away")
        (is (= [60000M] (get-in §17 [:jurisdiction-specific-codes :pit-base-additions])))))))

(deftest §17-freibetrag-fully-available-on-small-gain
  (testing "a €10k §17 gain → 60 % = €6 000 < €36 100 taper-start;
            full €9 060 Freibetrag available → taxable = max(0, 6 000 − 9 060) = 0"
    (let [conn (fresh)]
      (record! conn {:external-id "§17-tiny"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 30000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)]
        ;; Note: §17 Freibetrag is bound by max(0, …) — tax can't go
        ;; negative; the base bottoms at 0.
        (is (== 0M (-> §17 :base :amount))
            "Freibetrag fully covers the €6k Teileinkünfte")))))

(deftest §17-freibetrag-partial-taper
  (testing "§17 gross gain €60k > taper-start €36 100; excess €23 900
            fully consumes the €9 060 Freibetrag → Freibetrag after
            taper = 0; Teileinkünfte €36 000 → taxable €36 000 (note
            136 P0-2: the taper anchors on GROSS Veräußerungsgewinn,
            NOT on the 60 % Teileinkünfte)"
    (let [conn (fresh)]
      (record! conn {:external-id "§17-taper-edge"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 80000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)]
        (is (== 36000M (-> §17 :base :amount)))))))

(deftest §17-freibetrag-mid-taper
  (testing "§17 gross gain €70k → Teileinkünfte €42k; taper anchors on
            GROSS, so excess = 70 000 − 36 100 = 33 900 fully consumes
            the €9 060 Freibetrag → Freibetrag after taper = 0 →
            taxable = €42 000 (note 136 P0-2: statute-faithful reading)"
    (let [conn (fresh)]
      (record! conn {:external-id "§17-mid-taper"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 90000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)]
        (is (== 42000M (-> §17 :base :amount)))))))

(deftest §17-freibetrag-boundary-at-fully-consumed
  (testing "§17 gross gain at exactly €45 160 (= €9 060 + €36 100) —
            the boundary at which the Freibetrag is JUST fully consumed.
            excess = 45 160 − 36 100 = 9 060 → Freibetrag after taper = 0
            → Teileinkünfte = 27 096 → taxable = 27 096. This pins the
            gross-gain taper math at the cliff edge (note 136 P0-2)."
    (let [conn (fresh)]
      (record! conn {:external-id "§17-boundary"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 65160M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)]
        ;; 45160 × 0.60 = 27096 Teileinkünfte; Freibetrag fully tapered
        ;; → taxable = 27096
        (is (== 27096M (-> §17 :base :amount)))))))

;; ============================================================================
;; §5. §20 Abgeltungsteuer — 25 % flat + Soli
;; ============================================================================

(deftest §20-abgeltungsteuer-25pct-plus-soli
  (testing "a €10 000 §20-stock gain → 25 % × 10 000 = €2 500 Abgeltungsteuer;
            Soli 5.5 % × 2 500 = €137.50; liability = €2 637.50"
    (let [conn (fresh)]
      (record! conn {:external-id "§20-stock-1"
                     :asset-class :de-§20-stock
                     :subject-kind :securities-stock
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 30000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §20   (component-by-lane facts :de-§20)]
        (is (some? §20))
        (is (== 10000M (-> §20 :base :amount)))
        (is (== 2500M (-> §20 :gross-liability :amount))
            "25 % × €10 000 = €2 500 Abgeltungsteuer")
        (is (== 2637.50M (-> §20 :liability :amount))
            "Abgeltungsteuer + Soli 5.5 % = €2 500 + €137.50")
        (is (= :abgeltungsteuer (:regime §20)))))))

(deftest §20-stock-other-bucket-walls
  (testing "§20 stock-bucket carry-in absorbs ONLY stock gains;
            §20-other carry-in absorbs ONLY other gains"
    (let [conn (fresh)]
      (record! conn {:external-id "§20-stock-mixed"
                     :asset-class :de-§20-stock
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 20000M :commodity eur}
                     :basis    {:amount 10000M :commodity eur}})
      (record! conn {:external-id "§20-other-mixed"
                     :asset-class :de-§20-other
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 15000M :commodity eur}
                     :basis    {:amount  5000M :commodity eur}})
      (let [facts (run-provider
                   conn :individual p2026
                   {:inputs {:capital-loss-carryforward
                             {:de-§20-stock 4000M
                              :de-§20-other 2000M}}})
            §20   (component-by-lane facts :de-§20)]
        ;; Stock net: 10 000 − 4 000 = 6 000
        ;; Other net: 10 000 − 2 000 = 8 000
        ;; Compound: 14 000 → 25 % = 3 500 → +Soli 192.50 = 3 692.50
        (is (== 14000M (-> §20 :base :amount)))
        (is (== 3500M (-> §20 :gross-liability :amount)))
        (is (== 3692.50M (-> §20 :liability :amount)))))))

(deftest §20-günstigerprüfung-folds-into-pit-base
  (testing "with :abgeltungsteuer-elect-marginal? true, the §20 net
            does NOT become an Abgeltungsteuer-bearing component;
            it folds into PIT base for marginal-rate treatment"
    (let [conn (fresh)]
      (record! conn {:external-id "§20-günstig"
                     :asset-class :de-§20-stock
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 30000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      (let [facts (run-provider
                   conn :individual p2026
                   {:tax-unit {:abgeltungsteuer-elect-marginal? true}})
            §20-pit (component-by-lane facts :de-§20-günstig)
            §20     (component-by-lane facts :de-§20)]
        (is (nil? §20) "no standalone Abgeltungsteuer component")
        (is (some? §20-pit) "the Günstigerprüfung component carries the net")
        (is (== 10000M (-> §20-pit :base :amount)))
        (is (= [10000M] (get-in §20-pit [:jurisdiction-specific-codes
                                         :pit-base-additions])))
        (is (== 0M (-> §20-pit :liability :amount))
            "no standalone tax — the PIT provider runs the marginal rate")
        (is (= :günstigerprüfung (:regime §20-pit)))))))

;; ============================================================================
;; §6. §23 — private speculation, real-estate 10-y cutoff
;; ============================================================================

(deftest §23-real-estate-within-window-note-113-§2-2
  (testing "note 113 §2.2 worked example: residential property bought
            2018-03-15, sold 2026-08-20 → 8y 5m → INSIDE 10-y window →
            taxable. Gain €170k (no broker fee here — provider takes
            net at gross-of-fees and consumer subtracts costs upstream;
            we test the substrate, not the fee accounting)"
    (let [conn (fresh)]
      (record! conn {:external-id "§23-housing"
                     :asset-class :de-§23-real-estate
                     :subject-kind :real-estate-private
                     :acquired-on #inst "2018-03-15"
                     :disposed-on #inst "2026-08-20"
                     :proceeds {:amount 420000M :commodity eur}
                     :basis    {:amount 250000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026
                                {:period {:from #inst "2026-01-01"
                                          :to   #inst "2027-01-01"}})
            §23   (component-by-lane facts :de-§23)]
        (is (some? §23) "within the 10-y window → taxable")
        (is (== 170000M (-> §23 :base :amount)))
        (is (= [170000M] (get-in §23 [:jurisdiction-specific-codes
                                      :pit-base-additions]))
            "fold into PIT base for marginal-rate treatment")))))

(deftest §23-real-estate-past-cutoff-tax-free
  (testing "same property bought 2014-01-01, sold 2026-06-15 → 12y
            INSIDE clears 10-y window → TAX-FREE → no §23 component"
    (let [conn (fresh)]
      (record! conn {:external-id "§23-housing-cleared"
                     :asset-class :de-§23-real-estate
                     :acquired-on #inst "2014-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 420000M :commodity eur}
                     :basis    {:amount 250000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)]
        (is (nil? (component-by-lane facts :de-§23))
            "past the 10-y cutoff — the gain is tax-free, no component")))))

(deftest §23-movable-1y-cutoff
  (testing "movable property (gold / crypto / art): 1-y cutoff. Held
            >1y → tax-free; held ≤1y → taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "§23-crypto-fast"
                     :asset-class :de-§23-movable
                     :subject-kind :movable-private
                     :acquired-on #inst "2026-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 50000M :commodity eur}
                     :basis    {:amount 10000M :commodity eur}})
      (record! conn {:external-id "§23-gold-slow"
                     :asset-class :de-§23-movable
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 25000M :commodity eur}
                     :basis    {:amount  5000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §23   (component-by-lane facts :de-§23)]
        (is (some? §23) "the in-window movable disposal contributes")
        ;; Only the 5-month crypto: gain 40 000. The 2-year gold is
        ;; tax-free past the 1-y cutoff and does NOT add to the lane.
        (is (== 40000M (-> §23 :base :amount)))))))

(deftest §23-freigrenze-hard-threshold
  (testing "Freigrenze is a HARD threshold per §23 Abs. 3 S. 5
            (\"weniger als 1 000 Euro\"): < €1 000 → entire gain
            tax-free; ≥ €1 000 → entire gain taxable (NOT just the
            excess). Note 136 P0-1: the €1 000.00 boundary is taxable,
            not the tax-free edge."
    (testing "at €999.99 the gain is fully tax-free"
      (let [conn (fresh)]
        (record! conn {:external-id "§23-under"
                       :asset-class :de-§23-movable
                       :acquired-on #inst "2026-01-01"
                       :disposed-on #inst "2026-06-15"
                       :proceeds {:amount 1999.99M :commodity eur}
                       :basis    {:amount 1000M    :commodity eur}})
        ;; Gain 999.99 — under Freigrenze 1 000 → tax-free
        (let [facts (run-provider conn :individual p2026)]
          (is (nil? (component-by-lane facts :de-§23))
              "below Freigrenze: §23 component suppressed"))))

    (testing "at €1 000.00 EXACTLY the FULL amount is taxable
              (note 136 P0-1 boundary case: \"weniger als 1 000\" is
              strict less-than → ≥ €1 000 is fully taxable)"
      (let [conn (fresh)]
        (record! conn {:external-id "§23-boundary"
                       :asset-class :de-§23-movable
                       :acquired-on #inst "2026-01-01"
                       :disposed-on #inst "2026-06-15"
                       :proceeds {:amount 2000M :commodity eur}
                       :basis    {:amount 1000M :commodity eur}})
        ;; Gain exactly €1 000.00 — AT the Freigrenze boundary;
        ;; statute says fully taxable.
        (let [facts (run-provider conn :individual p2026)
              §23   (component-by-lane facts :de-§23)]
          (is (some? §23) "boundary case: §23 component emitted")
          (is (== 1000M (-> §23 :base :amount))
              "at the boundary the FULL €1 000.00 is taxable"))))

    (testing "at €1 000.01 the FULL amount is taxable"
      (let [conn (fresh)]
        (record! conn {:external-id "§23-over"
                       :asset-class :de-§23-movable
                       :acquired-on #inst "2026-01-01"
                       :disposed-on #inst "2026-06-15"
                       :proceeds {:amount 2000.01M :commodity eur}
                       :basis    {:amount 1000M    :commodity eur}})
        ;; Gain 1 000.01 — over Freigrenze; entire €1 000.01 taxable
        (let [facts (run-provider conn :individual p2026)
              §23   (component-by-lane facts :de-§23)]
          (is (some? §23))
          (is (== 1000.01M (-> §23 :base :amount))
              "hard threshold: ALL of the gain falls into PIT base"))))))

;; ============================================================================
;; §7. §17 carry-in loss (loss-bucket isolation)
;; ============================================================================

(deftest §17-carry-in-loss-offsets-current-gain
  (testing "a §17 carry-in loss offsets §17 gains; does NOT offset §20"
    (let [conn (fresh)]
      (record! conn {:external-id "§17-with-carry"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      ;; §17 carry-in = €40k; gross gain = €100k → §17 net = €60k.
      ;; Note 136 P0-2 fix: taper anchors on the post-carry-in GROSS
      ;; gain (€60k), NOT on the Teileinkünfte. excess = 60 000 −
      ;; 36 100 = 23 900 → Freibetrag fully consumed → 0; Teileinkünfte
      ;; = 60 000 × 0.60 = 36 000 → taxable = 36 000.
      (let [facts (run-provider
                   conn :individual p2026
                   {:inputs {:capital-loss-carryforward {:de-§17 40000M}}})
            §17   (component-by-lane facts :de-§17)]
        (is (== 36000M (-> §17 :base :amount)))))))

;; ============================================================================
;; §8. Multi-regime composition — §17 + §20 + §23 + voided exclusion
;; ============================================================================

(deftest multi-regime-individual-period
  (testing "one individual with §17, §20, §23 disposals all in the same
            period → three independent components"
    (let [conn (fresh)]
      ;; §17 — €100k gain → Teileinkünfte €60k, Freibetrag tapered off,
      ;; taxable €60 000.
      (record! conn {:external-id "multi-§17"
                     :asset-class :de-§17-wesentlich
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-03-01"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      ;; §20 stock — €10k gain → €2 500 Abgeltungsteuer + Soli.
      (record! conn {:external-id "multi-§20"
                     :asset-class :de-§20-stock
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 30000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}})
      ;; §23 real estate within window — €170k → PIT base.
      (record! conn {:external-id "multi-§23"
                     :asset-class :de-§23-real-estate
                     :acquired-on #inst "2018-03-15"
                     :disposed-on #inst "2026-08-20"
                     :proceeds {:amount 420000M :commodity eur}
                     :basis    {:amount 250000M :commodity eur}})
      (let [facts (run-provider conn :individual p2026)
            §17   (component-by-lane facts :de-§17)
            §20   (component-by-lane facts :de-§20)
            §23   (component-by-lane facts :de-§23)]
        (is (= 3 (count (:components facts))))
        (is (== 60000M (-> §17 :base :amount)))
        (is (== 10000M (-> §20 :base :amount)))
        (is (== 170000M (-> §23 :base :amount)))
        (is (== 2637.50M (-> §20 :liability :amount))
            "§20 produces standalone Abgeltungsteuer + Soli")))))

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-me"
                     :asset-class :de-§8b-participation
                     :acquired-on #inst "2020-01-15"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 5000000M :commodity eur}
                     :basis    {:amount 1000000M :commodity eur}})
      (disposal/void! conn {:disposal "void-me" :recorded-by-uid "u"})
      (let [facts (run-provider conn :corporation p2026)]
        (is (empty? (:components facts)))))))

(deftest unknown-asset-class-silently-dropped
  (testing "a disposal with an asset-class neither provider knows is
            silently skipped (forward-compat with future asset classes)"
    (let [conn (fresh)]
      (record! conn {:external-id "alien"
                     :asset-class :uk-residential-property
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 1000000M :commodity eur}
                     :basis    {:amount  600000M :commodity eur}})
      (is (empty? (:components (run-provider conn :corporation p2026))))
      (is (empty? (:components (run-provider conn :individual  p2026)))))))
