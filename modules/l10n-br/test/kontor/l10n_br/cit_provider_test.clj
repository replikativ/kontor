(ns kontor.l10n-br.cit-provider-test
  "BR corporate income tax provider tests — ADR-101 substrate's second
   end-to-end CIT consumer (after DE/FR/JP/CA). Validates the
   statute-as-data path (`:parameter` + `:provision` rows + the
   `kontor.statute/apply-provisions` fold) computes real BR CIT
   against blueprint note 162 §3's worked examples.

   Coverage map (note 162 §5.5):
     §1   Lucro Real clean profit (Example A) — headline rates only
     §2   Lucro Real complex (Example B) — JCP + trava-30 % + all addbacks
     §3   Lucro Presumido services (Example C) — 32 % presumption
     §4   Lucro Presumido comércio variant (8 % presumption)
     §5   Bitemporal bank CSLL (Example D) — pre/post Lei 14.183 sunset
     §6   Regime-scope regression (P1-1) — Real vs Presumido isolation
     §7   Simples Nacional short-circuit → nil
     §8   Lucro Arbitrado short-circuit → nil (v1 stub)
     §9   IRRF retido na fonte via :prepaid
     §10  install! is idempotent (parameter + provision counts)
     §11  functional-commodity is :BRL on every Money
     §12  missing :book-profit (Lucro Real) raises
     §13  :provenance records the provisions applied"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.cit-provider :as br-cit]
            [kontor.l10n-br.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

(defn- fresh
  "Fresh test DB with the BR CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the BR CIT provider for one period × tax-profile × inputs.
   Defaults to annual Lucro Real for 2025."
  ([inputs] (compute {:regime :br-lucro-real} inputs))
  ([tax-profile inputs]
   (compute tax-profile inputs
            {:from #inst "2025-01-01" :to #inst "2026-01-01"}
            #inst "2025-06-30"))
  ([tax-profile inputs period as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (br-cit/br-cit-provider {})
      {:entity      :br-co
       :period      period
       :db          (d/db conn)
       :as-of       as-of
       :tax-profile tax-profile
       :inputs      inputs}))))

(defn- component
  "Pull one component (`:br-rfb-irpj` or `:br-rfb-csll`) out of facts."
  [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Lucro Real clean profit — Example A (note 162 §3.A)
;; ============================================================================

(deftest lucro-real-clean-profit-example-a
  (testing "Lucro Real annual, R$ 800k book profit, no adjustments — note 162 §3.A"
    (let [facts (compute {:book-profit 800000M})
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (testing "IRPJ: 800000 × 15 % = 120000; adicional 10 % × (800k − 240k) = 56000; liability 176000"
        (is (== 800000M  (:amount (:base irpj))))
        (is (== 120000M  (:amount (:gross-liability irpj))))
        (is (= [[:irpj-adicional 56000M]]
               (mapv (fn [s] [(:code s) (:amount s)]) (:surtaxes irpj))))
        (is (== 176000M  (:amount (:liability irpj))))
        (is (nil? (:base-transform irpj))
            "no base adjustments fired"))
      (testing "CSLL: 800000 × 9 % = 72000; no surtaxes"
        (is (== 800000M  (:amount (:base csll))))
        (is (== 72000M   (:amount (:gross-liability csll))))
        (is (empty? (:surtaxes csll)))
        (is (== 72000M   (:amount (:liability csll))))
        (is (nil? (:base-transform csll))))
      (testing "Total federal CIT = R$ 248 000 — matches PwC/Contabilizei reference"
        (is (== 248000M (total-liability facts)))))))

;; ============================================================================
;; §2. Lucro Real complex — Example B (note 162 §3.B)
;; ============================================================================

(deftest lucro-real-complex-example-b
  (testing "Lucro Real annual with multas + doações + CSLL-addback + dividendos
            + JCP + trava-30 % on both sides — note 162 §3.B"
    (let [facts (compute {:book-profit                  800000M
                          :multas-indedutiveis          5000M
                          :doacoes-acima-limite         10000M
                          :csll-provisao-periodo        72000M
                          :dividendos-recebidos         20000M
                          :jcp-pago                     40000M
                          :jcp-tjlp-x-pl-cap            60000M
                          :lucro-periodo                800000M
                          :lucros-acumulados            500000M
                          :prejuizo-fiscal-acumulado    300000M
                          :base-negativa-csll-acumulada 200000M})
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (testing "IRPJ fold: 800k + 5k + 10k + 72k − 20k − 40k (JCP cap = min(40k,60k,400k)) =
                827k pré-trava; trava cap = min(300k, 30 % × 827k = 248100) → 248100 deducted →
                base 578900"
        (is (== 578900M (:amount (:base irpj))))
        (is (= [:irpj-multas :irpj-doacoes :irpj-csll-addback
                :irpj-dividendos :irpj-jcp :irpj-compensacao]
               (mapv :code (:items (:base-transform irpj)))))
        (testing "trava-30 % capped at 248100 (not the full 300k carryforward)"
          (is (== 248100M (-> (filter #(= :irpj-compensacao (:code %))
                                      (:items (:base-transform irpj)))
                              first :amount)))))
      (testing "IRPJ liability: 578900 × 15 % = 86835; adicional 10 % × (578900 − 240k) =
                33890; liability 120725"
        (is (== 86835M    (:amount (:gross-liability irpj))))
        (is (== 33890M    (-> irpj :surtaxes first :amount)))
        (is (== 120725M   (:amount (:liability irpj)))))
      (testing "CSLL fold: 800k + 5k + 10k − 20k − 40k = 755k pré-trava; trava cap =
                min(200k, 30 % × 755k = 226500) → 200k deducted → base 555000"
        (is (== 555000M (:amount (:base csll))))
        (is (= [:csll-multas :csll-doacoes :csll-dividendos :csll-jcp :csll-compensacao]
               (mapv :code (:items (:base-transform csll))))))
      (testing "CSLL liability: 555000 × 9 % = 49950"
        (is (== 49950M (:amount (:gross-liability csll))))
        (is (== 49950M (:amount (:liability csll))))
        (is (empty? (:surtaxes csll))))
      (testing "Total: 120725 + 49950 = 170675 — matches note 162 §3.B"
        (is (== 170675M (total-liability facts)))))))

;; ============================================================================
;; §3. Lucro Presumido services (Example C, note 162 §3.C)
;; ============================================================================

(deftest lucro-presumido-services-example-c
  (testing "Lucro Presumido trimestral Q4, serviços 32 % presunção — note 162 §3.C"
    (let [facts (compute {:regime :br-lucro-presumido :atividade-codigo :servicos}
                         {:receita-bruta      3000000M
                          :ganho-capital      50000M
                          :receita-financeira 20000M}
                         {:from #inst "2025-10-01" :to #inst "2026-01-01"}
                         #inst "2025-12-15")
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (testing "IRPJ base: 3M × 32 % + 50k + 20k = 1 030 000"
        (is (== 1030000M (:amount (:base irpj))))
        (is (nil? (:base-transform irpj))
            "no LALUR adjustments under Presumido"))
      (testing "IRPJ: 1030k × 15 % = 154500; adicional 10 % × (1030k − 60k) = 97000;
                liability 251500"
        (is (== 154500M (:amount (:gross-liability irpj))))
        (is (== 97000M  (-> irpj :surtaxes first :amount)))
        (is (== 251500M (:amount (:liability irpj)))))
      (testing "CSLL base: same 3M × 32 % + 50k + 20k = 1 030 000 (serviços CSLL = 32 %)"
        (is (== 1030000M (:amount (:base csll))))
        (is (nil? (:base-transform csll))))
      (testing "CSLL: 1030k × 9 % = 92700; no surtax"
        (is (== 92700M (:amount (:gross-liability csll))))
        (is (== 92700M (:amount (:liability csll))))
        (is (empty? (:surtaxes csll))))
      (testing "Total Q4 federal: 251500 + 92700 = 344200 — matches note 162 §3.C"
        (is (== 344200M (total-liability facts)))))))

;; ============================================================================
;; §4. Lucro Presumido comércio variant (8 % IRPJ / 12 % CSLL)
;; ============================================================================

(deftest lucro-presumido-comercio-variant
  (testing "Lucro Presumido trimestral, comércio 8 % IRPJ / 12 % CSLL — divergent
            ratios across IRPJ and CSLL"
    (let [facts (compute {:regime :br-lucro-presumido :atividade-codigo :comercio}
                         {:receita-bruta 1000000M}
                         {:from #inst "2025-07-01" :to #inst "2025-10-01"}
                         #inst "2025-09-15")
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (testing "IRPJ comércio: 1M × 8 % = 80000 base"
        (is (== 80000M (:amount (:base irpj))))
        (is (== 12000M (:amount (:gross-liability irpj))))
        (testing "adicional: 80k < 60k threshold (trimestral = R$ 20k × 3) — actually 80k > 60k → 10 % × (80k − 60k) = 2000"
          (is (== 2000M  (-> irpj :surtaxes first :amount)))
          (is (== 14000M (:amount (:liability irpj))))))
      (testing "CSLL comércio: 1M × 12 % = 120000 base (different from IRPJ 8 %)"
        (is (== 120000M (:amount (:base csll))))
        (is (== 10800M  (:amount (:gross-liability csll))))
        (is (== 10800M  (:amount (:liability csll))))))))

;; ============================================================================
;; §5. Bitemporal bank CSLL — Lei 14.183/2021 sunset (Example D, note 162 §3.D)
;; ============================================================================

(deftest bitemporal-bank-csll-lei-14183-sunset
  (testing "Same R$ 10M bank book profit, as-of pre vs post Lei 14.183 sunset
            (2021-09-01 → 2024-12-31 = 20 %; from 2025-01-01 = 15 %)"
    (let [conn (fresh)
          run-asof (fn [as-of]
                     (ptp/period-tax-facts
                      (br-cit/br-cit-provider {})
                      {:entity :bank
                       :period {:from #inst "2023-01-01" :to #inst "2024-01-01"}
                       :db (d/db conn)
                       :as-of as-of
                       :tax-profile {:regime :br-lucro-real :financial? true}
                       :inputs {:book-profit 10000000M}}))
          csll-at (fn [as-of] (component (run-asof as-of) :br-rfb-csll))]
      (testing "2023 as-of → 20 % bank rate (Lei 14.183/2021 in force)"
        (is (== 0.20M (:rate (:schedule (csll-at #inst "2023-06-30")))))
        (is (== 2000000M (:amount (:liability (csll-at #inst "2023-06-30"))))))
      (testing "2025 as-of → 15 % bank rate (sunset)"
        (is (== 0.15M (:rate (:schedule (csll-at #inst "2025-06-30")))))
        (is (== 1500000M (:amount (:liability (csll-at #inst "2025-06-30"))))))
      (testing "Non-bank CSLL stays at 9 % both years"
        (let [non-bank #(component
                         (ptp/period-tax-facts
                          (br-cit/br-cit-provider {})
                          {:entity :br-co
                           :period {:from #inst "2023-01-01" :to #inst "2024-01-01"}
                           :db (d/db conn)
                           :as-of %
                           :tax-profile {:regime :br-lucro-real}    ; :financial? absent
                           :inputs {:book-profit 10000000M}})
                         :br-rfb-csll)]
          (is (== 0.09M (:rate (:schedule (non-bank #inst "2023-06-30")))))
          (is (== 0.09M (:rate (:schedule (non-bank #inst "2025-06-30"))))))))))

;; ============================================================================
;; §6. Regime-scope regression (note 162 §4 P1-1)
;; ============================================================================

(deftest regime-scope-isolation-p1-1
  (testing "Real provisions do NOT fire under Presumido and vice versa.
            First substrate test of `:provision/regime` filter end-to-end —
            BR is the lead exerciser (note 162 §4 P1-1)."
    (let [conn (fresh)
          db   (d/db conn)
          base-ctx {:component :irpj
                    :inputs {:multas-indedutiveis 1M
                             :doacoes-acima-limite 1M
                             :csll-provisao-periodo 1M
                             :jcp-pago 1M}
                    :as-of #inst "2025-06-30"}]
      (testing "Real-elected → only Real provisions"
        (let [codes (->> (statute/applicable-provisions
                          db {:concept :base-transform-add :jurisdiction :br
                              :as-of #inst "2025-06-30" :regime :br-lucro-real}
                          base-ctx)
                         (map :provision/code) set)]
          (is (contains? codes "BR-IRPJ-Real-multas-indedutiveis"))
          (is (contains? codes "BR-IRPJ-Real-csll-addback"))))
      (testing "Presumido-elected → NO Real provisions fire"
        (let [codes (->> (statute/applicable-provisions
                          db {:concept :base-transform-add :jurisdiction :br
                              :as-of #inst "2025-06-30" :regime :br-lucro-presumido}
                          base-ctx)
                         (map :provision/code) set)]
          (is (empty? codes)
              "no BR-IRPJ-Real-* provisions should fire under Presumido")))
      (testing "IRPJ adicional surtax is regime-AGNOSTIC — fires under all regimes"
        (doseq [regime [:br-lucro-real :br-lucro-presumido]]
          (let [codes (->> (statute/applicable-provisions
                            db {:concept :surtax :jurisdiction :br
                                :as-of #inst "2025-06-30" :regime regime}
                            base-ctx)
                           (map :provision/code) set)]
            (is (contains? codes "BR-IRPJ-adicional-10pct")
                (str "adicional must fire under " regime))))))))

;; ============================================================================
;; §7. Simples Nacional short-circuit
;; ============================================================================

(deftest simples-nacional-short-circuits-to-nil
  (testing "Simples Nacional is NOT a CIT regime in the ADR-099 sense
            (note 162 §1.2) — provider returns nil"
    (is (nil? (compute {:regime :br-simples-nacional}
                       {:book-profit 100000M})))))

;; ============================================================================
;; §8. Lucro Arbitrado short-circuit (v1)
;; ============================================================================

(deftest lucro-arbitrado-short-circuits-to-nil-v1
  (testing "Lucro Arbitrado is parameter-only in v1 (note 162 §1.2) —
            provider returns nil; v1.1 will turn this on"
    (is (nil? (compute {:regime :br-lucro-arbitrado}
                       {:book-profit 100000M})))))

;; ============================================================================
;; §9. IRRF retido na fonte → :prepaid on the component (note 162 §5.4)
;; ============================================================================

(deftest irrf-retido-feeds-prepaid
  (testing ":inputs :irrf-retido-irpj / :irrf-retido-csll surface on
            :prepaid for each component (note 162 §5.4)"
    (let [facts (compute {:book-profit       800000M
                          :irrf-retido-irpj  4500M
                          :irrf-retido-csll  3000M})
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (is (= {:amount 4500M :commodity :BRL} (:prepaid irpj)))
      (is (= {:amount 3000M :commodity :BRL} (:prepaid csll))))))

;; ============================================================================
;; §10. install! is idempotent
;; ============================================================================

(deftest install-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [db (d/db conn)
            n-params (count (d/q '[:find ?p :where [?p :parameter/code _]
                                   [?p :parameter/jurisdiction :br]]
                                 db))
            n-provs  (count (d/q '[:find ?p :where [?p :provision/code _]
                                   [?p :provision/jurisdiction :br]]
                                 db))
            n-regimes (count (d/q '[:find ?r :where [?r :regime/code _]
                                    [?r :regime/jurisdiction :br]]
                                  db))]
        ;; Final counts (note 162 blueprint estimated 18+14; the
        ;; IRPJ-Real dividendos-excluidos provision was implicit in
        ;; the blueprint's listing — explicitly carried here, giving
        ;; 15 provisions. The blueprint's 18-parameter count included
        ;; one phantom; actual is 17.
        (is (= 17 n-params))
        (is (= 15 n-provs))
        (is (= 3  n-regimes))))))

;; ============================================================================
;; §11. functional-commodity is :BRL on every Money
;; ============================================================================

(deftest functional-commodity-is-brl-on-every-money
  (let [facts (compute {:book-profit 800000M})]
    (is (= :BRL (:functional-commodity facts)))
    (is (every? #(= :BRL (:commodity (:base %))) (:components facts)))
    (is (every? #(= :BRL (:commodity (:liability %))) (:components facts)))))

;; ============================================================================
;; §12. Missing :book-profit / :receita-bruta raises
;; ============================================================================

(deftest missing-required-input-raises
  (testing "Lucro Real without :book-profit raises"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":book-profit"
                          (compute {:regime :br-lucro-real} {}))))
  (testing "Lucro Presumido without :receita-bruta raises"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":receita-bruta"
                          (compute {:regime :br-lucro-presumido :atividade-codigo :servicos} {}))))
  (testing "Lucro Presumido without :atividade-codigo raises"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":atividade-codigo"
                          (compute {:regime :br-lucro-presumido}
                                   {:receita-bruta 1000000M})))))

;; ============================================================================
;; §13. :provenance records the provisions applied
;; ============================================================================

(deftest provenance-records-applied-provisions
  (testing "Each component records every fired provision in :provenance"
    (let [facts (compute {:book-profit         800000M
                          :multas-indedutiveis 5000M
                          :jcp-pago            10000M
                          :jcp-tjlp-x-pl-cap   20000M
                          :lucro-periodo       800000M})
          irpj  (component facts :br-rfb-irpj)
          csll  (component facts :br-rfb-csll)]
      (is (= #{"BR-IRPJ-Real-multas-indedutiveis"
               "BR-IRPJ-Real-jcp-deduction"
               "BR-IRPJ-adicional-10pct"}
             (set (-> irpj :provenance :provisions-applied)))
          "IRPJ fired multas + JCP + adicional (no doações/dividendos/etc — absent inputs)")
      (is (= #{"BR-CSLL-Real-multas-indedutiveis"
               "BR-CSLL-Real-jcp-deduction"}
             (set (-> csll :provenance :provisions-applied)))
          "CSLL fired multas + JCP only (no adicional on CSLL, no IRPJ-only deducts)")
      (is (= :br-cit (-> irpj :provenance :provider-id)))
      (is (= "IRPJ — Lei 9.249/95 + Lei 9.430/96"
             (-> irpj :provenance :statute))))))
