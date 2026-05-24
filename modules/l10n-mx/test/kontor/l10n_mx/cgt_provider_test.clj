(ns kontor.l10n-mx.cgt-provider-test
  "Tests for the MX CGT provider (ADR-102 + ADR-101, research note 132).

   The cases mirror note 132 §2:
     Example A — Sra. Hernández casa habitación ~MXN 9.5M sale.
     Example B — CorpCo CUFIN-adjusted unlisted-share disposal.
   Plus the orthogonal coverage required by note 132 §5: BMV 10 %,
   non-resident regime (gross/net election), notary withholding,
   void exclusion, and the 700k UDIS cap arithmetic."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal-source :as ds]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-mx.cgt-provider :as mx-cgt]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, MX CGT statute, an MXN
   commodity, and one HOLDCO entity (functional commodity MXN)."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:commodity/symbol "MXN" :commodity/name "Mexican Peso"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo SA de CV"
                       :entity/kind :company :entity/country "MX"
                       :entity/functional-commodity [:commodity/symbol "MXN"]}])
    conn))

(def ^:private mxn [:commodity/symbol "MXN"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal MX disposal — proceeds + basis in MXN."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         mxn
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity mxn}
                :basis           {:amount 0M :commodity mxn}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the facts."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (case kind
                   :individual  (mx-cgt/mx-individual-cgt-provider  {:source source})
                   :corporation (mx-cgt/mx-corporate-cgt-provider   {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- component-by-lane
  "Find the first component whose `:jurisdiction-specific-codes :lane`
   matches the given lane keyword."
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing — empty source, kind validation
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns no components"
    (let [conn (fresh)
          facts (run-provider conn :individual p2026)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn (fresh)
          source (disp-source/datahike-source conn)
          bad (mx-cgt/->MXCapitalGainsTaxProvider :bogus source :mx-sat :MXN "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :entity (holdco-eid conn) :period p2026}))))))

;; ============================================================================
;; §2. Casa habitación 700k UDIS cap
;; ============================================================================

(deftest casa-habitacion-fully-exempt-below-cap
  (testing "Sale below 700k UDIS × UDI rate is fully exempt → no component"
    (let [conn (fresh)]
      (record! conn {:external-id "casa-small"
                     :asset-class :mx-inmueble-residencia
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; UDI rate 8.78 → cap MXN 6,146,000 ; proceeds 5M < cap → exempt
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M}})]
        (is (empty? (:components facts))
            "no taxable real-estate component when proceeds ≤ cap")))))

(deftest casa-habitacion-note-132-worked-example
  (testing "Sra. Hernández — note 132 §2 example A — partial exemption"
    (let [conn (fresh)]
      ;; Note 132 §2 Example A.
      ;; Proceeds 9.5M, basis already-INPC-adjusted (the consumer-supplied
      ;; figure stands in for the indexed MOI − accumulated depreciation).
      ;; For the v1 provider the indexed-basis input subsumes the INPC
      ;; adjustment + art. 124 depreciation that note 132 walks through.
      (record! conn {:external-id "casa-1"
                     :asset-class :mx-inmueble-residencia
                     :acquired-on #inst "2018-08-15"
                     :disposed-on #inst "2026-04-10"
                     :proceeds    {:amount 9500000M :commodity mxn}
                     :basis       {:amount 5135255M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (some? cmp) "real-estate component present")
        (is (true? (get-in cmp [:jurisdiction-specific-codes :casa-habitacion?])))
        (let [cap-line (->> (:line-items cmp)
                            (filter #(= :casa-habitacion-cap (:line %)))
                            first)]
          (is (some? cap-line) "the 700k UDIS cap surfaces as a line item")
          (is (== 6146000M (-> cap-line :value :amount))
              "cap MXN = 700 000 × 8.78"))
        (is (pos? (-> cmp :base :amount))
            "taxable base is positive — proceeds exceeded the cap")))))

(deftest casa-habitacion-cooling-off-already-used
  (testing "When the cooling-off cap is already consumed, the disposal is fully taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "casa-2"
                     :asset-class :mx-inmueble-residencia
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M
                                          :mx-residence-cap-used 1M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (some? cmp)
            "cap already used → still taxable, component present")
        (is (== 2000000M (-> cmp :base :amount))
            "base = full gain (proceeds 5M − basis 3M = 2M) — no exemption")))))

;; ============================================================================
;; §3. Real-estate (non-casa-habitación) — art. 120 averaging
;; ============================================================================

(deftest real-estate-art-120-split
  (testing "Non-residence real-estate: art. 120 averaging splits gain by years held"
    (let [conn (fresh)]
      (record! conn {:external-id "secundario"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-01-02"
                     :proceeds    {:amount 8000000M :commodity mxn}
                     :basis       {:amount 1000000M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M
                                          :mx-years-held-override 7M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)
            acu   (->> (:line-items cmp)
                       (filter #(= :acumulable (:line %)))
                       first)
            no-acu (->> (:line-items cmp)
                        (filter #(= :no-acumulable (:line %)))
                        first)]
        (is (some? cmp))
        (is (= 7M (get-in cmp [:jurisdiction-specific-codes :years-held]))
            "years-held override honored")
        ;; gain = 7M ; acumulable = 1M ; no-acumulable = 6M
        (is (== 1000000M (-> acu :value :amount)))
        (is (== 6000000M (-> no-acu :value :amount)))
        (is (= [1000000M]
               (get-in cmp [:jurisdiction-specific-codes :pit-base-additions]))
            "acumulable folds into PIT base for downstream PIT inclusion")))))

(deftest art-120-years-held-cap-20
  (testing "Holding longer than 20 years is clipped — art. 120 divisor cap"
    (let [conn (fresh)]
      (record! conn {:external-id "long-hold"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "1995-01-01"
                     :disposed-on #inst "2026-06-01"
                     :proceeds    {:amount 21000000M :commodity mxn}
                     :basis       {:amount 1000000M  :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (= 20M (get-in cmp [:jurisdiction-specific-codes :years-held]))
            "raw 31y held capped to 20y by parameter MX.CGT.art-120.gain-years-cap")))))

;; ============================================================================
;; §4. BMV — art. 129 10 % broker withholding lane
;; ============================================================================

(deftest bmv-10pct-broker-flat
  (testing "BMV/BIVA listed-share gain → 10 % definitive flat (art. 129)"
    (let [conn (fresh)]
      (record! conn {:external-id "bmv-1"
                     :asset-class :mx-bmv-shares
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 500000M :commodity mxn}
                     :basis       {:amount 200000M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :mx-pf-bolsa-art-129)]
        (is (some? cmp))
        (is (== 300000M (-> cmp :base :amount)))
        ;; 300k × 10% = 30 000
        (is (== 30000M (-> cmp :gross-liability :amount)))))))

(deftest bmv-broker-withholding-credits-prepaid
  (testing "Broker-supplied withholding rides :prepaid and reduces :liability"
    (let [conn (fresh)]
      (record! conn {:external-id "bmv-wh"
                     :asset-class :mx-bmv-shares
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 1000000M :commodity mxn}
                     :basis       {:amount 500000M  :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-bmv-broker-withheld 50000M}})
            cmp   (component-by-lane facts :mx-pf-bolsa-art-129)]
        (is (== 50000M (-> cmp :prepaid :amount)))
        ;; 500k × 10% = 50k = exact match to broker WH → liability 0
        (is (== 0M (-> cmp :liability :amount)))))))

(deftest bmv-loss-carry-within-lane
  (testing "Bolsa-lane carry-forward loss offsets current-year net gain"
    (let [conn (fresh)]
      (record! conn {:external-id "bmv-carry"
                     :asset-class :mx-bmv-shares
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 500000M :commodity mxn}
                     :basis       {:amount 100000M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:capital-loss-carryforward {:mx-bolsa 150000M}}})
            cmp   (component-by-lane facts :mx-pf-bolsa-art-129)]
        (is (== 250000M (-> cmp :base :amount))
            "400k current − 150k carry = 250k")))))

