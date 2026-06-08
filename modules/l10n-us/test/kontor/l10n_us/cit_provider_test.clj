(ns kontor.l10n-us.cit-provider-test
  "US corporate income tax provider tests — ADR-101 substrate's US
   consumer (ADR-104 / FR template, applied to the United States).
   Validates that the statute-as-data path (`:parameter` +
   `:provision` rows + `kontor.tax.statute/apply-provisions` fold)
   computes real US §11 federal CIT against published worked examples.

   Worked examples cited:

   - **§1 Clean C-Corp @ $1 M** — flat 21 % × $1 000 000 = $210 000.
     Source: published authority worked example; IRC §11(b).
   - **§2 Bitemporal stability 2020 vs 2025** — same $1 M assessed
     as-of either year-end. The §11 rate is post-TCJA stable in the
     5y window → both return $210 000 (proves substrate plumbing).
   - **§3 CGT lane integration** — $1 M book-profit + $50 k
     :cgt-cit-base-additions → base $1 050 000; CIT $220 500.
   - **§4 §172 NOL optional stub** — $1 M − $200 k NOL → base $800 k;
     CIT $168 000. Provenance trail records the §172 code.
   - **§5 §163(j) + §250 optional stubs** — both fire; provenance
     records both codes.
   - **§6 Zero taxable** — $0 → $0 (no CAMT in v1).
   - **§7 Loss clamps at zero** — negative book-profit → CIT $0
     (no negative-tax refund; CAMT deferred).
   - **§8 Install idempotence** — substrate property.
   - **§9 Provenance** — `:provisions-applied` records the codes.
   - **§10 Missing book-profit** — ex-info.
   - **§11 Pre-2018 raises** — no §11 parameter-value at 2017."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.cit-provider :as us-cit]
            [kontor.l10n-us.cit-statute :as cit-statute]
            [kontor.tax.period-tax-provider :as ptp]))

(defn- fresh
  "Fresh test DB with the US CIT statute installed."
  []
  (let [conn (core/create-test-db)]
    (cit-statute/install! conn)
    conn))

