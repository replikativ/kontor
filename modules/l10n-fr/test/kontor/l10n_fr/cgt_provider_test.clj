(ns kontor.l10n-fr.cgt-provider-test
  "Tests for the FR CGT providers (corporate + personal) over the
   ADR-102 disposal substrate + ADR-101 statute-as-data. Reproduces
   the three worked examples from research note 128 §2 to the cent,
   plus exercises every lane / exemption / election."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-fr.cgt-provider :as fr-cgt]
            [kontor.l10n-fr.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

;; ============================================================================
;; Fixture — one HOLDCO + one INDIVIDUAL entity, EUR commodity, FR CGT statute
;; ============================================================================

(defn- fresh
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo SAS"
                       :entity/kind :company :entity/country "FR"
                       :entity/functional-commodity [:kontor.commodity/symbol "EUR"]}
                      {:entity/code "INDIV" :entity/name "Mlle Dupont"
                       :entity/kind :company :entity/country "FR"
                       :entity/functional-commodity [:kontor.commodity/symbol "EUR"]}])
    conn))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private holdco [:entity/code "HOLDCO"])
(def ^:private indiv [:entity/code "INDIV"])

(defn- entity-eid [conn code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :entity/code ?code]] (d/db conn) code))

(defn- record!
  "Record a minimal disposal — entity defaults to INDIV."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity            indiv
                :kind              :sale
                :subject           eur            ; ref of convenience; not load-bearing
                :subject-kind      :fixed-asset
                :recorded-by-uid   "test"
                :proceeds          {:amount 0M :commodity eur}
                :basis             {:amount 0M :commodity eur}}
               opts)))

(defn- run-personal
  "Build the personal provider, call period-tax-facts."
  [conn entity-code period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (fr-cgt/fr-personal-cgt-provider {:source source})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (entity-eid conn entity-code)
             :period period}
            extra-ctx))))

(defn- run-corporate
  [conn entity-code period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (fr-cgt/fr-corporate-cgt-provider {:source source})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (entity-eid conn entity-code)
             :period period}
            extra-ctx))))

