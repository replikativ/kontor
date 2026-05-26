(ns kontor.l10n-ca.cit-provider-test
  "CA corporate income tax provider tests — ADR-107. Validates that
   the statute-as-data path (`:parameter` + `:provision` rows +
   `kontor.statute/apply-provisions` fold) computes real CA T2
   against the published CRA / PwC worked example. Sibling test to
   `kontor.l10n-de.cit-provider-test`; same structure, CA-specific
   content.

   Cases:
     §1  CRA / PwC Canada Corporate Tax Summaries worked example
         (research note 111 §2) — Acme Widgets Co., a CCPC with
         CAD 620,000 taxable income earned in Ontario + Alberta
         (Sch-5 allocation 65/35). Federal SBD-pool computation;
         per-province SBD-pool allocation; the small-business
         cascade in three components.
     §2  Non-CCPC standard case — flat 15% federal + flat 11.5% ON
         on the full taxable income.
     §3  Simple two-province ON/BC CCPC — easy round numbers for
         confidence in the per-province SBD-pool allocation.
     §4  Idempotent install! check (parameters + provisions are
         identity-attr backed).
     §5  Provenance audit trail — every component records the
         provisions that fired.

   Sources:
     - https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income
     - https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/you-have-complete-schedule-5.html
     - https://www.taxtips.ca/smallbusiness/corporatetax/corporate-tax-rates-2025.htm"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.cit-provider :as ca-cit]
            [kontor.l10n-ca.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the CA CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the CA CIT provider over a (tax-unit, inputs) pair, return the
   `TaxReturnFacts`. Convenience wrapper."
  [tax-unit inputs]
  (let [conn (fresh)]
    (ptp/period-tax-facts
     (ca-cit/ca-cit-provider {})
     {:entity   :corp
      :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
      :db       (d/db conn)
      :as-of    #inst "2025-06-30"
      :tax-unit tax-unit
      :inputs   inputs})))

(defn- component
  "Pull one component by `:authority` out of a `TaxReturnFacts`."
  [facts authority]
  (->> facts :components (filter #(= authority (:authority %))) first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. CRA / PwC worked example — Acme Widgets Co. (research note 111 §2)
;; ============================================================================

(deftest cra-worked-example-ccpc-on-ab-allocation
  (testing "Acme Widgets Co. CCPC, 620k taxable, ON 65% / AB 35% — note 111 §2"
    (let [facts (compute {:ccpc? true
                          :provincial-allocation {:on 0.65M :ab 0.35M}}
                         {:taxable-income 620000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)
          ab    (component facts :ca-ab-tra)]
      (testing "three components: federal + ON + AB"
        (is (= 3 (count (:components facts))))
        (is (some? fed))
        (is (some? on))
        (is (some? ab)))
      (testing "Federal: SBD on first 500k at 9% (45,000) + 120k at 15% (18,000) = 63,000"
        (is (== 620000M (:amount (:base fed))))
        (is (== 63000M  (:amount (:gross-liability fed))))
        (is (== 63000M  (:amount (:liability fed))))
        (is (= :ccpc (:regime fed))))
      (testing "Ontario: 620k × 0.65 = 403,000 base; SBD pool 500k × 0.65 = 325,000"
        (is (== 403000M (:amount (:base on))))
        (is (== 0.65M   (get-in on [:jurisdiction-specific-codes :ca/share])))
        (testing "325k × 3.2% (10,400.00) + 78k × 11.5% (8,970.00) = 19,370.00"
          (is (== 19370M (:amount (:gross-liability on))))
          (is (== 19370M (:amount (:liability on))))))
      (testing "Alberta: 620k × 0.35 = 217,000 base; SBD pool 500k × 0.35 = 175,000"
        (is (== 217000M (:amount (:base ab))))
        (is (== 0.35M   (get-in ab [:jurisdiction-specific-codes :ca/share])))
        (testing "175k × 2% (3,500) + 42k × 8% (3,360) = 6,860"
          (is (== 6860M (:amount (:gross-liability ab))))
          (is (== 6860M (:amount (:liability ab))))
          (is (= :ca-ab-tra (:authority ab))
              "AB files with Alberta TRA, not the CRA")))
      (testing "Total liability: 63,000 + 19,370 + 6,860 = 89,230"
        ;; The note 111 §2 arithmetic example states 89,430.86, derived
        ;; from a SBD-pool allocation of 322,580 for ON (not the
        ;; proportional 325,000). That note's arithmetic is internally
        ;; inconsistent (it computes ON share = 0.65 then uses 322,580
        ;; = 500k × 0.6452 for the SBD pool); the methodology the note
        ;; intends — proportional Sch-5 allocation of the federal SBD
        ;; pool — is what this provider implements, giving 89,230.00
        ;; exactly per the formulas in §1.1 and §2 prose.
        (is (== 89230M (total-liability facts)))))))

;; ============================================================================
;; §2. Non-CCPC standard case
;; ============================================================================

(deftest non-ccpc-flat-rate
  (testing "non-CCPC, 1M taxable, ON single-province — flat federal + flat ON"
    (let [facts (compute {:ccpc? false
                          :provincial-allocation {:on 1M}}
                         {:taxable-income 1000000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)]
      (testing "Federal: 1,000,000 × 15% = 150,000 (flat — no SBD)"
        (is (== 150000M (:amount (:gross-liability fed))))
        (is (== 150000M (:amount (:liability fed))))
        (is (= :general (:regime fed)))
        (is (= :flat (:schedule/type (:schedule fed))))
        (is (== 0.15M (:rate (:schedule fed)))))
      (testing "Ontario: 1,000,000 × 11.5% = 115,000 (flat — no SBD)"
        (is (== 1000000M (:amount (:base on))))
        (is (== 115000M (:amount (:gross-liability on))))
        (is (== 115000M (:amount (:liability on))))
        (is (= :flat (:schedule/type (:schedule on))))
        (is (== 0.115M (:rate (:schedule on)))))
      (testing "Total: 150,000 + 115,000 = 265,000"
        (is (== 265000M (total-liability facts)))))))

;; ============================================================================
;; §3. Multi-province ON+BC CCPC — easy round-number confidence test
;; ============================================================================

(deftest two-province-ccpc-on-bc-small
  (testing "CCPC, 100k taxable, ON 60% / BC 40% — both province bases under SBD pool"
    (let [facts (compute {:ccpc? true
                          :provincial-allocation {:on 0.60M :bc 0.40M}}
                         {:taxable-income 100000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)
          bc    (component facts :ca-bc)]
      (testing "three components: federal + ON + BC (no AB)"
        (is (= 3 (count (:components facts))))
        (is (some? fed))
        (is (some? on))
        (is (some? bc))
        (is (nil? (component facts :ca-ab-tra))))
      (testing "Federal: 100k well under 500k SBD limit → 100k × 9% = 9,000"
        (is (== 100000M (:amount (:base fed))))
        (is (== 9000M   (:amount (:gross-liability fed))))
        (is (== 9000M   (:amount (:liability fed)))))
      (testing "Ontario: 100k × 0.6 = 60k base; SBD pool 500k × 0.6 = 300k"
        (is (== 60000M (:amount (:base on))))
        (testing "60k all in SBD bracket: 60k × 3.2% = 1,920"
          (is (== 1920M (:amount (:gross-liability on))))
          (is (== 1920M (:amount (:liability on))))))
      (testing "BC: 100k × 0.4 = 40k base; SBD pool 500k × 0.4 = 200k"
        (is (== 40000M (:amount (:base bc))))
        (testing "40k all in SBD bracket: 40k × 2% = 800"
          (is (== 800M (:amount (:gross-liability bc))))
          (is (== 800M (:amount (:liability bc))))
          (is (= :ca-bc (:authority bc)))))
      (testing "Total: 9,000 + 1,920 + 800 = 11,720"
        (is (== 11720M (total-liability facts)))))))

;; ============================================================================
;; §4. SR&ED — refundable for CCPC, non-refundable for non-CCPC
;; ============================================================================

(deftest sred-credit-ccpc-refundable
  (testing "CCPC with 200k SR&ED spend, well under 3M limit → 35% refundable ITC"
    (let [facts (compute {:ccpc? true
                          :provincial-allocation {:on 1M}}
                         {:taxable-income    100000M
                          :sred-expenditure  200000M})
          fed   (component facts :cra)]
      (testing "Federal gross: 100k × 9% (SBD) = 9,000"
        (is (== 9000M (:amount (:gross-liability fed)))))
      (testing "SR&ED credit: 200k × 35% = 70,000 refundable"
        (let [sred (->> fed :credits (filter #(= :ca-sred-ccpc (:code %))) first)]
          (is (some? sred) "SR&ED credit fired for CCPC")
          (is (true? (:refundable? sred)))
          (is (== 70000M (:amount sred)))))
      (testing "Liability goes NEGATIVE: 9,000 − 70,000 = −61,000 (a refund)"
        (is (== -61000M (:amount (:liability fed))))))))

(deftest sred-credit-non-ccpc-non-refundable-floors
  (testing "non-CCPC with 200k SR&ED spend → 15% non-refundable, floored at 0"
    (let [facts (compute {:ccpc? false
                          :provincial-allocation {:on 1M}}
                         {:taxable-income    100000M
                          :sred-expenditure  200000M})
          fed   (component facts :cra)]
      (testing "Federal gross: 100k × 15% = 15,000"
        (is (== 15000M (:amount (:gross-liability fed)))))
      (testing "SR&ED credit: 200k × 15% = 30,000 non-refundable"
        (let [sred (->> fed :credits (filter #(= :ca-sred-standard (:code %))) first)]
          (is (some? sred))
          (is (false? (:refundable? sred)))
          (is (== 30000M (:amount sred)))))
      (testing "Liability floored at 0 (non-refundable credit caps reduction)"
        (is (== 0M (:amount (:liability fed))))))))

;; ============================================================================
;; §5. Substrate-property sanity
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p :where [?p :kontor.parameter/code _]] (d/db conn)))
            n-provs  (count (d/q '[:find ?p :where [?p :kontor.provision/code _]] (d/db conn)))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

(deftest provenance-audit-trail
  (testing "every component records the provisions that fired"
    (let [facts (compute {:ccpc? true
                          :provincial-allocation {:on 1M}}
                         {:taxable-income 100000M
                          :sred-expenditure 50000M})
          fed   (component facts :cra)
          on    (component facts :ca-on)]
      (is (= #{"CA-ITA-§125-CCPC-SBD" "CA-ITA-§127.1-SRED-CCPC"}
             (set (-> fed :provenance :provisions-applied)))
          "Federal fired CCPC SBD schedule + CCPC SR&ED credit (no non-CCPC variants)")
      (is (= #{"CA-ON-TA-§31-CCPC-SBD"}
             (set (-> on :provenance :provisions-applied)))
          "Ontario fired ON CCPC SBD schedule only (no non-CCPC variant)")
      (is (= :ca-cit (-> fed :provenance :provider-id)))
      (is (= :ca-cit (-> on  :provenance :provider-id))))))

(deftest unallocated-province-raises-when-asked
  (testing "asking for a province not in :provincial-allocation: no component built"
    (let [facts (compute {:ccpc? true
                          :provincial-allocation {:on 1M}}
                         {:taxable-income 100000M})]
      (is (nil? (component facts :ca-bc))
          "BC not allocated → no BC component")
      (is (nil? (component facts :ca-ab-tra))
          "AB not allocated → no AB component"))))

(deftest missing-taxable-income-raises
  (testing ":inputs :taxable-income is required"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"taxable-income"
                            (ptp/period-tax-facts
                             (ca-cit/ca-cit-provider {})
                             {:entity   :corp
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {:ccpc? false :provincial-allocation {:on 1M}}
                              :inputs   {}}))))))

(deftest on-2026-bill-12-transition
  ;; Note 126 P0-1 + P0-2 + P0-3 — Ontario Bill 12 (RA Nov 2025;
  ;; SO 2025, c.12) raises ON small-business limit from $500k to $600k
  ;; effective 2026-01-01 and cuts the SBD rate from 3.2% to 2.2%
  ;; effective 2026-07-01. The provider must read the per-province
  ;; sbd-limit (not federal) for ON, and pick up both transitions.
  (let [conn (fresh)
        run-on (fn [as-of]
                 (ptp/period-tax-facts
                  (ca-cit/ca-cit-provider {})
                  {:entity   :corp
                   :period   {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                   :db       (d/db conn)
                   :as-of    as-of
                   :tax-unit {:ccpc?                 true
                              :provincial-allocation {:on 1M}}
                   :inputs   {:taxable-income 600000M}}))]
    (testing "as-of 2025-12-31 → pre-Bill-12: $500k limit at 3.2%"
      (let [on (component (run-on #inst "2025-12-31") :ca-on)]
        ;; 500000 × 3.2% + 100000 × 11.5% = 16000 + 11500 = 27500
        (is (== 27500.00M (:amount (:liability on))))))
    (testing "as-of 2026-04-01 → limit raised to $600k (Jan 2026) but rate still 3.2% (cut not yet 2026-07-01)"
      (let [on (component (run-on #inst "2026-04-01") :ca-on)]
        ;; whole 600000 at 3.2% = 19200 (no general-rate slice)
        (is (== 19200.00M (:amount (:liability on))))))
    (testing "as-of 2026-09-01 → limit $600k + rate 2.2% (both transitions live)"
      (let [on (component (run-on #inst "2026-09-01") :ca-on)]
        ;; 600000 × 2.2% = 13200 (no general-rate slice)
        (is (== 13200.00M (:amount (:liability on))))))))

(deftest functional-commodity-is-cad-on-every-money
  (let [facts (compute {:ccpc? true :provincial-allocation {:on 1M}}
                       {:taxable-income 100000M})]
    (is (every? #(= :CAD (:commodity (:base %)))      (:components facts)))
    (is (every? #(= :CAD (:commodity (:liability %))) (:components facts)))))
