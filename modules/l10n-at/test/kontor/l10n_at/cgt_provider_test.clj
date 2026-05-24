(ns kontor.l10n-at.cgt-provider-test
  "Tests for the AT CGT providers (ADR-102 + ADR-101, research note 134).

   Three providers under test:
   - `at-kest-cgt-provider`       — §27/§27a EStG (KESt 27.5 % on
     shares/bonds/funds/derivatives; Verlustverrechnungstopf within
     year; NO carryforward; Regelbesteuerungsoption).
   - `at-immoest-provider`        — §30/§30a EStG (ImmoESt 30 %
     Neuvermögen; pauschale 4.2 % / 18 % Altvermögen;
     Hauptwohnsitzbefreiung 2of2 + 5of10; Herstellerbefreiung;
     §30 Abs 7 cross-category loss carry).
   - `at-corporate-cgt-provider`  — §10 KStG (DEFAULT exempt for
     qualifying Schachtelbeteiligung; opt-in tax-effective Option;
     §12 Abs 3 Z 2 Siebentelregelung losses)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-at.cgt-provider :as at-cgt]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion + AT CGT statute + EUR
   commodity + a HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "AT"
                       :entity/functional-commodity [:commodity/symbol "EUR"]}])
    conn))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal. Defaults are zero-Money proceeds + basis;
   tests fill what they need."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         eur                ; throwaway ref
                :subject-kind    :securities-stock
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity eur}
                :basis           {:amount 0M :commodity eur}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn provider-kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (case provider-kind
                   :kest        (at-cgt/at-kest-cgt-provider      {:source source})
                   :immoest     (at-cgt/at-immoest-provider       {:source source})
                   :corporate   (at-cgt/at-corporate-cgt-provider {:source source}))]
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
      (is (empty? (:components (run-provider conn :kest p2026))))
      (is (empty? (:components (run-provider conn :immoest p2026))))
      (is (empty? (:components (run-provider conn :corporate p2026)))))))

(deftest source-required
  (testing "all three constructors reject a missing :source"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":source"
                          (at-cgt/at-kest-cgt-provider {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":source"
                          (at-cgt/at-immoest-provider {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":source"
                          (at-cgt/at-corporate-cgt-provider {})))))

;; ============================================================================
;; §2. KESt — §27a 27.5 % on shares
;; ============================================================================

(deftest kest-shares-flat-27-5
  (testing "Frau Huber sells OMV shares for €56k after €38k basis (note 134 §2.1)"
    (let [conn (fresh)]
      (record! conn {:external-id "kest-omv"
                     :acquired-on #inst "2022-08-15"
                     :disposed-on #inst "2026-04-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 56000M :commodity eur}
                     :basis       {:amount 38000M :commodity eur}})
      (let [facts (run-provider conn :kest p2026)
            kest  (component-by-lane facts :at-kest)]
        (is (some? kest))
        (is (== 18000M (-> kest :base :amount)) "net gain €18 000")
        ;; 18,000 × 27.5% = 4,950 — exact match to note 134 §2.1
        (is (== 4950M (-> kest :gross-liability :amount)) "KESt 27.5 % = €4 950")
        (is (== 4950M (-> kest :liability :amount))
            "no prepaid → liability = gross")
        (is (= :endbesteuerung (:regime kest)))))))

(deftest kest-with-bank-withholding-zero-liability
  (testing "Endbesteuerungswirkung — bank-withheld via :inputs nets liability to zero"
    (let [conn (fresh)]
      (record! conn {:external-id "kest-erste"
                     :acquired-on #inst "2022-08-15"
                     :disposed-on #inst "2026-04-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 56000M :commodity eur}
                     :basis       {:amount 38000M :commodity eur}})
      (let [facts (run-provider conn :kest p2026
                                {:inputs {:at-kest-prepaid 4950M}})
            kest  (component-by-lane facts :at-kest)]
        (is (== 4950M (-> kest :gross-liability :amount)))
        (is (== 4950M (-> kest :prepaid :amount)))
        (is (== 0M (-> kest :liability :amount))
            "bank-withheld KESt discharges the liability (§97 EStG)")))))

(deftest kest-verlustverrechnungstopf-within-year
  (testing "gain on shares offsets loss on bonds within the year (Verlustverrechnungstopf)"
    (let [conn (fresh)]
      (record! conn {:external-id "kest-gain"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 30000M :commodity eur}
                     :basis       {:amount 10000M :commodity eur}})
      (record! conn {:external-id "kest-loss"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-kest-anleihen
                     :proceeds    {:amount 5000M :commodity eur}
                     :basis       {:amount 12000M :commodity eur}})
      (let [facts (run-provider conn :kest p2026)
            kest  (component-by-lane facts :at-kest)]
        (is (some? kest))
        (is (== 13000M (-> kest :base :amount))
            "gain 20k - loss 7k = 13k taxable")
        (is (== 3575M (-> kest :gross-liability :amount))
            "13,000 × 27.5% = 3,575")))))

(deftest kest-no-carryforward-substrate-discipline
  (testing "the KESt provider does NOT consume :inputs :capital-loss-carryforward — Jan 1 reset"
    (let [conn (fresh)]
      (record! conn {:external-id "kest-loss-only"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 5000M :commodity eur}
                     :basis       {:amount 15000M :commodity eur}})
      ;; Even if a consumer (wrongly) supplies a carry-in, the AT
      ;; KESt provider ignores it (§27 Abs 8 EStG — no carryforward).
      (let [facts (run-provider conn :kest p2026
                                {:inputs {:capital-loss-carryforward
                                          {:at-kest 5000M}}})
            kest  (component-by-lane facts :at-kest)]
        (is (== 0M (-> kest :base :amount))
            "loss clamped to 0 base (no negative base; carry not honored)")
        (is (== 0M (-> kest :gross-liability :amount)))))))