(def ^:private p2025 {:from #inst "2025-01-01" :to #inst "2026-01-01"})
(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- by-lane
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(defn- amt
  ^java.math.BigDecimal [money]
  (some-> money :amount))

;; ============================================================================
;; §1. Plumbing — empty source, statute install
;; ============================================================================

(deftest empty-source-personal-returns-zero-components
  (testing "no disposals → empty :components"
    (let [conn (fresh)
          facts (run-personal conn "INDIV" p2026
                              {:tax-unit {:pfu-or-bareme :pfu}})]
      (is (empty? (:components facts))))))

(deftest empty-source-corporate-returns-zero-components
  (testing "no disposals → empty :components for corporate too"
    (let [conn (fresh)
          facts (run-corporate conn "HOLDCO" p2026)]
      (is (empty? (:components facts))))))

(deftest statute-installs-2026-PS-rates
  (testing "the LFSS 2026 PS rate bump (17.2 % → 18.6 %) splits by income class (note 141 P0-1)"
    (let [conn (fresh)
          db   (d/db conn)]
      ;; Revenus du patrimoine (mobilière) — hike RETROACTIVE to 2025-income
      (is (== 0.186M
              (statute/parameter-value-at
               db "FR.CGT.PS.patrimoine-rate" #inst "2025-08-15"))
          "patrimoine 2025-income at 18.6 % (LFSS 2026 retroactive)")
      (is (== 0.186M
              (statute/parameter-value-at
               db "FR.CGT.PS.patrimoine-rate" #inst "2026-06-15"))
          "patrimoine 2026 at 18.6 %")
      (is (== 0.172M
              (statute/parameter-value-at
               db "FR.CGT.PS.patrimoine-rate" #inst "2024-06-15"))
          "2024 patrimoine still at legacy 17.2 %")
      ;; Revenus de placement (dividendes / intérêts / PFLU) — hike forward 2026-01-01
      (is (== 0.172M
              (statute/parameter-value-at
               db "FR.CGT.PS.placement-rate" #inst "2025-08-15"))
          "placement 2025 still at legacy 17.2 % (hike only forward from 2026-01-01)")
      (is (== 0.186M
              (statute/parameter-value-at
               db "FR.CGT.PS.placement-rate" #inst "2026-06-15"))
          "placement 2026 at 18.6 %")
      (is (== 0.172M
              (statute/parameter-value-at
               db "FR.CGT.PS.real-estate-rate" #inst "2026-06-15"))
          "real-estate stays at 17.2 % through 2026 — LFSS carve-out"))))

;; ============================================================================
;; §2. Mobilière — PFU default (note 128 §2.1 Track 1)
;; ============================================================================

(deftest mobiliere-pfu-default-31_4-pct
  (testing "PFU default — €200k gain on listed shares, no abattement"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-pfu"
                     :asset-class :fr-titres-listed
                     :acquired-on #inst "2015-03-15"
                     :disposed-on #inst "2026-04-20"
                     :proceeds {:amount 300000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :pfu}})
            mob   (by-lane facts :fr-mobilière)]
        (is (some? mob) "mobilière component present")
        ;; Note 128 §2.1 Track 1: 12.8% × 200k = €25,600 + 18.6% × 200k = €37,200 = €62,800
        (is (== 25600M (-> (filter #(= :mob-ir-tax (:line %)) (:line-items mob))
                           first :value :amount))
            "IR PFU 12.8 % × €200 000 = €25 600")
        (is (== 62800M (amt (:liability mob)))
            "Total = €25 600 IR + €37 200 PS = €62 800 (per note 128 §2.1 Track 1)")
        (is (= :fr-pfu (:regime mob)))))))

(deftest mobiliere-2025-disposal-PS-186-retroactive
  (testing "2025 mobilière disposal pays PS at 18.6 % (LFSS 2026 rétroactif aux revenus du patrimoine 2025 — note 141 P0-1)"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-2025"
                     :asset-class :fr-titres-listed
                     :acquired-on #inst "2020-01-15"
                     :disposed-on #inst "2025-09-10"   ; 2025-period disposal
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2025
                                {:tax-unit {:pfu-or-bareme :pfu}})
            mob   (by-lane facts :fr-mobilière)]
        (is (some? mob) "mobilière component present for 2025 period")
        ;; €100k gain. IR PFU 12.8 % = €12 800.
        ;; PS RÉTROACTIF (revenus du patrimoine 2025 → 18.6 %) × €100k = €18 600.
        ;; Total = €31 400. Under the buggy encoding pre-P0-1 fix, PS would
        ;; be 17.2 % = €17 200 (off by €1 400).
        (is (== 12800M (-> (filter #(= :mob-ir-tax (:line %)) (:line-items mob))
                           first :value :amount))
            "IR PFU 12.8 % × €100k = €12 800")
        (is (== 31400M (amt (:liability mob)))
            "Total = €12 800 IR + €18 600 PS = €31 400 (PS 18.6 % retroactive per LFSS 2026)")))))

;; ============================================================================
;; §3. Mobilière — barème + abattement renforcé 85 % (note 128 §2.1 Track 2)
;; ============================================================================

(deftest mobiliere-bareme-with-renforce-85pct-abatement
  (testing "barème election + abattement renforcé 85 % on pre-2018 PME shares ≥8y"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-bareme"
                     :asset-class :fr-titres-pme
                     :acquired-on #inst "2015-03-15"      ; pre-2018
                     :disposed-on #inst "2026-04-20"      ; ~11 years held
                     :proceeds {:amount 300000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}
                     :exemption-claimed #{:fr-abattement-durée}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :bareme}})
            mob   (by-lane facts :fr-mobilière)]
        (is (some? mob))
        (is (= :fr-barème (:regime mob)))
        ;; Abattement 85 % → taxable IR base = 200 000 × 15 % = €30 000
        (is (== 30000M (-> (filter #(= :mob-ir-base (:line %)) (:line-items mob))
                           first :value :amount))
            "IR base = €30 000 (200k × 15 % after 85 % abattement)")
        ;; IR folds into PIT base (zero standalone IR tax)
        (is (== 0M (-> (filter #(= :mob-ir-tax (:line %)) (:line-items mob))
                       first :value :amount))
            "barème election → IR fold into PIT, standalone IR = 0")
        (is (= [30000M] (get-in mob [:jurisdiction-specific-codes :pit-base-additions]))
            "IR base of €30 000 routes to PIT via :pit-base-additions")
        ;; PS on gross 200k × 18.6 % = €37 200 (Art. 150-0 D 4° — PS on gross)
        (is (== 37200M (amt (:liability mob)))
            "Total = €0 IR (deferred to PIT) + €37 200 PS on gross")))))

(deftest mobiliere-bareme-no-abatement-post-2018
  (testing "post-2018 acquisitions get NO abattement-durée even under barème"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-post2018"
                     :asset-class :fr-titres-pme
                     :acquired-on #inst "2019-01-15"    ; post-2018 cliff
                     :disposed-on #inst "2026-04-20"
                     :proceeds {:amount 300000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}
                     :exemption-claimed #{:fr-abattement-durée}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :bareme}})
            mob   (by-lane facts :fr-mobilière)]
        ;; No abattement — full €200k base folds into PIT
        (is (== 200000M (-> (filter #(= :mob-ir-base (:line %)) (:line-items mob))
                            first :value :amount)))
        (is (= [200000M] (get-in mob [:jurisdiction-specific-codes :pit-base-additions])))))))

(deftest mobiliere-abatement-general-50pct-at-2-years
  (testing "abattement général 50 % at 2y held, barème elected, pre-2018"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-2y"
                     :asset-class :fr-titres-listed    ; général, not renforcé
                     :acquired-on #inst "2015-06-01"   ; ~10.9 years → ≥8y
                     :disposed-on #inst "2026-04-20"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}
                     :exemption-claimed #{:fr-abattement-durée}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :bareme}})
            mob   (by-lane facts :fr-mobilière)]
        ;; ≥8y général → 65 % abattement → taxable = 35 % × €100k = €35k
        (is (== 35000M (-> (filter #(= :mob-ir-base (:line %)) (:line-items mob))
                           first :value :amount))
            "≥8y under général → 65 % abattement (note 128 §1.2 table)")))))

;; ============================================================================
;; §4. Mobilière — loss carryforward (10y, Art. 150-0 D 11°)
;; ============================================================================

(deftest mobiliere-carry-applied-before-abatement
  (testing "fr-mv-mobilière carry-in offsets gain BEFORE abattement"
    (let [conn (fresh)]
      (record! conn {:external-id "mob-c"
                     :asset-class :fr-titres-listed
                     :acquired-on #inst "2020-01-01"   ; post-2018, no abat anyway
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 60000M :commodity eur}
                     :basis    {:amount 10000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :pfu}
                                 :inputs   {:capital-loss-carryforward
                                            {:fr-mv-mobilière 30000M}}})
            mob   (by-lane facts :fr-mobilière)]
        ;; €50k gain − €30k carry = €20k base; IR 12.8 % × 20k = 2 560
        ;; PS 18.6 % × 50k (GROSS, Art. 150-0 D 4°) = 9 300
        (is (== 2560M (-> (filter #(= :mob-ir-tax (:line %)) (:line-items mob))
                          first :value :amount)))
        (is (== 11860M (amt (:liability mob)))
            "€2 560 IR (on post-carry base) + €9 300 PS (on gross) = €11 860")))))

;; ============================================================================
;; §5. PEA exonération
;; ============================================================================

(deftest pea-exoneration-zeroes-ir-but-keeps-ps
  (testing "PEA exemption-claimed → IR = 0, PS at 18.6 % on gain"
    (let [conn (fresh)]
      (record! conn {:external-id "pea-1"
                     :asset-class :fr-pea
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"   ; ≥5y → exempt
                     :proceeds {:amount 50000M :commodity eur}
                     :basis    {:amount 20000M :commodity eur}
                     :exemption-claimed #{:fr-pea-exoneration}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :pfu}})
            mob   (by-lane facts :fr-mobilière)]
        ;; PEA gain = 30k; IR = 0 (exonération 5y), PS = 18.6 % × 30k = 5 580
        (is (== 0M (-> (filter #(= :mob-ir-tax (:line %)) (:line-items mob))
                       first :value :amount)))
        (is (== 5580M (amt (:liability mob)))
            "PS 18.6 % × €30k = €5 580 only (IR exonéré)")))))

;; ============================================================================
;; §6. Immobilière — long-held secondary residence (note 128 §2.2)
;; ============================================================================

(deftest immobiliere-23y-secondary-residence-note128-§2_2
  (testing "Note 128 §2.2: 23-year secondary residence — IR fully exempt, PS partial"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-23y"
                     :asset-class :fr-immobilier-autre
                     :acquired-on #inst "2002-09-15"
                     :disposed-on #inst "2026-06-30"   ; 23 years, 9 months
                     :proceeds {:amount 450000M :commodity eur}
                     :basis    {:amount 180000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026)
            immo  (by-lane facts :fr-immobilière)]
        (is (some? immo))
        ;; IR ladder: 22y → 100 % abatement → IR base = 0
        (is (== 0M (-> (filter #(= :immo-ir-base (:line %)) (:line-items immo))
                       first :value :amount)))
        ;; PS ladder: 23y = 16×1.65 + 1.6 + 1×9 = 26.4 + 1.6 + 9 = 37 % → 63 % remaining
        ;; PS base = €270 000 × 63 % = €170 100
        (is (== 170100M (-> (filter #(= :immo-ps-base (:line %)) (:line-items immo))
                            first :value :amount))
            "23y → 37 % PS abattement → PS base = €270 000 × 63 % = €170 100 (note 128 §2.2)")
        ;; Total tax: 0 IR + 0 surtaxe (IR base = 0) + 17.2 % × €170 100 = €29 257.20
        (is (== 29257.2M (amt (:liability immo)))
            "Note 128 §2.2: €29 257 total (17.2 % × €170 100 + 0 IR + 0 surtaxe)")))))

(deftest immobiliere-residence-principale-exempt
  (testing "résidence principale → both components zeroed"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-rp"
                     :asset-class :fr-immobilier-residence
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity eur}
                     :basis    {:amount 200000M :commodity eur}
                     :residence? true
                     :exemption-claimed #{:fr-residence-principale}})
      (let [facts (run-personal conn "INDIV" p2026)]
        (is (nil? (by-lane facts :fr-immobilière))
            "résidence principale → no immobilière component (zero tax)")))))

