(ns kontor.regression.cit-group-a-test
  "Regression suite — corporate income tax (CIT) for DE / US / CA / FR / JP.

   Each jurisdiction's l10n `cit-provider` is run end-to-end through
   `kontor.tax.period-tax-provider/period-tax-facts` against an
   AUTHORITY-published worked example (the exact figures already carried
   in each module's `cit_provider_test.clj`), then extended with one
   CHALLENGING extra scenario whose expected component liabilities are
   hand-computed from the same statute (source in the comment above each
   `is`).

   These providers are known-good (ADR-104/105/106/107 + Gap-#5 closure),
   so every deftest here is expected to PASS. A failure is a real
   regression — such a test would be tagged ^:kaocha/pending PENDING(NEW).

   Money is BigDecimal throughout; every assertion uses `==` (never `=`
   on doubles) so 43687.50M and 43687.500M compare equal.

   Providers exercised:
     DE — kontor.l10n-de.cit-provider  (KSt 15% + Soli 5.5% + GewSt 3.5%×Hebesatz)
     US — kontor.l10n-us.cit-provider  (§11 flat 21%)
     CA — kontor.l10n-ca.cit-provider  (T2 federal + per-province, CCPC SBD cascade)
     FR — kontor.l10n-fr.cit-provider  (IS 25% / PME 15%-25% + CGE 3.3% + CIR)
     JP — kontor.l10n-jp.cit-provider  (national/local CIT + enterprise + inhabitants)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.l10n-de.cit-provider :as de-cit]
            [kontor.l10n-de.cit-statute :as de-statute]
            [kontor.l10n-us.cit-provider :as us-cit]
            [kontor.l10n-us.cit-statute :as us-statute]
            [kontor.l10n-ca.cit-provider :as ca-cit]
            [kontor.l10n-ca.cit-statute :as ca-statute]
            [kontor.l10n-fr.cit-provider :as fr-cit]
            [kontor.l10n-fr.cit-statute :as fr-statute]
            [kontor.l10n-jp.cit-provider :as jp-cit]
            [kontor.l10n-jp.cit-statute :as jp-statute]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- fresh
  "Fresh in-memory test DB with `install-fn` (a statute installer) run."
  [install-fn]
  (let [conn (core/create-test-db)]
    (install-fn conn)
    conn))

(defn- component
  "Pull the first component with `:authority` = `authority` out of facts."
  [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

(defn- surtax-amount
  [component-map code]
  (some (fn [s] (when (= code (:code s)) (:amount s))) (:surtaxes component-map)))

;; ===========================================================================
;; DE — Körperschaftsteuer + Solidaritätszuschlag + Gewerbesteuer
;; ===========================================================================

(defn- de-compute [hebesatz inputs]
  (ptp/period-tax-facts
   (de-cit/de-cit-provider {})
   {:entity   :gmbh
    :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
    :db       (d/db (fresh de-statute/install!))
    :as-of    #inst "2025-06-30"
    :tax-unit {:hebesatz hebesatz}
    :inputs   inputs}))

(deftest de-bmf-worked-example
  ;; Source: BMF GmbH worked example (modules/l10n-de cit_provider_test §1).
  ;; €150k profit, Hebesatz 380%, no add-backs. KSt 22,500 + Soli 1,237.50
  ;; + GewSt 19,950 = €43,687.50.
  (testing "DE BMF GmbH €150k @ Hebesatz 380% → €43,687.50"
    (let [facts (de-compute 380 {:book-profit 150000M})
          kst   (component facts :de-bundesfinanzministerium)
          gewst (component facts :de-municipality)]
      (is (== 22500M    (:amount (:gross-liability kst))))
      (is (== 1237.50M  (:amount (first (:surtaxes kst)))))
      (is (== 23737.50M (:amount (:liability kst))))
      (is (== 19950M    (:amount (:liability gewst))))
      (is (== 43687.50M (total-liability facts))))))

(deftest de-challenging-high-hebesatz-larger-profit
  ;; CHALLENGING extra: Munich Hebesatz 490% on a €300k profit, no add-backs.
  ;; Hand-computed from the same headline rates:
  ;;   KSt   = 300,000 × 15%          = 45,000
  ;;   Soli  = 45,000 × 5.5%          =  2,475      → KSt liability 47,475
  ;;   GewSt = 300,000 × 3.5% × 4.90  = 300,000 × 0.1715 = 51,450
  ;;   Total = 47,475 + 51,450        = 98,925
  (testing "DE GmbH €300k @ Hebesatz 490% → €98,925.00"
    (let [facts (de-compute 490 {:book-profit 300000M})
          kst   (component facts :de-bundesfinanzministerium)
          gewst (component facts :de-municipality)]
      (is (== 45000M    (:amount (:gross-liability kst))))
      (is (== 2475M     (:amount (first (:surtaxes kst)))))
      (is (== 47475M    (:amount (:liability kst))))
      (is (== 51450M    (:amount (:liability gewst))))
      (is (== 98925M    (total-liability facts))))))

;; ===========================================================================
;; US — IRC §11 flat 21% federal CIT
;; ===========================================================================

(defn- us-compute
  ([inputs] (us-compute inputs #inst "2025-12-31"))
  ([inputs as-of]
   (ptp/period-tax-facts
    (us-cit/us-cit-provider {})
    {:entity   :c-corp
     :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
     :db       (d/db (fresh us-statute/install!))
     :as-of    as-of
     :tax-unit {}
     :inputs   inputs})))

(deftest us-section-11-flat-21pct
  ;; Source: IRC §11(b), published worked example (l10n-us cit_provider_test §1).
  ;; $1,000,000 taxable × 21% = $210,000, flat.
  (testing "US C-Corp $1M taxable → $210,000 (flat 21%)"
    (let [facts (us-compute {:book-profit 1000000M})
          c     (->> facts :components first)]
      (is (= :flat (:kontor.schedule/type (:schedule c))))
      (is (== 0.21M    (:rate (:schedule c))))
      (is (== 210000M  (:amount (:gross-liability c))))
      (is (== 210000M  (total-liability facts))))))

(deftest us-challenging-nol-plus-163j-plus-250
  ;; CHALLENGING extra: every base lever fires at once (l10n-us statute vocab).
  ;;   book-profit                 5,000,000
  ;;   + §163(j) disallowed int.    +200,000
  ;;   − §250 FDII/GILTI deduction  −300,000
  ;;   − §172 NOL applied           −800,000
  ;;   = taxable base            4,100,000
  ;;   CIT = 4,100,000 × 21%     =  861,000
  (testing "US C-Corp $5M − adjustments → base $4.1M, CIT $861,000"
    (let [facts (us-compute {:book-profit                5000000M
                             :§163j-disallowed-interest   200000M
                             :§250-deduction              300000M
                             :nol-applied                 800000M})
          c     (->> facts :components first)
          applied (set (-> c :provenance :provisions-applied))]
      (is (== 4100000M (:amount (:base c))))
      (is (== 861000M  (:amount (:liability c))))
      (is (contains? applied "US-IRC-§172-nol-deduction"))
      (is (contains? applied "US-IRC-§163j-interest-cap"))
      (is (contains? applied "US-IRC-§250-fdii-gilti-deduction")))))

;; ===========================================================================
;; CA — T2 federal + per-province (CCPC small-business deduction cascade)
;; ===========================================================================

(defn- ca-compute [tax-unit inputs]
  (ptp/period-tax-facts
   (ca-cit/ca-cit-provider {})
   {:entity   :corp
    :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
    :db       (d/db (fresh ca-statute/install!))
    :as-of    #inst "2025-06-30"
    :tax-unit tax-unit
    :inputs   inputs}))

(deftest ca-cra-acme-widgets-ccpc-on-ab
  ;; Source: CRA Acme Widgets Co. worked example (l10n-ca cit_provider_test §1).
  ;; CCPC, CAD 620,000 taxable, Schedule-5 allocation ON 65% / AB 35%.
  ;;   Federal = 500k×9% (45,000) + 120k×15% (18,000)            = 63,000
  ;;   Ontario = 325k×3.2% (10,400) + 78k×11.5% (8,970)          = 19,370
  ;;   Alberta = 175k×2% (3,500) + 42k×8% (3,360)                =  6,860
  ;;   Total                                                     = 89,230
  (testing "CA CCPC 620k ON/AB (65/35) → CAD 89,230"
    (let [facts (ca-compute {:ccpc? true
                             :provincial-allocation {:on 0.65M :ab 0.35M}}
                            {:taxable-income 620000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)
          ab    (component facts :ca-ab-tra)]
      (is (== 63000M (:amount (:liability fed))))
      (is (== 19370M (:amount (:liability on))))
      (is (== 6860M  (:amount (:liability ab))))
      (is (== 89230M (total-liability facts))))))

(deftest ca-challenging-ccpc-above-sbd-limit-single-province
  ;; CHALLENGING extra: a single-province CCPC whose income STRADDLES the
  ;; $500k small-business limit, so both the SBD and general-rate slices
  ;; fire federally and provincially (Ontario, pre-Bill-12: $500k @ 3.2%).
  ;;   Income 800,000, ON 100%.
  ;;   Federal = 500k×9% (45,000) + 300k×15% (45,000)  = 90,000
  ;;   Ontario = 500k×3.2% (16,000) + 300k×11.5% (34,500) = 50,500
  ;;   Total                                            = 140,500
  (testing "CA CCPC 800k ON-only straddling the $500k SBD limit → CAD 140,500"
    (let [facts (ca-compute {:ccpc? true
                             :provincial-allocation {:on 1M}}
                            {:taxable-income 800000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)]
      (is (== 800000M (:amount (:base fed))))
      (is (== 90000M  (:amount (:liability fed))))
      (is (== 800000M (:amount (:base on))))
      (is (== 50500M  (:amount (:liability on))))
      (is (== 140500M (total-liability facts))))))

;; ===========================================================================
;; FR — Impôt sur les sociétés (IS) + Contribution sociale (CGE)
;; ===========================================================================

(defn- fr-compute [tax-unit inputs]
  (ptp/period-tax-facts
   (fr-cit/fr-cit-provider {})
   {:entity   :sas
    :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
    :db       (d/db (fresh fr-statute/install!))
    :as-of    #inst "2025-12-31"
    :tax-unit tax-unit
    :inputs   inputs}))

(deftest fr-pme-15-25-with-cge
  ;; Source: legifiscal.fr worked example (l10n-fr cit_provider_test §2).
  ;; PME (CA HT €8M, NOT CGE-exempt), bénéfice fiscal €4M.
  ;;   IS  = 42,500×15% (6,375) + 3,957,500×25% (989,375) = 995,750
  ;;   CGE = (995,750 − 763,000) × 3.3%                   =   7,680.75
  ;;   Total                                              = 1,003,430.75
  (testing "FR PME €4M profit + CGE → €1,003,430.75"
    (let [facts (fr-compute {:pme? true :cge-exempt? false}
                            {:book-profit 4000000M})
          is-c  (component facts :fr-dgfip)]
      (is (= :progressive-bracket (:kontor.schedule/type (:schedule is-c))))
      (is (== 995750M      (:amount (:gross-liability is-c))))
      (is (== 7680.75M     (:amount (first (:surtaxes is-c)))))
      (is (== 1003430.75M  (:amount (:liability is-c))))
      (is (== 1003430.75M  (total-liability facts))))))

(deftest fr-challenging-large-non-pme-with-cge
  ;; CHALLENGING extra: a large non-PME (flat 25%) with a €10M bénéfice
  ;; fiscal, CGE firing well above the €763k abattement.
  ;;   IS  = 10,000,000 × 25%                              = 2,500,000
  ;;   CGE = (2,500,000 − 763,000) × 3.3% = 1,737,000×3.3% =    57,321
  ;;   Total                                               = 2,557,321
  (testing "FR non-PME €10M profit + CGE → €2,557,321.00"
    (let [facts (fr-compute {:pme? false :cge-exempt? false}
                            {:book-profit 10000000M})
          is-c  (component facts :fr-dgfip)]
      (is (= :flat (:kontor.schedule/type (:schedule is-c))))
      (is (== 0.25M       (:rate (:schedule is-c))))
      (is (== 2500000M    (:amount (:gross-liability is-c))))
      (is (== 57321M      (:amount (first (:surtaxes is-c)))))
      (is (= :fr-cge      (:code (first (:surtaxes is-c)))))
      (is (== 2557321M    (:amount (:liability is-c))))
      (is (== 2557321M    (total-liability facts))))))

;; ===========================================================================
;; JP — national/local CIT + enterprise tax + inhabitants' tax
;; ===========================================================================

(defn- jp-compute
  ([tax-unit inputs] (jp-compute tax-unit inputs #inst "2025-06-30"))
  ([tax-unit inputs as-of]
   (ptp/period-tax-facts
    (jp-cit/jp-cit-provider {})
    {:entity   :kk
     :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
     :db       (d/db (fresh jp-statute/install!))
     :as-of    as-of
     :tax-unit tax-unit
     :inputs   inputs})))

(deftest jp-jetro-sme-worked-example
  ;; Source: JETRO Tokyo SME worked example (l10n-jp cit_provider_test §1).
  ;; SME @ ¥10M income, capital ≤¥10M, ≤50 employees, Tokyo.
  ;;   National CIT = 15%×8M + 23.2%×2M            = 1,664,000
  ;;   Local CIT    = 10.3% × 1,664,000            =   171,392   → nat liab 1,835,392
  ;;   Enterprise   = 3.5%×4M + 5.3%×4M + 7%×2M    =   492,000
  ;;   Special corp = 37% × 492,000                =   182,040   → ent liab   674,040
  ;;   Inhabitant   = 7% × 1,664,000               =   116,480
  ;;   Per-capita   = tier(≤¥10M, ≤50)             =    70,000   → inh liab   186,480
  ;;   Total (with per-capita)                     = 2,695,912
  (testing "JP SME ¥10M income (Tokyo) → ¥2,695,912"
    (let [facts (jp-compute {:is-sme?         true
                             :capital-class   :capital-up-to-10m
                             :headcount-class :small
                             :prefecture      :tokyo}
                            {:book-profit 10000000M})
          nat   (component facts :jp-nta)
          ent   (component facts :jp-prefecture)
          inh   (component facts :jp-municipality)]
      (is (== 1664000M (:amount (:gross-liability nat))))
      (is (== 171392M  (surtax-amount nat :local-corporate-tax)))
      (is (== 1835392M (:amount (:liability nat))))
      (is (== 492000M  (:amount (:gross-liability ent))))
      (is (== 182040M  (surtax-amount ent :special-corp-enterprise-tax)))
      (is (== 674040M  (:amount (:liability ent))))
      (is (== 116480M  (surtax-amount inh :inhabitant-income-levy)))
      (is (== 70000M   (surtax-amount inh :inhabitant-per-capita-levy)))
      (is (== 186480M  (:amount (:liability inh))))
      (is (== 2695912M (total-liability facts))))))

(deftest jp-challenging-sme-at-8m-kink
  ;; CHALLENGING extra: SME income lands EXACTLY on the ¥8M national-CIT
  ;; kink, so the 23.2% band contributes nothing and every downstream
  ;; surtax scales off the smaller ¥1.2M national CIT.
  ;;   National CIT = 15% × 8M                     = 1,200,000
  ;;   Local CIT    = 10.3% × 1,200,000            =   123,600   → nat liab 1,323,600
  ;;   Enterprise   = 3.5%×4M + 5.3%×4M + 7%×0     =   352,000
  ;;   Special corp = 37% × 352,000                =   130,240   → ent liab   482,240
  ;;   Inhabitant   = 7% × 1,200,000               =    84,000
  ;;   Per-capita   = tier(≤¥10M, ≤50)             =    70,000   → inh liab   154,000
  ;;   Total                                       = 1,959,840
  (testing "JP SME exactly ¥8M income (Tokyo) → ¥1,959,840"
    (let [facts (jp-compute {:is-sme?         true
                             :capital-class   :capital-up-to-10m
                             :headcount-class :small
                             :prefecture      :tokyo}
                            {:book-profit 8000000M})
          nat   (component facts :jp-nta)
          ent   (component facts :jp-prefecture)
          inh   (component facts :jp-municipality)]
      (is (== 1200000M (:amount (:gross-liability nat))))
      (is (== 123600M  (surtax-amount nat :local-corporate-tax)))
      (is (== 1323600M (:amount (:liability nat))))
      (is (== 352000M  (:amount (:gross-liability ent))))
      (is (== 130240M  (surtax-amount ent :special-corp-enterprise-tax)))
      (is (== 482240M  (:amount (:liability ent))))
      (is (== 84000M   (surtax-amount inh :inhabitant-income-levy)))
      (is (== 70000M   (surtax-amount inh :inhabitant-per-capita-levy)))
      (is (== 154000M  (:amount (:liability inh))))
      (is (== 1959840M (total-liability facts))))))