(deftest kest-regelbesteuerung-folds-into-pit
  (testing "Regelbesteuerungsoption — net gain folds into PIT base instead of 27.5 % flat"
    (let [conn (fresh)]
      (record! conn {:external-id "kest-regelbest"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 30000M :commodity eur}
                     :basis       {:amount 10000M :commodity eur}})
      (let [facts (run-provider conn :kest p2026
                                {:tax-unit {:regelbesteuerung-elected? true}})
            kest  (component-by-lane facts :at-kest)]
        (is (= :regelbesteuerung (:regime kest)))
        (is (= [20000M] (get-in kest [:jurisdiction-specific-codes :pit-base-additions]))
            "the 20k net gain rides into PIT marginal rate instead")))))

;; ============================================================================
;; §3. ImmoESt — §30a 30 % Neuvermögen
;; ============================================================================

(deftest immoest-neuvermoegen-30-percent
  (testing "Neuvermögen apartment sale — 30 % on (proceeds − basis)"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-neu"
                     :acquired-on #inst "2014-03-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-neu
                     :subject-kind :real-estate-private
                     :proceeds    {:amount 580000M :commodity eur}
                     :basis       {:amount 320000M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            immo  (component-by-lane facts :at-immoest-neu)]
        (is (some? immo))
        (is (== 260000M (-> immo :base :amount)))
        ;; 260k × 30% = 78,000
        (is (== 78000M (-> immo :gross-liability :amount)))
        (is (= :neuvermoegen (:regime immo)))))))

;; ============================================================================
;; §4. ImmoESt — Altvermögen pauschale (4.2 % / 18 %)
;; ============================================================================

(deftest immoest-altvermoegen-unwidmet-4-2-pauschale
  (testing "Altvermögen unwidmet — 4.2 % effective rate on GROSS proceeds"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-alt-unwidmet"
                     :acquired-on #inst "1990-01-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-alt
                     :subject-kind :real-estate-private
                     :proceeds    {:amount 400000M :commodity eur}
                     :basis       {:amount 0M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            immo  (component-by-lane facts :at-immoest-alt)]
        (is (some? immo))
        (is (== 400000M (-> immo :base :amount))
            "Altvermögen pauschale: base is GROSS proceeds, not gain")
        ;; 400,000 × 4.2% = 16,800
        (is (== 16800.000M (-> immo :gross-liability :amount)))
        (is (= :altvermoegen-unwidmet (:regime immo)))))))

(deftest immoest-altvermoegen-gewidmet-18-pauschale
  (testing "Altvermögen gewidmet (rezoned post-1987) — 18 % effective on proceeds"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-alt-gewidmet"
                     :acquired-on #inst "1990-01-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-alt
                     :elective-regime #{:at-immoest-alt-gewidmet}
                     :subject-kind :real-estate-private
                     :proceeds    {:amount 400000M :commodity eur}
                     :basis       {:amount 0M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            immo  (component-by-lane facts :at-immoest-alt)]
        (is (some? immo))
        ;; 400,000 × 18% = 72,000
        (is (== 72000.00M (-> immo :gross-liability :amount)))
        (is (= :altvermoegen-gewidmet (:regime immo)))))))

;; ============================================================================
;; §5. Hauptwohnsitzbefreiung — both alternative tests
;; ============================================================================