(deftest immobiliere-surtaxe-1609-nonies-G
  (testing "surtaxe 1609 nonies G fires above €50k IR base"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-surtaxe"
                     :asset-class :fr-immobilier-autre
                     :acquired-on #inst "2024-01-01"     ; <6y → no abatement
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity eur}
                     :basis    {:amount 380000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026)
            immo  (by-lane facts :fr-immobilière)
            surtaxe (-> (filter #(= :immo-surtaxe (:line %)) (:line-items immo))
                        first :value :amount)]
        ;; Gain = €120k; IR base full €120k. Surtaxe brackets:
        ;;   50k @ 2% = 1000; 20k slice (100k-120k) @ 3% = 600 → €1 600
        (is (== 1600M surtaxe))))))

(deftest immobiliere-22y-fully-exempt-ir-still-ps
  (testing "22y → IR fully exempt, PS still partial"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-22y"
                     :asset-class :fr-immobilier-autre
                     :acquired-on #inst "2004-01-01"
                     :disposed-on #inst "2026-06-15"  ; 22 years
                     :proceeds {:amount 300000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026)
            immo  (by-lane facts :fr-immobilière)]
        ;; 22 years: IR 16×6+4 = 100 %; PS 16×1.65 + 1.6 = 28 % → 72 % remaining
        (is (== 0M (-> (filter #(= :immo-ir-base (:line %)) (:line-items immo))
                       first :value :amount)))
        (is (== 144000M (-> (filter #(= :immo-ps-base (:line %)) (:line-items immo))
                            first :value :amount))
            "PS base = 200k × 72 % = €144 000")))))

