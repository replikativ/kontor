(ns kontor.l10n-mx.cgt-provider-test
  "Tests for the MX CGT provider (ADR-102 + ADR-101).

   The cases mirror
     Example A — Sra. Hernández casa habitación ~MXN 9.5M sale.
     Example B — CorpCo CUFIN-adjusted unlisted-share disposal.
   Plus orthogonal coverage of non-resident regime (gross/net election),
   notary withholding, void exclusion, and the 700k UDIS cap arithmetic."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.provider.disposal-provider :as ds]
            [kontor.disposal.provider :as disp-provider]
            [kontor.l10n-mx.cgt-provider :as mx-cgt]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.tax.period-tax-provider :as ptp]))

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
    (d/transact conn [{:kontor.commodity/symbol "MXN" :kontor.commodity/name "Mexican Peso"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo SA de CV"
                       :kontor.entity/kind :company :kontor.entity/country "MX"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "MXN"]}])
    conn))

(def ^:private mxn [:kontor.commodity/symbol "MXN"])
(def ^:private holdco [:kontor.entity/code "HOLDCO"])

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
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the facts."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-provider/datahike-provider conn)
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
          source (disp-provider/datahike-provider conn)
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
  (testing "Sra. Hernández — casa-habitación worked example"
    (let [conn (fresh)]
      ;; Note 132 §2 Example A.
      ;; Proceeds 9.5M, basis already-INPC-adjusted (the consumer-supplied
      ;; figure stands in for the indexed MOI − accumulated depreciation).
      ;; For the v1 provider the indexed-basis input subsumes the INPC
      ;; adjustment + art. 124 depreciation.
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
  (testing "CorpCo — CUFIN folds into PM CIT base"
    (let [conn (fresh)]
      ;; Per
      ;; (consumer supplies indexed basis MXN 21.3M; the substrate
      ;; does NOT compute the INPC factor —).
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
  (testing "Real-estate disposal — federal ISR on no-acumulable + 5 % state surtax in :gross-liability"
    (let [conn (fresh)]
      (record! conn {:external-id "art-127-1"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; Gain 2M, ~2.45 yrs → floor 2 yrs.
      ;;   acumulable     = 2M / 2 = 1M (folds into PIT base)
      ;;   no-acumulable  = 2M - 1M = 1M
      ;; Federal ISR (simplified) = 1M × 0.35 = 350 000
      ;; State surtax = 2M × 5 %  = 100 000
      ;; gross-liability = 450 000
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (some? cmp))
        (is (== 100000M (get-in cmp [:jurisdiction-specific-codes :state-surtax]))
            "state surtax = 5 % × 2 000 000 surfaces in jurisdiction-specific-codes")
        (is (== 350000M (get-in cmp [:jurisdiction-specific-codes :federal-isr]))
            "federal ISR on no-acumulable = 1 000 000 × 0.35 (default marginal)")
        (is (== 450000M (-> cmp :gross-liability :amount))
            "gross-liability = federal ISR + state surtax")
        (is (== 450000M (-> cmp :liability :amount))
            "no prepayments → liability = gross-liability")))))

(deftest art-127-real-estate-federal-isr-with-custom-marginal-rate
  (testing "Real-estate disposal — consumer-supplied :mx-marginal-rate is honored"
    (let [conn (fresh)]
      (record! conn {:external-id "art-127-2"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2019-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 10000000M :commodity mxn}
                     :basis       {:amount  3000000M :commodity mxn}})
      ;; Gain 7M, override 7 yrs.
      ;;   acumulable     = 7M / 7 = 1M
      ;;   no-acumulable  = 7M - 1M = 6M
      ;; Federal ISR @ 0.25 = 1 500 000
      ;; State surtax = 7M × 5 % = 350 000
      ;; gross-liability = 1 850 000
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate 8.78M
                                          :mx-marginal-rate 0.25M
                                          :mx-years-held-override 7M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (== 1500000M (get-in cmp [:jurisdiction-specific-codes :federal-isr]))
            "federal ISR honors consumer-supplied marginal rate")
        (is (== 350000M (get-in cmp [:jurisdiction-specific-codes :state-surtax])))
        (is (== 1850000M (-> cmp :gross-liability :amount)))))))

(deftest art-127-federal-and-state-prepayments-credit-correct-slice
  (testing "Federal prepayment credits federal liability; state prepayment credits state — neither over-credits"
    (let [conn (fresh)]
      (record! conn {:external-id "art-127-3"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; Same gain as above: federal ISR = 350 000, state surtax = 100 000.
      ;; Notary federal withholding 200 000, state withholding 80 000.
      ;; Net federal = max(0, 350 000 − 200 000) = 150 000
      ;; Net state   = max(0, 100 000 −  80 000) =  20 000
      ;; Net total   = 170 000
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate            8.78M
                                          :mx-isr-retencion-federal 200000M
                                          :mx-isr-retencion-estatal  80000M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (== 200000M (get-in cmp [:jurisdiction-specific-codes :federal-prepaid])))
        (is (==  80000M (get-in cmp [:jurisdiction-specific-codes :state-prepaid])))
        (is (== 170000M (-> cmp :liability :amount))
            "federal prepaid does NOT over-credit state surtax")))))

(deftest art-127-overpaid-state-does-not-credit-federal
  (testing "An over-large state prepayment cannot be redirected to credit federal liability"
    (let [conn (fresh)]
      (record! conn {:external-id "art-127-4"
                     :asset-class :mx-inmueble
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds    {:amount 5000000M :commodity mxn}
                     :basis       {:amount 3000000M :commodity mxn}})
      ;; federal ISR = 350 000, state surtax = 100 000.
      ;; Notary state withholding 500 000 (excessive) — must NOT cross over.
      ;; Net federal = 350 000 (unchanged) ; Net state = 0 ; Net total = 350 000
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:mx-udis-rate            8.78M
                                          :mx-isr-retencion-estatal 500000M}})
            cmp   (component-by-lane facts :mx-pf-real-estate-art-120)]
        (is (== 350000M (-> cmp :liability :amount))
            "excess state withholding does not credit federal ISR")))))
