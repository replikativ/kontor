(ns kontor.l10n-de.cit-provider-test
  "DE corporate income tax provider tests — ADR-101 substrate's first
   end-to-end consumer. Validates that the statute-as-data path
   (`:parameter` + `:provision` rows + `kontor.statute/apply-provisions`
   fold) computes real DE CIT against published worked examples.

   Two cases:
     §1  BMF / onlinebilanz worked example — GmbH @ €150k profit,
         Hebesatz 380%, NO §8 / §9 adjustments, NO §10 / §8b. Pure
         headline rates. Expected total €43,687.50. The note 108 §2
         reference case.
     §2  Complex case — every adjustment lever fires (§10 non-
         deductibles, §8b participation addback, §8 interest, §8
         rental, §9 real-estate, Soli). Confirms multi-component fold
         + compute-fns + parameter resolution + condition gating all
         work in concert. Hand-computed expected total €83,402.15."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.cit-provider :as de-cit]
            [kontor.l10n-de.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the DE CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the DE CIT provider over `inputs` + a Hebesatz, return the
   `TaxReturnFacts`. Convenience wrapper."
  [hebesatz inputs]
  (let [conn (fresh)]
    (ptp/period-tax-facts
     (de-cit/de-cit-provider {})
     {:entity   :gmbh
      :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
      :db       (d/db conn)
      :as-of    #inst "2025-06-30"
      :tax-unit {:hebesatz hebesatz}
      :inputs   inputs})))

(defn- component
  "Pull one component (`:de-bundesfinanzministerium` for KSt+Soli or
   `:de-municipality` for GewSt) out of a `TaxReturnFacts`."
  [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. BMF worked example — note 108 §2
;; ============================================================================

(deftest bmf-worked-example-gmbh-150k-hebesatz-380
  (testing "GmbH €150k profit, Hebesatz 380%, no add-backs/deducts (note 108 §2)"
    (let [facts (compute 380 {:book-profit 150000M})
          kst   (component facts :de-bundesfinanzministerium)
          gewst (component facts :de-municipality)]
      (testing "KSt: 150000 × 15% = 22500; + Soli 5.5% = 1237.50; liability 23737.50"
        (is (== 150000M  (:amount (:base kst))))
        (is (== 22500M   (:amount (:gross-liability kst))))
        (is (= [[:soli 1237.50M]]
               (mapv (fn [s] [(:code s) (:amount s)]) (:surtaxes kst))))
        (is (== 23737.50M (:amount (:liability kst))))
        (is (nil? (:base-transform kst))
            "no base adjustments fired — :base-transform absent"))
      (testing "GewSt: 150000 × 3.5% × 380% = 19950; no surtaxes"
        (is (== 150000M (:amount (:base gewst))))
        (is (== 19950M  (:amount (:gross-liability gewst))))
        (is (empty? (:surtaxes gewst)))
        (is (== 19950M  (:amount (:liability gewst))))
        (is (nil? (:base-transform gewst))))
      (testing "Total matches BMF/onlinebilanz €43,687.50 — note 108 §2 reference"
        (is (== 43687.50M (total-liability facts)))))))

;; ============================================================================
;; §2. Complex case — every adjustment lever fires
;; ============================================================================

(deftest complex-case-all-adjustments-fire
  (testing "every statute lever fires; Hebesatz 410% (Berlin avg)"
    (let [facts (compute 410 {:book-profit                    200000M
                              :kst-non-deductibles            50000M
                              :participation-gain             20000M
                              :gewst-interest-post-freibetrag 400000M
                              :gewst-rental-expense           40000M
                              :gewst-real-estate-value        50000M})
          kst   (component facts :de-bundesfinanzministerium)
          gewst (component facts :de-municipality)]
      (testing "KSt base: 200000 + §10 50000 + §8b (20000 × 5%) 1000 = 251000"
        (is (== 251000M (:amount (:base kst))))
        (is (= #{:kst-§10 :kst-§8b-addback}
               (set (map :code (:items (:base-transform kst)))))))
      (testing "KSt gross 37650; Soli 2070.75; liability 39720.75"
        (is (== 37650M    (:amount (:gross-liability kst))))
        (is (== 2070.75M  (:amount (first (:surtaxes kst)))))
        (is (== 39720.75M (:amount (:liability kst)))))
      (testing "GewSt base: 200000 + §8a 100000 + §8d 5000 − §9 600 = 304400"
        (is (== 304400M (:amount (:base gewst))))
        (is (= #{:gewst-§8-interest :gewst-§8-rental :gewst-§9-real-estate}
               (set (map :code (:items (:base-transform gewst)))))))
      (testing "GewSt gross: 304400 × 3.5% × 410% = 43681.40"
        (is (== 43681.40M (:amount (:gross-liability gewst))))
        (is (== 43681.40M (:amount (:liability gewst)))))
      (testing "Total: 39720.75 + 43681.40 = 83402.15"
        (is (== 83402.15M (total-liability facts)))))))

;; ============================================================================
;; §3. Substrate-property sanity
;; ============================================================================

(deftest hebesatz-missing-raises
  (testing "GewSt requires :tax-unit :hebesatz; absent → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Hebesatz"
                            (ptp/period-tax-facts
                             (de-cit/de-cit-provider {})
                             {:entity   :gmbh
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {:book-profit 100000M}}))))))

(deftest provisions-applied-recorded-in-provenance
  (testing "every component records the provisions that fired in :provenance"
    (let [facts (compute 380 {:book-profit             100000M
                              :kst-non-deductibles     5000M
                              :gewst-real-estate-value 10000M})
          kst   (component facts :de-bundesfinanzministerium)
          gewst (component facts :de-municipality)]
      (is (= #{"DE-KStG-§10" "DE-SolZG-§4"}
             (set (-> kst :provenance :provisions-applied)))
          "KSt fired §10 + Soli; §8b skipped (no participation-gain)")
      (is (= #{"DE-GewStG-§9-Nr-1"}
             (set (-> gewst :provenance :provisions-applied)))
          "GewSt fired §9 only; no §8 (no interest / rental)"))))

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p :where [?p :parameter/code _]] (d/db conn)))
            n-provs  (count (d/q '[:find ?p :where [?p :provision/code _]] (d/db conn)))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

(deftest functional-commodity-is-eur-on-every-money
  (let [facts (compute 380 {:book-profit 150000M})]
    (is (every? #(= :EUR (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :EUR (:commodity (:liability %)))
                (:components facts)))))