;; ============================================================================
;; §7. Pro court-terme — folds into PIT, §151 septies revenue-tested
;; ============================================================================

(deftest pro-court-terme-folds-into-pit
  (testing "pro court-terme → flows into PIT via :pit-base-additions"
    (let [conn (fresh)]
      (record! conn {:external-id "pct-1"
                     :asset-class :fr-pro-court-terme
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 80000M :commodity eur}
                     :basis    {:amount 30000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026)
            pct   (by-lane facts :fr-pro-ct)]
        (is (some? pct))
        (is (= [50000M] (get-in pct [:jurisdiction-specific-codes :pit-base-additions])))
        (is (== 0M (amt (:liability pct))) "no standalone tax; folds into PIT")))))

(deftest pro-§151-septies-fully-exempt-below-threshold
  (testing "services BIC/BNC turnover €60k (< €90k) → fully exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "pct-§151"
                     :asset-class :fr-pro-court-terme
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 80000M :commodity eur}
                     :basis    {:amount 30000M :commodity eur}
                     :exemption-claimed #{:fr-151-septies-pme}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:inputs {:151-septies {:activity :services
                                                        :revenue 60000M}}})
            pct   (by-lane facts :fr-pro-ct)]
        (is (nil? pct) "fully exempt → no component")))))