;; ============================================================================
;; §5. Unlisted shares — art. 22 costo promedio (PF + PM)
;; ============================================================================

(deftest unlisted-cufin-adjustment-pf
  (testing "PF unlisted share — CUFIN add to basis lowers the gain"
    (let [conn (fresh)]
      (record! conn {:external-id "unlisted-pf"
                     :asset-class :mx-unlisted-shares
                     :acquired-on #inst "2020-03-15"
                     :disposed-on #inst "2026-05-15"
                     :ownership-fraction 0.30M
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; CUFIN delta 4M × 30% = 1.2M added to basis. Adjusted basis 4.2M → gain 800k.
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-share-adjustments
                                          {"unlisted-pf" {:cufin-delta 4000000M}}}})
            cmp   (component-by-lane facts :mx-pf-unlisted-art-22)]
        (is (some? cmp))
        (is (== 800000M (-> cmp :base :amount)))
        (is (= [800000M]
               (get-in cmp [:jurisdiction-specific-codes :pit-base-additions])))))))

(deftest corp-pm-cufin-adjustment-note-132-example
  (testing "CorpCo (note 132 §2 Example B) — CUFIN folds into PM CIT base"
    (let [conn (fresh)]
      ;; Per note 132 §2 example B but pre-INPC-adjusted basis
      ;; (consumer supplies indexed basis MXN 21.3M; the substrate
      ;; does NOT compute the INPC factor — note 132 §4 Gap A).
      (record! conn {:external-id "corpco-1"
                     :asset-class :mx-unlisted-shares
                     :acquired-on #inst "2020-03-15"
                     :disposed-on #inst "2026-05-15"
                     :ownership-fraction 0.30M
                     :proceeds    {:amount 28000000M :commodity mxn}
                     :basis       {:amount 21300000M :commodity mxn}})
      (let [facts (run-provider conn :corporation p2026
                                {:inputs {:mx-share-adjustments
                                          {"corpco-1" {:cufin-delta 20000000M}}}})
            cmp   (component-by-lane facts :mx-pm-cgt-fold)]
        (is (some? cmp))
        ;; Adjusted basis 21.3M + 6M CUFIN = 27.3M. Gain = 700k.
        (is (== 700000M (-> cmp :base :amount)))
        (is (= [700000M]
               (get-in cmp [:jurisdiction-specific-codes :cit-base-additions]))
            "gain folds into CIT base for the 30 % art. 9 rate at the CIT provider")))))

