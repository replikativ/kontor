(ns kontor.regression.cit-group-b-test
  "Regression suite — corporate income tax, group B: CN / MX / AT / AU / IN / BR.

   Each jurisdiction's l10n `cit-provider` is run against its module test's
   AUTHORITY-published worked example (asserted to the cent), plus one extra
   challenging scenario that combines statute levers in a new way (expected
   values hand-computed from the same statute the module test already
   validated). This locks in the ADR-101 statute-as-data path
   (`:parameter` + `:provision` rows + `kontor.tax.statute/apply-provisions`)
   for the six group-B jurisdictions.

   Method mirrors each module's own `cit-provider-test`: fresh in-memory DB,
   install the CGT/investment statute (ships the shared rate parameter) then
   the CIT statute, then `kontor.tax.period-tax-provider/period-tax-facts`.

   A FAILURE here is a real regression in the corresponding l10n module ⇒
   tag the deftest ^:kaocha/pending with a PENDING(NEW) comment naming it."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax.period-tax-provider :as ptp]
            ;; CN
            [kontor.l10n-cn.cgt-statute :as cn-cgt]
            [kontor.l10n-cn.cit-provider :as cn-cit]
            [kontor.l10n-cn.cit-statute :as cn-cit-statute]
            ;; MX
            [kontor.l10n-mx.cgt-statute :as mx-cgt]
            [kontor.l10n-mx.cit-provider :as mx-cit]
            [kontor.l10n-mx.cit-statute :as mx-cit-statute]
            ;; AT
            [kontor.l10n-at.cgt-statute :as at-cgt]
            [kontor.l10n-at.cit-provider :as at-cit]
            [kontor.l10n-at.cit-statute :as at-cit-statute]
            ;; AU
            [kontor.l10n-au.cit-provider :as au-cit]
            [kontor.l10n-au.cit-statute :as au-cit-statute]
            [kontor.l10n-au.investment-income-statute :as au-inv-statute]
            ;; IN
            [kontor.l10n-in.cit-provider :as in-cit]
            [kontor.l10n-in.cit-statute :as in-cit-statute]
            ;; BR
            [kontor.l10n-br.cit-provider :as br-cit]
            [kontor.l10n-br.cit-statute :as br-cit-statute]))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

(defn- first-component [facts]
  (first (:components facts)))

;; ============================================================================
;; CN — China EIT (25 % standard / 15 % HNTE / statute-as-data adjustments)
;; ============================================================================