(deftest hauptwohnsitzbefreiung-5of10-zero-tax
  (testing "Herr Mayer Vienna apartment (note 134 §2.2) — 5-of-10 → €0 tax"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-haupt-5of10"
                     :acquired-on #inst "2014-03-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-residence
                     :subject-kind :real-estate-private
                     :residence? true
                     :exemption-claimed #{:at-hauptwohnsitz-5of10}
                     :proceeds    {:amount 580000M :commodity eur}
                     :basis       {:amount 320000M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            cmp   (component-by-lane facts :at-immoest-residence)]
        (is (some? cmp))
        (is (== 0M (-> cmp :liability :amount))
            "Hauptwohnsitzbefreiung (5-of-10) → zero ImmoESt (matches note 134 §2.2)")
        (is (= :hauptwohnsitzbefreiung (:regime cmp)))))))

(deftest hauptwohnsitzbefreiung-2of2-zero-tax
  (testing "2-of-2 test path — exemption applies, zero tax"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-haupt-2of2"
                     :acquired-on #inst "2024-03-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-residence
                     :subject-kind :real-estate-private
                     :residence? true
                     :exemption-claimed #{:at-hauptwohnsitz-2of2}
                     :proceeds    {:amount 800000M :commodity eur}
                     :basis       {:amount 600000M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            cmp   (component-by-lane facts :at-immoest-residence)]
        (is (some? cmp))
        (is (== 0M (-> cmp :liability :amount))
            "Hauptwohnsitzbefreiung (2-of-2) also short-circuits to zero")))))

(deftest residence-without-exemption-defaults-neuvermoegen
  (testing "asset-class :at-immoest-residence without Hauptwohnsitz flag → 30 % Neuvermögen path"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-resi-no-flag"
                     :acquired-on #inst "2014-03-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-residence
                     :subject-kind :real-estate-private
                     :residence? true
                     ;; NO exemption-claimed
                     :proceeds    {:amount 580000M :commodity eur}
                     :basis       {:amount 320000M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            immo  (component-by-lane facts :at-immoest-neu)]
        (is (some? immo) "falls through to Neuvermögen lane")
        (is (== 78000M (-> immo :gross-liability :amount)))))))

;; ============================================================================
;; §6. §30 Abs 7 loss carry — cross-category to §28 Vermietung
;; ============================================================================

(deftest §30-abs-7-loss-carry-60-percent-15-years
  (testing "real-estate loss → 60 % × 1/15 carry against §28-Vermietung via :pit-base-deductions"
    (let [conn (fresh)]
      (record! conn {:external-id "immo-loss"
                     :acquired-on #inst "2014-03-15"
                     :disposed-on #inst "2026-05-20"
                     :asset-class :at-immoest-neu
                     :subject-kind :real-estate-private
                     :proceeds    {:amount 200000M :commodity eur}
                     :basis       {:amount 500000M :commodity eur}})
      (let [facts (run-provider conn :immoest p2026)
            carry (component-by-lane facts :at-immoest-loss-carry)]
        (is (some? carry) "loss-carry component emitted")
        (is (= :§30-abs-7-loss-carry (:regime carry)))
        ;; loss = 300,000; 60 % × 300,000 = 180,000;
        ;; yearly = 180,000 / 15 = 12,000
        (let [pit-deduct (get-in carry [:jurisdiction-specific-codes
                                        :pit-base-deductions :§28-vermietung])]
          (is (vector? pit-deduct))
          (is (== 12000M (first pit-deduct))
              "12,000 / year vs §28 Vermietung (the cross-category destination)"))))))

;; ============================================================================
;; §7. §10 KStG — default exempt (INVERSION)
;; ============================================================================

(deftest §10-default-exempt-no-option
  (testing "Müller-Holding 25 % Swiss AG gain (note 134 §2.3) — default exempt, no Option"
    (let [conn (fresh)]
      (record! conn {:external-id "§10-default"
                     :acquired-on #inst "2018-02-15"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-§10-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :ownership-fraction 0.25M
                     :proceeds    {:amount 12000000M :commodity eur}
                     :basis       {:amount 4000000M  :commodity eur}})
      (let [facts (run-provider conn :corporate p2026)
            cmp   (component-by-lane facts :at-§10-exempt)]
        (is (some? cmp))
        (is (= :§10-default-exempt (:regime cmp)))
        (is (== 0M (-> cmp :liability :amount))
            "default exemption — zero CIT impact at this provider")
        ;; The €8M gain is REMOVED from CIT base (it landed in GL as
        ;; ordinary income; provider deducts it so CIT doesn't tax it).
        (is (= [8000000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))
            "8M flows to :cit-base-deductions — the INVERSION's quiet shape")))))

;; ============================================================================
;; §8. §10 KStG — Option zur Steuerwirksamkeit + losses
;; ============================================================================

(deftest §10-option-elected-gain-taxable
  (testing "Müller-Holding 25 % Swiss AG (note 134 §2.4) — Option elected, gain taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "§10-option-gain"
                     :acquired-on #inst "2018-02-15"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-§10-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :ownership-fraction 0.25M
                     :elective-regime #{:at-§10-tax-effective-option}
                     :proceeds    {:amount 12000000M :commodity eur}
                     :basis       {:amount 4000000M  :commodity eur}})
      (let [facts (run-provider conn :corporate p2026)
            cmp   (component-by-lane facts :at-§10-option-taxable)]
        (is (some? cmp))
        (is (= :§10-tax-effective-option (:regime cmp)))
        (is (= [8000000M] (get-in cmp [:jurisdiction-specific-codes :cit-base-additions]))
            "8M flows to :cit-base-additions — CIT provider applies 23 %")))))