(defn- compute
  "Run the US CIT provider over `inputs` + `tax-unit`, return the
   `TaxReturnFacts`. Default `:as-of` 2025-12-31."
  ([tax-unit inputs] (compute tax-unit inputs #inst "2025-12-31"))
  ([tax-unit inputs as-of]
   (let [conn (fresh)]
     (ptp/period-tax-facts
      (us-cit/us-cit-provider {})
      {:entity   :c-corp
       :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
       :db       (d/db conn)
       :as-of    as-of
       :tax-unit tax-unit
       :inputs   inputs}))))

(defn- cit-component
  "Pull the CIT component out of a `TaxReturnFacts`."
  [facts]
  (->> facts :components first))

(defn- total-liability [facts]
  (reduce + 0M (map (comp :amount :liability) (:components facts))))

;; ============================================================================
;; §1. Clean C-Corp @ $1 M — flat 21 %
;; ============================================================================

(deftest clean-c-corp-1m-taxable
  (testing "C-Corp, book-profit $1 000 000, as-of 2025-12-31: flat 21 % → CIT $210 000"
    (let [facts (compute {} {:book-profit 1000000M})
          c     (cit-component facts)]
      (testing "schedule = flat 21 %"
        (is (= :flat (:kontor.schedule/type (:schedule c))))
        (is (== 0.21M (:rate (:schedule c)))))
      (testing "base = book-profit (no adjustments fire)"
        (is (== 1000000M (:amount (:base c)))))
      (testing "gross-liability = $210 000"
        (is (== 210000M (:amount (:gross-liability c)))))
      (testing "liability = $210 000"
        (is (== 210000M (:amount (:liability c))))
        (is (== 210000M (total-liability facts))))
      (testing "no provisions fire (no driver facts)"
        (is (empty? (-> c :provenance :provisions-applied)))))))

;; ============================================================================
;; §2. Bitemporal — substrate plumbing (no rate change in Q5.4 window)
;; ============================================================================

(deftest bitemporal-stable-21-pct-2020-vs-2025
  (testing "$1 M taxable assessed 2020-12-31 vs 2025-12-31 yields the same $210 000
            (no rate change in the shipped 5y window — proves substrate plumbing)"
    (let [pre  (compute {} {:book-profit 1000000M} #inst "2020-12-31")
          post (compute {} {:book-profit 1000000M} #inst "2025-12-31")]
      (is (== 210000M (total-liability pre)))
      (is (== 210000M (total-liability post)))
      (is (== (total-liability pre) (total-liability post))))))

;; ============================================================================
;; §3. CGT corp-net lane integration (required base-add provision)
;; ============================================================================

(deftest cgt-cit-base-additions-flows-through
  (testing "book-profit $1 M + :cgt-cit-base-additions $50 k → base $1 050 000; CIT $220 500"
    (let [facts (compute {} {:book-profit             1000000M
                             :cgt-cit-base-additions  50000M})
          c     (cit-component facts)]
      (testing "base = 1 000 000 + 50 000 = 1 050 000"
        (is (== 1050000M (:amount (:base c)))))
      (testing "base-transform records the §11 CGT add"
        (let [items (:items (:base-transform c))]
          (is (= 1 (count items)))
          (is (= :us-§1245-§1250-recapture (:code (first items))))
          (is (== 50000M (:amount (first items))))))
      (testing "liability = 1 050 000 × 21 % = $220 500"
        (is (== 220500M (:amount (:liability c)))))
      (testing "provenance records the §11 base-additions provision"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "US-IRC-§11-cit-base-additions"))))))

;; ============================================================================
;; §4. §172 NOL optional stub
;; ============================================================================

(deftest nol-base-deduct-traces-in-provenance
  (testing "book-profit $1 M − :nol-applied $200 k → base $800 k; CIT $168 000"
    (let [facts (compute {} {:book-profit  1000000M
                             :nol-applied  200000M})
          c     (cit-component facts)]
      (testing "base = 1 000 000 − 200 000 = 800 000"
        (is (== 800000M (:amount (:base c)))))
      (testing "liability = 800 000 × 21 % = $168 000"
        (is (== 168000M (:amount (:liability c)))))
      (testing "provenance records the §172 provision code"
        (is (contains? (set (-> c :provenance :provisions-applied))
                       "US-IRC-§172-nol-deduction"))))))

;; ============================================================================
;; §5. §163(j) + §250 optional stubs trace
;; ============================================================================

(deftest §163j-and-§250-optional-stubs-trace
  (testing "both optional add-back + deduct provisions fire; provenance records both"
    (let [facts (compute {} {:book-profit                  1000000M
                             :§163j-disallowed-interest    30000M
                             :§250-deduction               20000M})
          c     (cit-component facts)
          applied (set (-> c :provenance :provisions-applied))]
      (testing "base = 1 000 000 + 30 000 − 20 000 = 1 010 000"
        (is (== 1010000M (:amount (:base c)))))
      (testing "liability = 1 010 000 × 21 % = $212 100"
        (is (== 212100M (:amount (:liability c)))))
      (testing "both optional provision codes appear in provenance"
        (is (contains? applied "US-IRC-§163j-interest-cap"))
        (is (contains? applied "US-IRC-§250-fdii-gilti-deduction"))))))

;; ============================================================================
;; §6. Zero taxable yields zero tax (no minimum tax in v1)
;; ============================================================================

(deftest zero-taxable-yields-zero-tax
  (testing "$0 book-profit → CIT $0 (CAMT deferred to v1.x)"
    (let [facts (compute {} {:book-profit 0M})
          c     (cit-component facts)]
      (is (== 0M (:amount (:liability c)))))))

;; ============================================================================
;; §7. Losses clamp at zero (no negative §11 tax)
;; ============================================================================

(deftest losses-clamp-at-zero
  (testing "$-100 000 book-profit → CIT $0 (max(0, base × rate); no refund)"
    (let [facts (compute {} {:book-profit -100000M})
          c     (cit-component facts)]
      (is (== 0M (:amount (:liability c))))
      (is (== 0M (:amount (:gross-liability c)))))))

;; ============================================================================
;; §8. Install idempotence
;; ============================================================================

(deftest installable-is-idempotent
  (testing "install! is idempotent (re-run is a no-op on identity attrs)"
    (let [conn (core/create-test-db)]
      (cit-statute/install! conn)
      (cit-statute/install! conn)
      (let [n-params (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.parameter/jurisdiction ?juris]
                                   [?p :kontor.parameter/code ?code]
                                   [(.startsWith ^String ?code "US.CIT.")]]
                                 (d/db conn) :us))
            n-provs  (count (d/q '[:find ?p
                                   :in $ ?juris
                                   :where
                                   [?p :kontor.provision/jurisdiction ?juris]
                                   [?p :kontor.provision/code ?code]
                                   [(.startsWith ^String ?code "US-IRC-")]]
                                 (d/db conn) :us))]
        (is (= (count cit-statute/parameters) n-params))
        (is (= (count cit-statute/provisions) n-provs))))))