(defn- cn-compute
  ([tax-unit inputs] (cn-compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (cn-cgt/install! conn)
     (cn-cit-statute/install! conn)
     (ptp/period-tax-facts
      (cn-cit/cn-cit-provider {})
      {:entity   :cn-llc
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(deftest cn-eit-worked-example-plus-hnte-with-addback
  (testing "AUTHORITY (module §1) — standard CN-LLC @ ¥1M book-profit → 25 % → ¥250 000"
    ;; Source: STA published worked example (chinatax.gov.cn), per module test.
    (let [facts (cn-compute {} {:book-profit 1000000M})
          c     (first-component facts)]
      (is (== 0.25M (:rate (:schedule c))))
      (is (== 1000000M (:amount (:base c))))
      (is (== 250000M (:amount (:liability c))))
      (is (== 250000M (total-liability facts)))
      (is (= :CNY (:commodity (:liability c))))))
  (testing "EXTRA (hand-computed) — HNTE @ 15 % on (book ¥2M + ¥500k §10 non-deductibles)"
    ;; HNTE (§28 ¶2) swaps the schedule to 15 %; §10 non-deductibles add to
    ;; the base BEFORE the rate applies: base = 2 000 000 + 500 000 = 2 500 000;
    ;; liability = 2 500 000 × 15 % = 375 000.
    (let [facts (cn-compute {:hnte? true}
                            {:book-profit        2000000M
                             :cn-non-deductibles  500000M})
          c     (first-component facts)]
      (is (== 0.15M (:rate (:schedule c))) "HNTE schedule-override fired")
      (is (== 2500000M (:amount (:base c))) "non-deductibles added to base")
      (is (== 375000M (:amount (:liability c))))
      (let [applied (set (-> c :provenance :provisions-applied))]
        (is (contains? applied "CN-EITLaw-§28-¶2-hnte"))
        (is (contains? applied "CN-EITLaw-§10-non-deductibles"))))))

;; ============================================================================
;; MX — ISR personas morales (flat 30 %, LISR adjustments)
;; ============================================================================

(defn- mx-compute
  ([tax-unit inputs] (mx-compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (mx-cgt/install! conn)
     (mx-cit-statute/install! conn)
     (ptp/period-tax-facts
      (mx-cit/mx-cit-provider {})
      {:entity   :sa-de-cv
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(deftest mx-isr-worked-example-plus-ptu-and-cgt
  (testing "AUTHORITY (module §1) — clean SA de CV @ MX$2M utilidad fiscal → 30 % → MX$600 000"
    ;; Source: SAT Guía del Contribuyente PM 2025 worked example, per module test.
    (let [facts (mx-compute {} {:book-profit 2000000M})
          c     (first-component facts)]
      (is (== 0.30M (:rate (:schedule c))))
      (is (== 2000000M (:amount (:base c))))
      (is (== 600000M (:amount (:liability c))))
      (is (== 600000M (total-liability facts)))
      (is (= :MXN (:commodity (:liability c))))))
  (testing "EXTRA (hand-computed) — PTU deduction + CGT fold together"
    ;; LISR art. 9 fr. I PTU deduction (subtract) and art. 22 CGT fold (add):
    ;; base = 2 000 000 − 200 000 + 500 000 = 2 300 000;
    ;; ISR = 2 300 000 × 30 % = 690 000.
    (let [facts (mx-compute {} {:book-profit             2000000M
                                :ptu-deductible           200000M
                                :cgt-cit-base-additions   500000M})
          c     (first-component facts)]
      (is (== 2300000M (:amount (:base c))))
      (is (== 690000M (:amount (:liability c))))
      (let [applied (set (-> c :provenance :provisions-applied))]
        (is (contains? applied "MX-LISR-art-9-fr-I-PTU"))
        (is (contains? applied "MX-LISR-art-22-cgt-cit-base-additions"))))))

;; ============================================================================
;; AT — KöSt (flat 23 % post-2024, Mindest-KöSt floor via compose-greater-of)
;; ============================================================================

(defn- at-compute
  ([tax-unit inputs] (at-compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (at-cgt/install! conn)
     (at-cit-statute/install! conn)
     (ptp/period-tax-facts
      (at-cit/at-cit-provider {})
      {:entity   :gmbh
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(deftest at-koest-worked-example-loss-floor-and-deduction
  (testing "AUTHORITY (module §1) — GmbH @ €100k → flat 23 % → €23 000 (regular prevails)"
    ;; Source: §22 KStG 2025 Worldwide Tax Summary; WKO Aktuelle Werte 2026.
    (let [facts (at-compute {} {:book-profit 100000M})
          c     (first-component facts)]
      (is (== 0.23M (:rate (:schedule c))))
      (is (== 23000M (:amount (:liability c))))
      (is (= :a (:prevailed (:composition c))) "KSt €23 000 > Mindest-KöSt €500")
      (is (= :EUR (:commodity (:liability c))))))
  (testing "AUTHORITY (module §2) — loss → Mindest-KöSt €500 floor prevails"
    ;; WKO Aktuelle Werte 2026: post-2024 GmbH minimum €500.
    (let [facts (at-compute {} {:book-profit -5000M})
          c     (first-component facts)]
      (is (= :b (:prevailed (:composition c))))
      (is (== 500M (:amount (:liability c))))))
  (testing "EXTRA (hand-computed) — §10 deduction lane, regular arm stays above the floor"
    ;; §10 KStG exempt dividends deduct from base:
    ;; base = 100 000 − 50 000 = 50 000; KSt = 50 000 × 23 % = 11 500 (> €500 floor).
    (let [facts (at-compute {} {:book-profit                100000M
                                :cgt-cit-base-deductions     50000M})
          c     (first-component facts)]
      (is (== 50000M (:amount (:base c))))
      (is (== 11500M (:amount (:liability c))))
      (is (= :a (:prevailed (:composition c))))
      (is (contains? (set (-> c :provenance :provisions-applied))
                     "AT-KStG-§10-cit-base-deductions")))))

;; ============================================================================
;; AU — company tax (30 % standard / 25 % BRE via :schedule-override)
;; ============================================================================

(defn- au-compute
  ([tax-unit inputs] (au-compute tax-unit inputs #inst "2025-06-30"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (au-inv-statute/install! conn)
     (au-cit-statute/install! conn)
     (ptp/period-tax-facts
      (au-cit/au-cit-provider {})
      {:entity   :pty-ltd
       :period   {:from #inst "2024-07-01" :to #inst "2025-07-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(deftest au-cit-worked-examples-plus-bre-cgt-fold
  (testing "AUTHORITY (module §1) — standard company @ $200k → 30 % → $60 000"
    ;; Source: ATO changes-to-company-tax-rates worked example.
    (let [facts (au-compute {:base-rate-entity? false} {:book-profit 200000M})
          c     (first-component facts)]
      (is (== 0.30M (:rate (:schedule c))))
      (is (== 60000M (:amount (:liability c))))
      (is (= :AUD (:commodity (:liability c))))))
  (testing "AUTHORITY (module §2) — BRE company @ $200k → 25 % override → $50 000"
    ;; Source: ATO + LCR 2019/5.
    (let [facts (au-compute {:base-rate-entity? true} {:book-profit 200000M})
          c     (first-component facts)]
      (is (== 0.25M (:rate (:schedule c))))
      (is (= :au-bre-rate (:regime c)))
      (is (== 50000M (:amount (:liability c))))))
  (testing "EXTRA (hand-computed) — BRE rate applied over a CGT-folded base"
    ;; Companies get no Div 115 discount; the full gain folds into the base,
    ;; then the BRE 25 % override applies: base = 200 000 + 50 000 = 250 000;
    ;; CIT = 250 000 × 25 % = 62 500.
    (let [facts (au-compute {:base-rate-entity? true}
                            {:book-profit                200000M
                             :au-cgt-cit-base-additions   50000M})
          c     (first-component facts)]
      (is (== 250000M (:amount (:base c))))
      (is (== 0.25M (:rate (:schedule c))) "BRE override still applies over folded base")
      (is (== 62500M (:amount (:liability c))))
      (let [applied (set (-> c :provenance :provisions-applied))]
        (is (contains? applied "AU-ITRA-1986-§23AA-bre-schedule"))
        (is (contains? applied "AU-cgt-cit-base-additions"))))))

;; ============================================================================
;; IN — CIT (regular vs MAT via compose-greater-of, surcharge + 4 % cess)
;; ============================================================================

(defn- in-compute
  ([tax-unit inputs] (in-compute tax-unit inputs #inst "2025-09-30"))
  ([tax-unit inputs as-of]
   (let [conn (core/create-test-db)]
     (in-cit-statute/install! conn)
     (ptp/period-tax-facts
      (in-cit/in-cit-provider {})
      {:entity   :pvt-ltd
       :period   {:from #inst "2025-04-01" :to #inst "2026-04-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(deftest in-cit-worked-examples-regular-and-mat
  (testing "AUTHORITY (module §1) — standard, ₹50L taxable / ₹60L book → regular ₹13,00,000"
    ;; Regular: 50L×25% + 4% cess = 13,00,000; MAT: 60L×15% + cess = 9,36,000;
    ;; max ⇒ regular prevails. Cross-checked against authority worked example.
    (let [facts (in-compute {:regime :in-cit-standard :turnover-band :small}
                            {:taxable-income     5000000M
                             :book-profit-115jb  6000000M})
          c     (first-component facts)]
      (is (= :corporate-income-tax (:kind c)))
      (is (== 1250000M (:amount (:gross-liability c))))
      (is (== 1300000M (:amount (:liability c))))
      (is (= :a (-> c :composition :prevailed)) "regular prevails")
      (is (= :INR (:commodity (:liability c))))))
  (testing "AUTHORITY (module §3) — MAT-binding: ₹50L taxable / ₹2cr book → MAT ₹33,38,400"
    ;; MAT: 2cr×15% = 30L; +7% surcharge (book>₹1cr) = 32,10,000; +4% cess = 33,38,400.
    ;; max(13,00,000, 33,38,400) ⇒ MAT prevails; §115JAA credit 20,38,400 recorded.
    (let [facts (in-compute {:regime :in-cit-standard :turnover-band :small}
                            {:taxable-income     5000000M
                             :book-profit-115jb 20000000M})
          c     (first-component facts)]
      (is (= :minimum-tax (:kind c)))
      (is (== 3000000M (:amount (:gross-liability c))))
      (is (== 3338400M (:amount (:liability c))))
      (is (= :b (-> c :composition :prevailed)) "MAT prevails")
      (is (== 2038400M (:amount (-> c :provenance :mat-credit-carry-forward)))
          "§115JAA MAT credit carry-forward = 33,38,400 − 13,00,000")))
  (testing "EXTRA (module §2 authority) — §115BAA @ ₹5cr → 25.168 % effective ₹1,25,84,000"
    ;; 22% + flat 10% surcharge + 4% cess; MAT gated OFF by §115JB(5A).
    (let [facts (in-compute {:regime :in-cit-115BAA}
                            {:taxable-income    50000000M
                             :book-profit-115jb 70000000M})
          c     (first-component facts)]
      (is (== 0.22M (:rate (:schedule c))))
      (is (nil? (:composed-of c)) "no MAT composition under §115BAA")
      (is (== 12584000M (:amount (:liability c)))))))

;; ============================================================================
;; BR — IRPJ + CSLL (Lucro Real / Lucro Presumido)
;; ============================================================================

(defn- br-compute
  ([tax-profile inputs]
   (br-compute tax-profile inputs
               {:from #inst "2025-01-01" :to #inst "2026-01-01"}
               #inst "2025-06-30"
               :br-co))
  ([tax-profile inputs period as-of entity]
   (let [conn (core/create-test-db)]
     (br-cit-statute/install! conn)
     (ptp/period-tax-facts
      (br-cit/br-cit-provider {})
      {:entity      entity
       :period      period
       :db          (d/db conn)
       :as-of       as-of
       :tax-profile tax-profile
       :inputs      inputs}))))

(defn- br-component [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(deftest br-cit-worked-examples-real-and-presumido
  (testing "AUTHORITY (module §1) — Lucro Real @ R$800k → IRPJ R$176 000 + CSLL R$72 000 = R$248 000"
    ;; IRPJ: 800k×15% = 120k; adicional 10%×(800k−240k) = 56k ⇒ 176k. CSLL: 800k×9% = 72k.
    (let [facts (br-compute {:regime :br-lucro-real} {:book-profit 800000M})
          irpj  (br-component facts :br-rfb-irpj)
          csll  (br-component facts :br-rfb-csll)]
      (is (== 120000M (:amount (:gross-liability irpj))))
      (is (== 56000M (-> irpj :surtaxes first :amount)))
      (is (== 176000M (:amount (:liability irpj))))
      (is (== 72000M (:amount (:liability csll))))
      (is (== 248000M (total-liability facts)))
      (is (= :BRL (:commodity (:liability irpj))))))
  (testing "AUTHORITY (module §3) — Lucro Presumido serviços Q4 @ R$3M → R$344 200"
    ;; Presunção 32 %: base = 3M×32% + 50k + 20k = 1 030 000 for both taxes.
    ;; IRPJ 251 500 (154 500 + adicional 97 000) + CSLL 92 700 = 344 200.
    (let [facts (br-compute {:regime :br-lucro-presumido :atividade-codigo :servicos}
                            {:receita-bruta      3000000M
                             :ganho-capital       50000M
                             :receita-financeira  20000M}
                            {:from #inst "2025-10-01" :to #inst "2026-01-01"}
                            #inst "2025-12-15"
                            :br-co)
          irpj  (br-component facts :br-rfb-irpj)
          csll  (br-component facts :br-rfb-csll)]
      (is (== 1030000M (:amount (:base irpj))))
      (is (== 251500M (:amount (:liability irpj))))
      (is (== 92700M (:amount (:liability csll))))
      (is (== 344200M (total-liability facts)))))
  (testing "EXTRA (hand-computed) — Lucro Presumido comércio R$1M (8 % IRPJ / 12 % CSLL split)"
    ;; comércio: IRPJ base 1M×8% = 80k → 12k + adicional 10%×(80k−60k trimestral)=2k ⇒ 14k.
    ;; CSLL base 1M×12% = 120k → 120k×9% = 10 800.
    (let [facts (br-compute {:regime :br-lucro-presumido :atividade-codigo :comercio}
                            {:receita-bruta 1000000M}
                            {:from #inst "2025-07-01" :to #inst "2025-10-01"}
                            #inst "2025-09-15"
                            :br-co)
          irpj  (br-component facts :br-rfb-irpj)
          csll  (br-component facts :br-rfb-csll)]
      (is (== 80000M (:amount (:base irpj))))
      (is (== 14000M (:amount (:liability irpj))))
      (is (== 120000M (:amount (:base csll))))
      (is (== 10800M (:amount (:liability csll)))))))