(deftest pro-§151-septies-degressive-band
  (testing "services turnover €108k (mid €90k-€126k band) → 50 % taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "pct-§151-mid"
                     :asset-class :fr-pro-court-terme
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 80000M :commodity eur}
                     :basis    {:amount 30000M :commodity eur}
                     :exemption-claimed #{:fr-151-septies-pme}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:inputs {:151-septies {:activity :services
                                                        :revenue 108000M}}})
            pct   (by-lane facts :fr-pro-ct)]
        ;; fraction = (108k - 90k)/(126k - 90k) = 18/36 = 50 % taxable
        ;; gain 50k × 50 % = €25k taxable
        (is (= [25000M] (get-in pct [:jurisdiction-specific-codes :pit-base-additions])))))))

;; ============================================================================
;; §8. Pro long-terme — preferential 12.8 % IR + 17.2 % PS
;; ============================================================================

(deftest pro-long-terme-preferential-rate
  (testing "pro long-terme: 12.8 % IR + 17.2 % PS, no exemption"
    (let [conn (fresh)]
      (record! conn {:external-id "plt-1"
                     :asset-class :fr-pro-long-terme
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026)
            plt   (by-lane facts :fr-pro-lt)]
        (is (some? plt))
        ;; Gain €100k; IR 12.8 % = €12 800; PS 17.2 % = €17 200; Total = €30 000
        (is (== 30000M (amt (:liability plt)))
            "12.8 % IR + 17.2 % PS = 30 % on €100k = €30 000")))))

(deftest pro-long-terme-238-quindecies-agri-full-exempt
  (testing "§238 quindecies AGRICULTURAL transmission ≤ €700k (FY-2025 cliff) → fully exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "plt-§238-agri"
                     :asset-class :fr-pro-long-terme
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 600000M :commodity eur}  ; <€700k FY-2025 agri cliff
                     :basis    {:amount 100000M :commodity eur}
                     :exemption-claimed #{:fr-238-quindecies-transmission}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:inputs {:238-quindecies
                                          {:transmission-value 600000M
                                           :activity :agricultural}}})]
        (is (nil? (by-lane facts :fr-pro-lt))
            "agricultural transmission €600k < €700k cliff → fully exempt")))))