;; ============================================================================
;; §6. Corporation — basic fold
;; ============================================================================

(deftest corp-pm-fold-without-cufin
  (testing "PM disposal without CUFIN adjustment — net gain folds into CIT"
    (let [conn (fresh)]
      (record! conn {:external-id "pm-land"
                     :asset-class :mx-land
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 1000000M :commodity mxn}})
      (let [facts (run-provider conn :corporation p2026)
            cmp   (component-by-lane facts :mx-pm-cgt-fold)]
        (is (some? cmp))
        (is (== 4000000M (-> cmp :base :amount)))
        (is (= [4000000M]
               (get-in cmp [:jurisdiction-specific-codes :cit-base-additions])))))))

(deftest corp-pm-capital-loss-carry
  (testing "PM capital-loss carry-forward reduces the corporate net cap gain"
    (let [conn (fresh)]
      (record! conn {:external-id "pm-2"
                     :asset-class :mx-land
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 1000000M :commodity mxn}})
      (let [facts (run-provider conn :corporation p2026
                                {:inputs {:capital-loss-carryforward {:mx-capital 1500000M}}})
            cmp   (component-by-lane facts :mx-pm-cgt-fold)]
        (is (== 2500000M (-> cmp :base :amount)))))))

;; ============================================================================
;; §7. Non-resident — art. 160 / 161
;; ============================================================================

(deftest non-resident-gross-25pct
  (testing "NR real estate, default → 25 % on gross consideration"
    (let [conn (fresh)]
      (record! conn {:external-id "nr-1"
                     :asset-class :mx-non-resident-prop
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 10000000M :commodity mxn}
                     :basis       {:amount 4000000M  :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:mx-residence-status :non-resident}})
            cmp   (component-by-lane facts :mx-nr)]
        (is (some? cmp))
        (is (== 10000000M (-> cmp :base :amount))
            "base = gross proceeds in the default (no-dictamen) path")
        ;; 10M × 25% = 2.5M
        (is (== 2500000M (-> cmp :liability :amount)))
        (is (= :mx-nr-default-gross (:regime cmp)))))))

(deftest non-resident-net-35pct-dictamen
  (testing "NR real estate with dictamen election → 35 % on net gain"
    (let [conn (fresh)]
      (record! conn {:external-id "nr-net"
                     :asset-class :mx-non-resident-prop
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :elective-regime #{:mx-art-161-dictamen-on-net}
                     :proceeds    {:amount 10000000M :commodity mxn}
                     :basis       {:amount 4000000M  :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:mx-residence-status :non-resident}})
            cmp   (component-by-lane facts :mx-nr)]
        (is (== 6000000M (-> cmp :base :amount))
            "base = net gain (10M − 4M)")
        ;; 6M × 35% = 2.1M (cheaper than the gross 25% × 10M = 2.5M)
        (is (== 2100000M (-> cmp :liability :amount)))
        (is (= :mx-art-161-dictamen-on-net (:regime cmp)))))))

;; ============================================================================
;; §8. Notary withholding — credits as prepaid
;; ============================================================================

(deftest notary-withholding-prepaid-credit
  (testing "Notary federal + state withholdings ride :prepaid"
    (let [conn (fresh)]
      (record! conn {:external-id "casa-notary"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 8000000M :commodity mxn}
                     :basis       {:amount 5000000M :commodity mxn}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate            8.78M
                                          :mx-isr-retencion-federal 200000M
                                          :mx-isr-retencion-estatal 150000M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)
            wh-lines (->> (:line-items cmp)
                          (filter #(#{:notary-federal :notary-state} (:line %))))]
        (is (some? cmp))
        (is (== 350000M (-> cmp :prepaid :amount))
            "federal + state withholdings sum into :prepaid")
        (is (= 2 (count wh-lines))
            "both withholding lines surface as :line-items")))))

;; ============================================================================
;; §9. Void exclusion — voided disposals do not reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "A voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-mx"
                     :asset-class :mx-bmv-shares
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 1000000M :commodity mxn}
                     :basis       {:amount 100000M  :commodity mxn}})
      (disposal/void! conn {:disposal "void-mx" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2026)]
        (is (empty? (:components facts))
            "voided disposal must NOT produce a component")))))

;; ============================================================================
;; §10. State 5 % surtax — art. 127
;; ============================================================================

(deftest art-127-state-5pct-surfaces
  (testing "Real-estate disposal — 5 % state surtax appears in :gross-liability"
    (let [conn (fresh)]
      (record! conn {:external-id "art-127-1"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; Gain 2M → state 5 % = 100 000
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (some? cmp))
        (is (== 100000M (-> cmp :gross-liability :amount))
            "state surtax = 5 % × 2 000 000")))))