;; ============================================================================
;; §9. Provenance trail
;; ============================================================================

(deftest provenance-records-the-applied-provisions
  (testing "clean C-Corp case fires NO provisions (no driver facts present)"
    (let [facts (compute {} {:book-profit 1000000M})
          c     (cit-component facts)]
      (is (empty? (-> c :provenance :provisions-applied)))))
  (testing "with all driver facts present, all four provisions fire"
    (let [facts (compute {} {:book-profit                1000000M
                             :cgt-cit-base-additions   50000M
                             :nol-applied              200000M
                             :§163j-disallowed-interest 30000M
                             :§250-deduction           20000M})
          c     (cit-component facts)
          applied (set (-> c :provenance :provisions-applied))]
      (is (contains? applied "US-IRC-§11-cit-base-additions"))
      (is (contains? applied "US-IRC-§172-nol-deduction"))
      (is (contains? applied "US-IRC-§163j-interest-cap"))
      (is (contains? applied "US-IRC-§250-fdii-gilti-deduction")))))

;; ============================================================================
;; §10. Missing book-profit raises
;; ============================================================================

(deftest missing-book-profit-raises
  (testing "absent :inputs :book-profit → ex-info with diagnostic"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"book-profit"
                            (ptp/period-tax-facts
                             (us-cit/us-cit-provider {})
                             {:entity   :c-corp
                              :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2025-06-30"
                              :tax-unit {}
                              :inputs   {}}))))))

;; ============================================================================
;; §11. Pre-2018 as-of raises (no parameter-value row in window)
;; ============================================================================

(deftest pre-2018-as-of-raises
  (testing ":as-of 2017-12-31 → no §11 rate parameter-value → ex-info"
    (let [conn (fresh)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"US.CIT.§11.rate"
                            (ptp/period-tax-facts
                             (us-cit/us-cit-provider {})
                             {:entity   :c-corp
                              :period   {:from #inst "2017-01-01" :to #inst "2018-01-01"}
                              :db       (d/db conn)
                              :as-of    #inst "2017-12-31"
                              :tax-unit {}
                              :inputs   {:book-profit 1000000M}}))))))

;; ============================================================================
;; Substrate property — monocommodity facts
;; ============================================================================

(deftest functional-commodity-is-usd-on-every-money
  (let [facts (compute {} {:book-profit 1000000M})]
    (is (every? #(= :USD (:commodity (:base %)))
                (:components facts)))
    (is (every? #(= :USD (:commodity (:liability %)))
                (:components facts)))))