(deftest pro-long-terme-238-quindecies-standard-600k-not-fully-exempt
  (testing "§238 quindecies STANDARD non-agricultural transmission €600k → partial taxable (note 141 P0-2)"
    (let [conn (fresh)]
      (record! conn {:external-id "plt-§238-std"
                     :asset-class :fr-pro-long-terme
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 600000M :commodity eur}
                     :basis    {:amount 100000M :commodity eur}
                     :exemption-claimed #{:fr-238-quindecies-transmission}})
      ;; Default activity is :standard. Standard cliffs €500k / €1M (stable).
      ;; transmission €600k → fraction = (600k − 500k)/(1M − 500k) = 0.2
      ;; gain €500k × 20 % = €100k taxable.
      ;; IR 12.8 % × €100k = €12 800; PS 17.2 % × €100k = €17 200; total €30 000.
      (let [facts (run-personal conn "INDIV" p2026
                                {:inputs {:238-quindecies
                                          {:transmission-value 600000M}}})
            plt   (by-lane facts :fr-pro-lt)]
        (is (some? plt) "standard transmission at €600k is NOT fully exempt")
        (is (== 100000M (-> (filter #(= :pro-lt-base (:line %)) (:line-items plt))
                            first :value :amount))
            "taxable base = €500k × 20 % = €100k (note 141 P0-2 — standard cliffs unchanged)")
        (is (== 30000M (amt (:liability plt)))
            "€12 800 IR + €17 200 PS = €30 000 on €100k taxable base")))))

;; ============================================================================
;; §9. Titres de participation — IS-side QPFC 12 % (note 128 §2.3)
;; ============================================================================

(deftest titres-participation-qpfc-12pct-note128-§2_3
  (testing "Note 128 §2.3: SAS sells qualifying 30 % stake → QPFC €960k"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "tp-1"
                     :asset-class :fr-titres-participation
                     :subject-form :corp
                     :acquired-on #inst "2020-06-15"
                     :disposed-on #inst "2026-08-20"   ; ≥2y
                     :proceeds {:amount 10000000M :commodity eur}
                     :basis    {:amount 2000000M  :commodity eur}
                     :ownership-fraction 0.30M})
      (let [facts (run-corporate conn "HOLDCO" p2026)
            tp    (by-lane facts :fr-titres-participation)]
        (is (some? tp))
        ;; €8M gross gain × 12 % QPFC = €960k → :cit-base-additions
        (is (= [960000M] (get-in tp [:jurisdiction-specific-codes :cit-base-additions]))
            "Note 128 §2.3: QPFC = 12 % × €8M = €960k routes to :cit-base-additions")
        ;; The IS lift is at the CIT layer; this component is liability-zero
        (is (== 0M (amt (:liability tp))))))))

(deftest titres-participation-under-2y-not-eligible
  (testing "<2y holding → does NOT qualify as titres de participation"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "tp-1y"
                     :asset-class :fr-titres-participation
                     :subject-form :corp
                     :acquired-on #inst "2025-08-01"   ; <2y
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 5000000M :commodity eur}
                     :basis    {:amount 1000000M :commodity eur}})
      (let [facts (run-corporate conn "HOLDCO" p2026)]
        (is (nil? (by-lane facts :fr-titres-participation))
            "<2y → no titres-participation component (substrate filters)")))))

;; ============================================================================
;; §10. Brevets / IP box — CGI Art. 238 — 10 %
;; ============================================================================

(deftest brevets-ip-box-10pct
  (testing "brevet sale with IP-box election → 10 % rate"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "brevet-1"
                     :asset-class :fr-brevet
                     :subject-kind :intangible
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 2000000M :commodity eur}
                     :basis    {:amount 500000M  :commodity eur}
                     :elective-regime #{:fr-ip-box-238}})
      (let [facts (run-corporate conn "HOLDCO" p2026)
            ip    (by-lane facts :fr-ip-box)]
        (is (some? ip))
        ;; €1.5M net × 10 % = €150 000
        (is (== 150000M (amt (:liability ip))))
        (is (= :fr-ip-box-238 (:regime ip)))))))

(deftest brevets-ip-box-with-nexus-ratio
  (testing "nexus-ratio 0.7 weights the net income before 10 %"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "brevet-nx"
                     :asset-class :fr-brevet
                     :subject-kind :intangible
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 2000000M :commodity eur}
                     :basis    {:amount 500000M  :commodity eur}
                     :elective-regime #{:fr-ip-box-238}})
      (let [facts (run-corporate conn "HOLDCO" p2026
                                 {:inputs {:ip-box {"brevet-nx" {:nexus-ratio 0.7M}}}})
            ip    (by-lane facts :fr-ip-box)]
        ;; €1.5M × 0.7 = €1.05M × 10 % = €105 000
        (is (== 105000M (amt (:liability ip))))))))

(deftest brevets-without-election-no-component
  (testing "brevet WITHOUT :fr-ip-box-238 election → no IP-box component"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "brevet-noelect"
                     :asset-class :fr-brevet
                     :subject-kind :intangible
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 1000000M :commodity eur}
                     :basis    {:amount 200000M  :commodity eur}})
      (let [facts (run-corporate conn "HOLDCO" p2026)]
        (is (nil? (by-lane facts :fr-ip-box))
            "no election → no component")))))