(deftest §10-option-loss-siebentelregelung
  (testing "Option elected + LOSS — 1/7 spread (note 134 §2.4)"
    (let [conn (fresh)]
      (record! conn {:external-id "§10-option-loss"
                     :acquired-on #inst "2018-02-15"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-§10-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :ownership-fraction 0.25M
                     :elective-regime #{:at-§10-tax-effective-option}
                     :proceeds    {:amount 2000000M :commodity eur}
                     :basis       {:amount 4000000M :commodity eur}})
      (let [facts (run-provider conn :corporate p2026)
            cmp   (component-by-lane facts :at-§10-siebentel-loss)]
        (is (some? cmp))
        ;; loss = 2,000,000; 1/7 = 285,714.285714…
        (let [yearly (first (get-in cmp [:jurisdiction-specific-codes :cit-base-deductions]))]
          (is (some? yearly))
          ;; Verify approx 285,714.29 (HALF_EVEN at 12 prec)
          (is (= 0 (.compareTo (bigdec "285714.285714") yearly))
              "1/7 of 2,000,000 = ~285,714.29 — Siebentelregelung yearly slice"))))))

;; ============================================================================
;; §9. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "voided KESt disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-kest"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :at-kest-aktien
                     :proceeds    {:amount 100000M :commodity eur}
                     :basis       {:amount 30000M  :commodity eur}})
      (disposal/void! conn {:disposal "void-kest" :recorded-by-uid "u"})
      (let [facts (run-provider conn :kest p2026)]
        (is (empty? (:components facts))
            "voided disposals excluded from KESt provider source")))))

;; ============================================================================
;; §10. Non-qualifying §10 — falls through (no CGT component)
;; ============================================================================

(deftest §10-non-qualifying-no-component
  (testing "ownership < 10 % → not Schachtelbeteiligung → CGT provider emits nothing"
    (let [conn (fresh)]
      (record! conn {:external-id "§10-non-qual"
                     :acquired-on #inst "2018-02-15"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-§10-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :ownership-fraction 0.05M
                     :proceeds    {:amount 12000000M :commodity eur}
                     :basis       {:amount 4000000M  :commodity eur}})
      (let [facts (run-provider conn :corporate p2026)]
        (is (empty? (:components facts))
            "below the 10 % threshold — gain lands in GL ordinary; provider has no view")))))

;; ============================================================================
;; §11. §10 KStG holding-period gate — < 1 year non-qualifying
;; ============================================================================

(deftest §10-short-holding-no-component
  (testing "holding < 1 year → not qualifying → no CGT component"
    (let [conn (fresh)]
      (record! conn {:external-id "§10-short-hold"
                     :acquired-on #inst "2026-04-15"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :at-§10-participation
                     :subject-kind :participation
                     :subject-form :corp
                     :ownership-fraction 0.25M
                     :proceeds    {:amount 12000000M :commodity eur}
                     :basis       {:amount 4000000M  :commodity eur}})
      (let [facts (run-provider conn :corporate p2026)]
        (is (empty? (:components facts))
            "5-month holding fails the 1-year qualifying period")))))

;; ============================================================================
;; §12. CIT rate ladder — bitemporal swap
;; ============================================================================

(deftest cit-rate-ladder-2024-23-percent
  (testing "CIT rate 23 % active for 2024+ (ÖkoStRefG 2022 stepped reduction)"
    (let [conn  (fresh)
          db    (d/db conn)
          rate-2026 (kontor.statute/parameter-value-at
                     db "AT.KStG.cit-rate" #inst "2026-06-01")
          rate-2023 (kontor.statute/parameter-value-at
                     db "AT.KStG.cit-rate" #inst "2023-06-01")
          rate-2022 (kontor.statute/parameter-value-at
                     db "AT.KStG.cit-rate" #inst "2022-06-01")]
      (is (== 0.23M rate-2026) "23 % from 2024-01-01")
      (is (== 0.24M rate-2023) "24 % calendar 2023")
      (is (== 0.25M rate-2022) "25 % through 2022"))))
