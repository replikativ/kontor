(ns kontor.l10n-mx.cit-provider-test
  "MX corporate income tax provider tests — ADR-101 substrate's MX
   consumer (ADR-104 template, applied to Mexico). Validates that the
   statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.tax.statute/apply-provisions` fold) computes real MX ISR
   personas morales against published worked examples.

   Worked examples cited:

   - **§1 Clean SA de CV @ MX$2 M** — flat 30 % × MX$ 2 000 000 =
     MX$ 600 000. Source: SAT Guía del Contribuyente PM 2025 and
     authority-published worked example.
   - **§2 SA de CV with PTU deduction** — MX$ 2 M base − MX$ 200 000
     PTU = MX$ 1.8 M; ISR MX$ 540 000. Source: LISR art. 9 fr. I.
   - **§3 CGT cit-base-additions** — MX$ 1 M book-profit +
     MX$ 500 000 CGT fold = MX$ 1.5 M; ISR MX$ 450 000. Tests
     provision `MX-LISR-art-22-cgt-cit-base-additions` fires.
   - **§4 Optional non-deductibles** — provenance records the
     optional add-back provision.
   - **§5 Install idempotence** — substrate property.
   - **§6 Provenance** — `:provisions-applied` records the codes.
   - **§7 Missing book-profit** — ex-info with diagnostic.
   - **§8 Cross-statute rate** — confirms the CIT provider reads
     `MX.CGT.art-9.pm-rate` shipped by CGT statute."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.cgt-statute :as cgt-statute]
            [kontor.l10n-mx.cit-provider :as mx-cit]
            [kontor.l10n-mx.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the MX CGT + CIT statutes installed. CGT ships
   `MX.CGT.art-9.pm-rate` (the 30 % CIT rate); CIT consumes it. Order
   matches `kontor.l10n-mx.preset/install-all!`."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the MX CIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (mx-cit/mx-cit-provider {})
      {:entity   :sa-de-cv
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- isr-pm-component
  "Pull the ISR personas morales component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Clean SA de CV @ MX$2 M utilidad fiscal
;; ============================================================================

(deftest sa-de-cv-clean-2m-utilidad-fiscal
  (testing "SA de CV, utilidad fiscal MX$ 2 000 000, no PTU, no adjustments
            → ISR MX$ 600 000"
    (let [facts (compute {} {:book-profit 2000000M})
          c     (isr-pm-component facts)]
      (testing "base = book-profit (no adjustments fire)"
        (is (== 2000000M (:amount (:base c)))))
      (testing "schedule = flat 30 %"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.30M (:rate (:schedule c)))))
      (testing "gross-liability = MX$ 600 000"
        (is (== 600000M (:amount (:gross-liability c)))))
      (testing "liability = MX$ 600 000"
        (is (== 600000M (:amount (:liability c))))
        (is (== 600000M (total-liability facts))))
      (testing "no base-transform recorded (no provisions fired)"
        (is (nil? (:base-transform c)))))))

;; ============================================================================
;; §2. SA de CV with PTU deduction (year 2+ company)
;; ============================================================================

(deftest sa-de-cv-with-ptu-deduction
  (testing "book-profit MX$ 2 M − MX$ 200 000 PTU → base MX$ 1.8 M; ISR MX$ 540 000"
    (let [facts (compute {} {:book-profit      2000000M
                             :ptu-deductible    200000M})
          c     (isr-pm-component facts)]
      (testing "base = 2 000 000 − 200 000 = 1 800 000"
        (is (== 1800000M (:amount (:base c)))))
      (testing "base-transform records the PTU deduction"
        (let [items (:items (:base-transform c))]
          (is (= 1 (count items)))
          (is (= :mx-ptu-deduction (:code (first items))))
          (is (== 200000M (:amount (first items))))))
      (testing "liability = 1 800 000 × 30 % = 540 000"
        (is (== 540000M (:amount (:liability c)))))
      (testing "provenance records the PTU provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-art-9-fr-I-PTU"))))))

;; ============================================================================
;; §3. CGT cit-base-additions flows through
;; ============================================================================

(deftest cgt-cit-base-additions-flows-through
  (testing "book-profit MX$ 1 M + :cgt-cit-base-additions MX$ 500 000
            → base MX$ 1.5 M; ISR MX$ 450 000"
    (let [facts (compute {} {:book-profit                1000000M
                             :cgt-cit-base-additions      500000M})
          c     (isr-pm-component facts)]
      (testing "base = 1 000 000 + 500 000 = 1 500 000"
        (is (== 1500000M (:amount (:base c)))))
      (testing "liability = 1 500 000 × 30 % = 450 000"
        (is (== 450000M (:amount (:liability c)))))
      (testing "provenance records the CGT fold provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-art-22-cgt-cit-base-additions"))))))

;; ============================================================================
;; §4. Optional non-deductibles add-back traces in provenance
;; ============================================================================

(deftest mx-non-deductible-add-back-traces-in-provenance
  (testing "the optional non-deductibles provision fires when
            `:inputs :mx-non-deductible-add` is supplied"
    (let [facts (compute {} {:book-profit             1000000M
                             :mx-non-deductible-add    50000M})
          c     (isr-pm-component facts)]
      (testing "base = 1 000 000 + 50 000 = 1 050 000"
        (is (== 1050000M (:amount (:base c)))))
      (testing "liability = 1 050 000 × 30 % = 315 000"
        (is (== 315000M (:amount (:liability c)))))
      (testing "provenance records the non-deductibles provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "MX-LISR-arts-28-32-non-deductible"))))))

;; ============================================================================
;; §5. Substrate property — install idempotence
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cgt-statute/install! conn)
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "MX.CIT.")]]
                                 (d/db conn) :mx))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "MX-LISR-art-9-fr-I")]]
                                 (d/db conn) :mx))]
        (is (= (count cit-statute/parameters) n-params))
        ;; the PTU provision is the only MX-LISR-art-9-fr-I-* code
        (is (= 1 n-provs))))))

;; ============================================================================
;; §6. Substrate property — provenance trail for clean run
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "clean SA de CV case fires NO provisions (no driver facts present)"
    (let [facts (compute {} {:book-profit 2000000M})
          c     (isr-pm-component facts)]
      (is (empty? (-> c :provenance :provisions-applied))
          "no driver facts → no provisions fire — the silent-no-op posture")
      (is (= :mx-cit (-> c :provenance :provider-id)))
      (is (re-find #"LISR" (-> c :provenance :statute))))))

;; ============================================================================
;; §7. Substrate property — missing book-profit raises
;; ============================================================================

(deftest missing-book-profit-raises
  (testing "absent :inputs :book-profit → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (mx-cit/mx-cit-provider {})
                             {:entity   :sa-de-cv
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; §8. Cross-statute parameter sharing — CGT ships the 30 % rate
;; ============================================================================

(deftest flat-30-pct-rate-via-existing-cgt-statute-parameter
  (testing "the CIT provider reads `MX.CGT.art-9.pm-rate` shipped by CGT statute"
    (let [facts (compute {} {:book-profit 100000M})
          c     (isr-pm-component facts)]
      (is (== 0.30M (:rate (:schedule c)))
          "the CIT schedule rate comes from cgt-statute's parameter")
      (is (== 30000M (:amount (:liability c)))
          "100 000 × 30 % = 30 000 — the cross-statute rate is read correctly"))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-mxn-on-every-money
  (let [facts (compute {} {:book-profit 2000000M})]
    (is (every? #(= :MXN (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :MXN (:commodity (:liability %)))
                (:components facts)))))