;; ============================================================================
;; §11. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :asset-class :fr-titres-listed
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity eur}
                     :basis    {:amount 50000M  :commodity eur}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :pfu}})]
        (is (empty? (:components facts)))))))

;; ============================================================================
;; §12. Bitemporal — pre-2025 §238 quindecies cliff at €500k
;; ============================================================================

(deftest §238-quindecies-bitemporal-cliff
  (testing "standard cliff stable €500k; agricultural cliff bitemporal €500k → €700k (note 141 P0-2)"
    (let [conn (fresh)
          db   (d/db conn)]
      ;; Standard (non-agricultural) cliff is stable at €500k — LFI 2024 did NOT raise it.
      (is (== 500000M (statute/parameter-value-at
                       db "FR.CGT.§238-quindecies.threshold-full" #inst "2024-06-15"))
          "standard cliff €500k (pre-2025)")
      (is (== 500000M (statute/parameter-value-at
                       db "FR.CGT.§238-quindecies.threshold-full" #inst "2026-06-15"))
          "standard cliff stays €500k post-2025 (LFI 2024 raise is agri-only)")
      ;; Agricultural cliff IS bitemporal: €500k pre-2025, €700k from FY-2025.
      (is (== 500000M (statute/parameter-value-at
                       db "FR.CGT.§238-quindecies.agri-threshold-full" #inst "2024-06-15"))
          "agricultural pre-2025 cliff €500k")
      (is (== 700000M (statute/parameter-value-at
                       db "FR.CGT.§238-quindecies.agri-threshold-full" #inst "2026-06-15"))
          "agricultural post-2025 cliff €700k (LFI 2024 VII bis)"))))

;; ============================================================================
;; §13. Multi-component — corporate has both titres + brevets simultaneously
;; ============================================================================

(deftest corporate-multi-component
  (testing "one corporation, both titres-de-participation + brevet IP box"
    (let [conn (fresh)]
      (record! conn {:entity      holdco
                     :external-id "multi-tp"
                     :asset-class :fr-titres-participation
                     :subject-form :corp
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 5000000M :commodity eur}
                     :basis    {:amount 2000000M :commodity eur}})
      (record! conn {:entity      holdco
                     :external-id "multi-brev"
                     :asset-class :fr-brevet
                     :subject-kind :intangible
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 1000000M :commodity eur}
                     :basis    {:amount 200000M  :commodity eur}
                     :elective-regime #{:fr-ip-box-238}})
      (let [facts (run-corporate conn "HOLDCO" p2026)]
        (is (= 2 (count (:components facts))) "both lanes fire")
        (is (some? (by-lane facts :fr-titres-participation)))
        (is (some? (by-lane facts :fr-ip-box)))
        ;; TP: 3M × 12 % = 360k QPFC
        (is (= [360000M] (get-in (by-lane facts :fr-titres-participation)
                                 [:jurisdiction-specific-codes :cit-base-additions])))
        ;; Brevet: 800k × 10 % = 80k
        (is (== 80000M (amt (:liability (by-lane facts :fr-ip-box)))))))))

;; ============================================================================
;; §14. Personal multi-lane — mobilière + immobilière simultaneously
;; ============================================================================

(deftest personal-multi-lane
  (testing "one individual with both mobilière + immobilière disposals in the year"
    (let [conn (fresh)]
      ;; Mobilière: simple PFU
      (record! conn {:external-id "p-mob"
                     :asset-class :fr-titres-listed
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 100000M :commodity eur}
                     :basis    {:amount 50000M  :commodity eur}})
      ;; Immobilière: 10 years held, no abatement other than start-of-ladder
      (record! conn {:external-id "p-immo"
                     :asset-class :fr-immobilier-autre
                     :acquired-on #inst "2016-06-15"   ; ~10y
                     :disposed-on #inst "2026-06-30"
                     :proceeds {:amount 400000M :commodity eur}
                     :basis    {:amount 300000M :commodity eur}})
      (let [facts (run-personal conn "INDIV" p2026
                                {:tax-unit {:pfu-or-bareme :pfu}})]
        (is (= 2 (count (:components facts))))
        (is (some? (by-lane facts :fr-mobilière)))
        (is (some? (by-lane facts :fr-immobilière)))))))
