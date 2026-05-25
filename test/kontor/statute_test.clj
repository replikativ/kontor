(ns kontor.statute-test
  "ADR-101 — statute-as-data substrate tests.

   Exercises:
     §1  starter `:tax-concept` catalogue installed on every test DB.
     §2  `eval-condition` — the 11-predicate closed vocab + nil/true/false
         + nested fact keys + missing-fact handling + unknown-predicate trap.
     §3  `parameter-value-at` + `parameter-brackets-at` — date-keyed
         value history (the OpenFisca pattern); in-window / out-of-window
         / multi-version selection / unknown-code.
     §4  `applicable-provisions` — concept + jurisdiction filters,
         effective-from/until windows, condition gate, regime gate.
     §5  `apply-provisions` — priority order, `:exception-of` suppression,
         same-priority ambiguity trap, consequence resolution
         (`:literal` / `:parameter` / `:tax-context-fact` / `:compute-fn`).
     §6  `apply-base-adjustments` (the new sibling of `apply-adjustments`
         for base-side ops) + vocab rejection traps on either side."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; §1. Starter catalogue
;; ============================================================================

(deftest starter-catalogue-installed
  (let [conn  (core/create-test-db)
        codes (set (d/q '[:find [?code ...]
                          :where [_ :tax-concept/code ?code]]
                        (d/db conn)))]
    (testing "all 14 starter concepts (ADR-101 §D6) are seeded"
      (is (= #{:participation-exemption :rollover-relief :like-kind-exchange
               :replacement-property :loss-bucket :lifetime-cap
               :holding-period-preference :non-refundable-credit
               :refundable-credit :surtax :minimum-tax :base-transform-add
               :base-transform-deduct :elective-regime}
             codes)))
    (testing "every concept carries label + family + description"
      (let [rows (d/q '[:find ?code ?label ?family ?desc
                        :where
                        [?c :tax-concept/code ?code]
                        [?c :tax-concept/label ?label]
                        [?c :tax-concept/family ?family]
                        [?c :tax-concept/description ?desc]]
                      (d/db conn))]
        (is (= 14 (count rows)))))))

;; ============================================================================
;; §2. eval-condition — the closed predicate vocabulary
;; ============================================================================

(deftest eval-condition-base-cases
  (testing "nil and true are unconditionally true; false is false"
    (is (statute/eval-condition nil {}))
    (is (statute/eval-condition true {}))
    (is (not (statute/eval-condition false {})))))

(deftest eval-condition-and-or-not
  (let [ctx {:a 1 :b 2 :c 3}]
    (is      (statute/eval-condition [:and [:eq :a 1] [:eq :b 2]] ctx))
    (is (not (statute/eval-condition [:and [:eq :a 1] [:eq :b 99]] ctx)))
    (is      (statute/eval-condition [:or  [:eq :a 99] [:eq :b 2]] ctx))
    (is (not (statute/eval-condition [:or  [:eq :a 99] [:eq :b 99]] ctx)))
    (is      (statute/eval-condition [:not [:eq :a 99]] ctx))
    (is (not (statute/eval-condition [:not [:eq :a 1]] ctx)))))

(deftest eval-condition-comparisons
  (let [ctx {:gross-revenue 100000M}]
    (is      (statute/eval-condition [:geq :gross-revenue 50000M] ctx))
    (is      (statute/eval-condition [:geq :gross-revenue 100000M] ctx))
    (is (not (statute/eval-condition [:geq :gross-revenue 100001M] ctx)))
    (is      (statute/eval-condition [:leq :gross-revenue 100000M] ctx))
    (is (not (statute/eval-condition [:lt  :gross-revenue 100000M] ctx)))
    (is (not (statute/eval-condition [:gt  :gross-revenue 100000M] ctx)))
    (is      (statute/eval-condition [:lt  :gross-revenue 100001M] ctx))
    (is      (statute/eval-condition [:between :gross-revenue 50000M 200000M] ctx))
    (is (not (statute/eval-condition [:between :gross-revenue 200000M 300000M] ctx)))))

(deftest eval-condition-set-membership
  (let [ctx {:form :gmbh}]
    (is      (statute/eval-condition [:in :form #{:gmbh :ag}] ctx))
    (is (not (statute/eval-condition [:in :form #{:kgaa :se}] ctx)))))

(deftest eval-condition-status-is
  (testing ":status-is is sugar for :eq — same semantics, different intent"
    (let [ctx {:filing-status :single}]
      (is (statute/eval-condition [:status-is :filing-status :single] ctx))
      (is (not (statute/eval-condition [:status-is :filing-status :mfj] ctx))))))

(deftest eval-condition-nested-fact-keys
  (testing "vector fact-key reads via get-in (for :tax-unit, :entity sub-maps)"
    (let [ctx {:tax-unit {:filing-status :hoh :dependents 2}}]
      (is (statute/eval-condition [:eq [:tax-unit :filing-status] :hoh] ctx))
      (is (statute/eval-condition [:geq [:tax-unit :dependents] 1] ctx)))))

(deftest eval-condition-missing-facts
  (testing "missing facts are nil — comparisons return false (not crash)"
    (is (not (statute/eval-condition [:leq :missing 100M] {})))
    (is (not (statute/eval-condition [:geq :missing 100M] {})))
    (is (not (statute/eval-condition [:lt  :missing 100M] {})))
    (is (not (statute/eval-condition [:gt  :missing 100M] {})))
    (is (not (statute/eval-condition [:between :missing 1M 10M] {})))
    (is (not (statute/eval-condition [:in :missing #{:a :b}] {})))
    (testing "but :eq with nil is true (compatible with explicit nil checks)"
      (is (statute/eval-condition [:eq :missing nil] {})))))

(deftest eval-condition-unknown-predicate-traps
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown predicate"
                        (statute/eval-condition [:bogus :a 1] {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid expression"
                        (statute/eval-condition "not-a-vector-or-bool" {}))))

;; ============================================================================
;; §3. Parameter resolution
;; ============================================================================

(defn- with-parameter-fixture [body-fn]
  (let [conn (core/create-test-db)]
    (d/transact conn [{:parameter/code "DE.KSt.rate"
                       :parameter/label "DE KSt federal rate"
                       :parameter/jurisdiction :de
                       :parameter/unit :rate}])
    (d/transact conn [{:parameter-value/parameter [:parameter/code "DE.KSt.rate"]
                       :parameter-value/effective-from #inst "2008-01-01"
                       :parameter-value/effective-until #inst "2024-01-01"
                       :parameter-value/decimal-value 0.15M}
                      {:parameter-value/parameter [:parameter/code "DE.KSt.rate"]
                       :parameter-value/effective-from #inst "2024-01-01"
                       :parameter-value/decimal-value 0.15M}])
    (body-fn conn)))

(deftest parameter-value-at-in-window
  (with-parameter-fixture
    (fn [conn]
      (is (= 0.15M (statute/parameter-value-at (d/db conn) "DE.KSt.rate" #inst "2010-06-01")))
      (is (= 0.15M (statute/parameter-value-at (d/db conn) "DE.KSt.rate" #inst "2025-06-01"))))))

(deftest parameter-value-at-out-of-window
  (with-parameter-fixture
    (fn [conn]
      (testing "before any value's effective-from → nil"
        (is (nil? (statute/parameter-value-at (d/db conn) "DE.KSt.rate" #inst "2007-12-31"))))
      (testing "exactly AT effective-until (half-open) → next value's range"
        (is (= 0.15M (statute/parameter-value-at (d/db conn) "DE.KSt.rate" #inst "2024-01-01")))))))

(deftest parameter-value-at-unknown-code
  (with-parameter-fixture
    (fn [conn]
      (is (nil? (statute/parameter-value-at (d/db conn) "MADE.UP.CODE" #inst "2025-01-01"))))))

(deftest parameter-value-at-multi-version-selection
  (let [conn (core/create-test-db)]
    (d/transact conn [{:parameter/code "X.rate" :parameter/label "X" :parameter/jurisdiction :test :parameter/unit :rate}])
    (d/transact conn [{:parameter-value/parameter [:parameter/code "X.rate"]
                       :parameter-value/effective-from #inst "2020-01-01"
                       :parameter-value/effective-until #inst "2023-01-01"
                       :parameter-value/decimal-value 0.20M}
                      {:parameter-value/parameter [:parameter/code "X.rate"]
                       :parameter-value/effective-from #inst "2023-01-01"
                       :parameter-value/effective-until #inst "2026-01-01"
                       :parameter-value/decimal-value 0.23M}
                      {:parameter-value/parameter [:parameter/code "X.rate"]
                       :parameter-value/effective-from #inst "2026-01-01"
                       :parameter-value/decimal-value 0.25M}])
    (testing "the resolver picks the value whose window contains as-of"
      (is (= 0.20M (statute/parameter-value-at (d/db conn) "X.rate" #inst "2021-06-01")))
      (is (= 0.23M (statute/parameter-value-at (d/db conn) "X.rate" #inst "2024-06-01")))
      (is (= 0.25M (statute/parameter-value-at (d/db conn) "X.rate" #inst "2027-06-01"))))))

(deftest parameter-brackets-at-sorted-by-index
  (let [conn (core/create-test-db)]
    (d/transact conn [{:parameter/code "FR.IS.pme" :parameter/label "FR IS PME"
                       :parameter/jurisdiction :fr :parameter/unit :bracket-scale}])
    (d/transact conn [{:parameter-bracket/parameter [:parameter/code "FR.IS.pme"]
                       :parameter-bracket/index 1 :parameter-bracket/rate 0.25M
                       :parameter-bracket/effective-from #inst "2023-01-01"}
                      {:parameter-bracket/parameter [:parameter/code "FR.IS.pme"]
                       :parameter-bracket/index 0 :parameter-bracket/rate 0.15M
                       :parameter-bracket/upper 42500M
                       :parameter-bracket/effective-from #inst "2023-01-01"}])
    (let [brackets (statute/parameter-brackets-at (d/db conn) "FR.IS.pme" #inst "2025-06-01")]
      (testing "brackets are sorted by :parameter-bracket/index regardless of insertion order"
        (is (= [{:rate 0.15M :upper 42500M}
                {:rate 0.25M :upper nil}]
               brackets)))
      (testing "the shape feeds kontor.tax-schedule/progressive directly"
        (let [schedule (ts/progressive brackets)]
          (is (= 45750.00M (ts/apply-schedule schedule 200000M))
              "200,000 → 42,500×15% + 157,500×25% = 6,375 + 39,375 = 45,750")
          (is (= 0M (ts/apply-schedule schedule 0M))))))))

;; ============================================================================
;; §4. Provision applicability
;; ============================================================================

(defn- p
  "Build a provision tx-data map with sensible defaults."
  [overrides]
  (merge {:provision/jurisdiction   :test
          :provision/title          "Test provision"
          :provision/citation       "https://test"
          :provision/effective-from #inst "2000-01-01"
          :provision/priority       100}
         overrides))

(defn- fresh-with-provisions [provisions]
  (let [conn (core/create-test-db)]
    (d/transact conn provisions)
    conn))

(deftest applicable-provisions-concept-filter
  (let [conn (fresh-with-provisions
              [(p {:provision/code "A" :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence "{}"})
               (p {:provision/code "B" :provision/concept [:tax-concept/code :refundable-credit]
                   :provision/consequence "{}"})])]
    (is (= ["A"] (mapv :provision/code
                       (statute/applicable-provisions (d/db conn)
                                                      {:concept :surtax :jurisdiction :test
                                                       :as-of #inst "2025-01-01"} {}))))))

(deftest applicable-provisions-jurisdiction-filter
  (let [conn (fresh-with-provisions
              [(p {:provision/code "A-DE" :provision/jurisdiction :de
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence "{}"})
               (p {:provision/code "A-FR" :provision/jurisdiction :fr
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence "{}"})])]
    (is (= ["A-DE"] (mapv :provision/code
                          (statute/applicable-provisions (d/db conn)
                                                         {:concept :surtax :jurisdiction :de
                                                          :as-of #inst "2025-01-01"} {}))))))

(deftest applicable-provisions-date-window
  (let [conn (fresh-with-provisions
              [(p {:provision/code "EXPIRED"
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/effective-from #inst "2000-01-01"
                   :provision/effective-until #inst "2020-01-01"
                   :provision/consequence "{}"})
               (p {:provision/code "FUTURE"
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/effective-from #inst "2030-01-01"
                   :provision/consequence "{}"})
               (p {:provision/code "CURRENT"
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/effective-from #inst "2020-01-01"
                   :provision/consequence "{}"})])]
    (is (= #{"CURRENT"} (set (mapv :provision/code
                                   (statute/applicable-provisions (d/db conn)
                                                                  {:concept :surtax :jurisdiction :test
                                                                   :as-of #inst "2025-01-01"} {})))))))

(deftest applicable-provisions-condition-gate
  (let [conn (fresh-with-provisions
              [(p {:provision/code "PME"
                   :provision/concept [:tax-concept/code :refundable-credit]
                   :provision/condition (pr-str [:leq :revenue 10000000M])
                   :provision/consequence "{}"})
               (p {:provision/code "ALWAYS"
                   :provision/concept [:tax-concept/code :refundable-credit]
                   :provision/consequence "{}"})])]
    (testing "with condition met"
      (is (= #{"PME" "ALWAYS"}
             (set (mapv :provision/code
                        (statute/applicable-provisions (d/db conn)
                                                       {:concept :refundable-credit :jurisdiction :test
                                                        :as-of #inst "2025-01-01"}
                                                       {:revenue 5000000M}))))))
    (testing "with condition NOT met"
      (is (= ["ALWAYS"]
             (mapv :provision/code
                   (statute/applicable-provisions (d/db conn)
                                                  {:concept :refundable-credit :jurisdiction :test
                                                   :as-of #inst "2025-01-01"}
                                                  {:revenue 20000000M})))))))

(deftest applicable-provisions-regime-gate
  (let [conn (core/create-test-db)]
    (d/transact conn [{:regime/code :test-old :regime/jurisdiction :test :regime/label "Old"
                       :regime/effective-from #inst "2000-01-01"}
                      {:regime/code :test-new :regime/jurisdiction :test :regime/label "New"
                       :regime/effective-from #inst "2024-01-01"}])
    (d/transact conn
                [(p {:provision/code "FREE" :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence "{}"})
                 (p {:provision/code "OLD-only" :provision/concept [:tax-concept/code :surtax]
                     :provision/regime [:regime/code :test-old]
                     :provision/consequence "{}"})
                 (p {:provision/code "NEW-only" :provision/concept [:tax-concept/code :surtax]
                     :provision/regime [:regime/code :test-new]
                     :provision/consequence "{}"})])
    (testing "no regime elected — only regime-free provisions apply"
      (is (= #{"FREE"}
             (set (mapv :provision/code
                        (statute/applicable-provisions (d/db conn)
                                                       {:concept :surtax :jurisdiction :test
                                                        :as-of #inst "2025-01-01" :regime nil} {}))))))
    (testing "OLD elected — FREE + OLD"
      (is (= #{"FREE" "OLD-only"}
             (set (mapv :provision/code
                        (statute/applicable-provisions (d/db conn)
                                                       {:concept :surtax :jurisdiction :test
                                                        :as-of #inst "2025-01-01" :regime :test-old} {}))))))
    (testing "NEW elected — FREE + NEW"
      (is (= #{"FREE" "NEW-only"}
             (set (mapv :provision/code
                        (statute/applicable-provisions (d/db conn)
                                                       {:concept :surtax :jurisdiction :test
                                                        :as-of #inst "2025-01-01" :regime :test-new} {}))))))))

(deftest applicable-provisions-regime-extends-chain
  (testing "electing regime A includes provisions bound to regime A's extends-chain"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:regime/code :base   :regime/jurisdiction :test :regime/label "Base"
                         :regime/effective-from #inst "2000-01-01"}
                        {:regime/code :reform :regime/jurisdiction :test :regime/label "Reform"
                         :regime/extends [:regime/code :base]
                         :regime/effective-from #inst "2024-01-01"}])
      (d/transact conn
                  [(p {:provision/code "BASE-P" :provision/concept [:tax-concept/code :surtax]
                       :provision/regime [:regime/code :base]
                       :provision/consequence "{}"})
                   (p {:provision/code "REFORM-P" :provision/concept [:tax-concept/code :surtax]
                       :provision/regime [:regime/code :reform]
                       :provision/consequence "{}"})])
      (testing "electing :base — only BASE-P"
        (is (= #{"BASE-P"}
               (set (mapv :provision/code
                          (statute/applicable-provisions (d/db conn)
                                                         {:concept :surtax :jurisdiction :test
                                                          :as-of #inst "2025-01-01" :regime :base} {}))))))
      (testing "electing :reform — both REFORM-P AND inherited BASE-P apply"
        (is (= #{"BASE-P" "REFORM-P"}
               (set (mapv :provision/code
                          (statute/applicable-provisions (d/db conn)
                                                         {:concept :surtax :jurisdiction :test
                                                          :as-of #inst "2025-01-01" :regime :reform} {})))))))))

(deftest regime-chain-cycle-detection
  (testing "kontor.tax/cyclic-regime is raised when :regime/extends loops"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:db/id -1 :regime/code :loop-a :regime/jurisdiction :test :regime/label "A"
                         :regime/effective-from #inst "2000-01-01"}
                        {:db/id -2 :regime/code :loop-b :regime/jurisdiction :test :regime/label "B"
                         :regime/effective-from #inst "2000-01-01"
                         :regime/extends -1}])
      ;; close the loop: A extends B
      (d/transact conn [{:regime/code :loop-a
                         :regime/extends [:regime/code :loop-b]}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cyclic-regime"
                            (statute/regime-chain (d/db conn) :loop-a))))))

(deftest regime-chain-handles-no-extends
  (let [conn (core/create-test-db)]
    (d/transact conn [{:regime/code :solo :regime/jurisdiction :test :regime/label "Solo"
                       :regime/effective-from #inst "2000-01-01"}])
    (is (= #{:solo} (statute/regime-chain (d/db conn) :solo)))
    (is (nil? (statute/regime-chain (d/db conn) nil)))))

;; ============================================================================
;; §5. apply-provisions — fold + exception-of + ambiguity + consequence-resolution
;; ============================================================================

(deftest apply-provisions-priority-order
  (let [conn (fresh-with-provisions
              [(p {:provision/code "P3" :provision/priority 300
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence (pr-str {:op :surtax :code :p3
                                                   :amount-from :literal :amount 30M})})
               (p {:provision/code "P1" :provision/priority 100
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence (pr-str {:op :surtax :code :p1
                                                   :amount-from :literal :amount 10M})})
               (p {:provision/code "P2" :provision/priority 200
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence (pr-str {:op :surtax :code :p2
                                                   :amount-from :literal :amount 20M})})])
        {:keys [tax-items]} (statute/apply-provisions (d/db conn)
                                                      {:concept :surtax :jurisdiction :test :as-of #inst "2025-01-01"} {})]
    (is (= [:p1 :p2 :p3] (mapv :code tax-items))
        ":tax-items (:surtax/:credit) emerge in :provision/priority ascending order")))

(deftest apply-provisions-exception-of-suppression
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id -1
                  :provision/code "DEFAULT" :provision/jurisdiction :test
                  :provision/concept [:tax-concept/code :refundable-credit]
                  :provision/title "Default" :provision/citation "https://t"
                  :provision/effective-from #inst "2000-01-01" :provision/priority 200
                  :provision/consequence (pr-str {:op :credit :code :default :refundable? true
                                                  :amount-from :literal :amount 100M})}
                 {:provision/code "EXCEPTION" :provision/jurisdiction :test
                  :provision/concept [:tax-concept/code :refundable-credit]
                  :provision/title "Boost" :provision/citation "https://t"
                  :provision/effective-from #inst "2000-01-01" :provision/priority 300
                  :provision/condition (pr-str [:eq :is-startup? true])
                  :provision/exception-of -1
                  :provision/consequence (pr-str {:op :credit :code :boost :refundable? true
                                                  :amount-from :literal :amount 300M})}])
    (testing "default fires when exception doesn't apply"
      (is (= [:default]
             (mapv :code (:tax-items (statute/apply-provisions (d/db conn)
                                                               {:concept :refundable-credit :jurisdiction :test
                                                                :as-of #inst "2025-01-01"} {}))))))
    (testing "exception fires AND default is suppressed when condition holds"
      (is (= [:boost]
             (mapv :code (:tax-items (statute/apply-provisions (d/db conn)
                                                               {:concept :refundable-credit :jurisdiction :test
                                                                :as-of #inst "2025-01-01"}
                                                               {:is-startup? true}))))))))

(deftest apply-provisions-same-priority-ambiguity-trap
  (let [conn (fresh-with-provisions
              [(p {:provision/code "AMB1" :provision/priority 500
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence (pr-str {:op :surtax :code :a1 :amount-from :literal :amount 10M})})
               (p {:provision/code "AMB2" :provision/priority 500
                   :provision/concept [:tax-concept/code :surtax]
                   :provision/consequence (pr-str {:op :surtax :code :a2 :amount-from :literal :amount 20M})})])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous-provision"
                          (statute/apply-provisions (d/db conn)
                                                    {:concept :surtax :jurisdiction :test
                                                     :as-of #inst "2025-01-01"} {})))))

(deftest apply-provisions-consequence-amount-sources
  (let [conn (core/create-test-db)]
    (d/transact conn [{:parameter/code "X.rate" :parameter/label "X" :parameter/jurisdiction :test :parameter/unit :rate}
                      {:parameter-value/parameter [:parameter/code "X.rate"]
                       :parameter-value/effective-from #inst "2000-01-01"
                       :parameter-value/decimal-value 42M}])
    (d/transact conn
                [(p {:provision/code "literal" :provision/priority 100
                     :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence (pr-str {:op :surtax :code :lit :amount-from :literal :amount 7M})})
                 (p {:provision/code "parameter" :provision/priority 200
                     :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence (pr-str {:op :surtax :code :param :amount-from :parameter :parameter "X.rate"})})
                 (p {:provision/code "tax-context-fact" :provision/priority 300
                     :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence (pr-str {:op :surtax :code :fact :amount-from :tax-context-fact :fact :my-fact})})])
    (statute/register-compute-fn! :test-fn (fn [_ctx] 99M))
    (d/transact conn
                [(p {:provision/code "compute-fn" :provision/priority 400
                     :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence (pr-str {:op :surtax :code :fn :amount-from :compute-fn :fn :test-fn})})])
    (let [{:keys [tax-items]} (statute/apply-provisions (d/db conn)
                                                        {:concept :surtax :jurisdiction :test :as-of #inst "2025-01-01"}
                                                        {:my-fact 13M})]
      (is (= [7M 42M 13M 99M] (mapv :amount tax-items)))
      (testing "every item carries provenance back to its provision"
        (is (every? #(some? (-> % :provenance :provision/code)) tax-items))))))

(deftest compute-fn-unregistered-raises
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"compute-fn not registered"
                        (statute/resolve-compute-fn :totally-not-registered))))

;; ============================================================================
;; §6. apply-base-adjustments + vocab rejection
;; ============================================================================

(deftest apply-base-adjustments-base-add-and-deduct
  (let [{:keys [base resolved]} (ts/apply-base-adjustments
                                 100000M
                                 [{:op :base-add :code :addback :amount 5000M}
                                  {:op :base-deduct :code :deduct :amount 2000M}]
                                 {})]
    (is (= 103000M base) "base + 5000 − 2000")
    (is (= 2 (count resolved)))))

(deftest apply-base-adjustments-fn-amount-sees-running
  (let [{:keys [base]} (ts/apply-base-adjustments
                        1000M
                        [{:op :base-add :code :first :amount 500M}
                         {:op :base-add :code :pct-of-running
                          :amount (fn [{:keys [running]}] (* running 0.1M))}]
                        {})]
    (is (= 1650M base) "1000 → +500 → 1500 → +10% of 1500 = +150 → 1650")))

(deftest apply-base-adjustments-empty-items-passthrough
  (let [{:keys [base resolved]} (ts/apply-base-adjustments 1000M [] {})]
    (is (= 1000M base))
    (is (empty? resolved))))

(deftest apply-base-adjustments-rejects-tax-side-ops
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tax-side"
                        (ts/apply-base-adjustments 100M [{:op :credit :code :x :amount 5M}] {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tax-side"
                        (ts/apply-base-adjustments 100M [{:op :surtax :code :x :amount 5M}] {}))))

(deftest apply-adjustments-rejects-base-side-ops
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"base-side"
                        (ts/apply-adjustments 100M [{:op :base-add :code :x :amount 5M}] {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"base-side"
                        (ts/apply-adjustments 100M [{:op :base-deduct :code :x :amount 5M}] {}))))

;; ============================================================================
;; End-to-end composition smoke
;; ============================================================================

;; ============================================================================
;; §7. :schedule-override op (post-cross-check polish — notes 121/122/123)
;; ============================================================================

(deftest schedule-override-flat-from-parameter
  (testing "a regime-elective provision can swap the schedule via :schedule-override"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:parameter/code "CN.EIT.hnte-rate"
                         :parameter/label "HNTE preferential rate"
                         :parameter/jurisdiction :cn :parameter/unit :rate}
                        {:parameter-value/parameter [:parameter/code "CN.EIT.hnte-rate"]
                         :parameter-value/effective-from #inst "2008-01-01"
                         :parameter-value/decimal-value 0.15M}])
      (d/transact conn
                  [(p {:provision/code "CN-EIT-HNTE-rate"
                       :provision/concept [:tax-concept/code :elective-regime]
                       :provision/consequence (pr-str {:op :schedule-override
                                                       :code :hnte
                                                       :label "HNTE 15%"
                                                       :schedule {:schedule/type :flat
                                                                  :rate-from :parameter
                                                                  :parameter "CN.EIT.hnte-rate"}})})])
      (let [{:keys [schedule-overrides]}
            (statute/apply-provisions (d/db conn)
                                      {:concept :elective-regime :jurisdiction :test :as-of #inst "2025-06-01"}
                                      {})
            override (first schedule-overrides)]
        (is (= 1 (count schedule-overrides)))
        (is (= :schedule-override (:op override)))
        (is (= :hnte (:code override)))
        (testing "the :schedule field has the rate resolved from the parameter at as-of"
          (is (= 0.15M (get-in override [:schedule :rate])))
          (is (= :flat (get-in override [:schedule :schedule/type]))))
        (testing "the resolved schedule feeds tax-schedule/apply-schedule directly"
          (is (== 7500M (ts/apply-schedule (:schedule override) 50000M))))))))

(deftest schedule-override-progressive-brackets-from-parameter
  (testing "the FR PME case: progressive-bracket override from :parameter-bracket"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:parameter/code "FR.IS.pme-brackets" :parameter/label "FR IS PME"
                         :parameter/jurisdiction :fr :parameter/unit :bracket-scale}])
      (d/transact conn [{:parameter-bracket/parameter [:parameter/code "FR.IS.pme-brackets"]
                         :parameter-bracket/index 0 :parameter-bracket/rate 0.15M
                         :parameter-bracket/upper 42500M
                         :parameter-bracket/effective-from #inst "2023-01-01"}
                        {:parameter-bracket/parameter [:parameter/code "FR.IS.pme-brackets"]
                         :parameter-bracket/index 1 :parameter-bracket/rate 0.25M
                         :parameter-bracket/effective-from #inst "2023-01-01"}])
      (d/transact conn
                  [(p {:provision/code "FR-IS-PME-rate"
                       :provision/concept [:tax-concept/code :elective-regime]
                       :provision/consequence (pr-str {:op :schedule-override
                                                       :code :pme
                                                       :label "FR PME 15%/25%"
                                                       :schedule {:schedule/type :progressive-bracket
                                                                  :brackets-from :parameter
                                                                  :parameter "FR.IS.pme-brackets"}})})])
      (let [{:keys [schedule-overrides]}
            (statute/apply-provisions (d/db conn)
                                      {:concept :elective-regime :jurisdiction :test :as-of #inst "2025-06-01"}
                                      {})
            override (first schedule-overrides)
            schedule (:schedule override)]
        (is (= :progressive-bracket (:schedule/type schedule)))
        (is (= [{:rate 0.15M :upper 42500M} {:rate 0.25M :upper nil}]
               (:brackets schedule)))
        (testing "200000 → 42500×15% + 157500×25% = 45750"
          (is (== 45750.00M (ts/apply-schedule schedule 200000M))))))))

(deftest schedule-override-empty-when-no-elective-fires
  (testing "no schedule-override provision → empty :schedule-overrides list"
    (let [conn (fresh-with-provisions
                [(p {:provision/code "X" :provision/concept [:tax-concept/code :surtax]
                     :provision/consequence (pr-str {:op :surtax :code :x
                                                     :amount-from :literal :amount 10M})})])]
      (is (empty? (:schedule-overrides
                   (statute/apply-provisions (d/db conn)
                                             {:concept :surtax :jurisdiction :test :as-of #inst "2025-01-01"} {})))))))

;; ============================================================================
;; §8. compose-greater-of — MAT/AMT composition convention
;; ============================================================================

(deftest compose-greater-of-picks-the-greater
  (testing "MAT (alternative-base) > regular tax → MAT prevails"
    (let [regular {:kind :corporate-income-tax :liability {:amount 100000M :commodity :INR}}
          mat     {:kind :minimum-tax          :liability {:amount 150000M :commodity :INR}}
          out     (statute/compose-greater-of regular mat)]
      (is (= 150000M (:amount (:liability out))))
      (is (= [:corporate-income-tax :minimum-tax] (:composed-of out)))
      (is (= :b (-> out :composition :prevailed)))
      (is (= :minimum-tax (-> out :composition :b :kind)))))
  (testing "regular > MAT → regular prevails"
    (let [regular {:kind :corporate-income-tax :liability {:amount 200000M :commodity :INR}}
          mat     {:kind :minimum-tax          :liability {:amount 150000M :commodity :INR}}
          out     (statute/compose-greater-of regular mat)]
      (is (= 200000M (:amount (:liability out))))
      (is (= :a (-> out :composition :prevailed)))))
  (testing "tie → :a wins by convention"
    (let [a {:kind :a-kind :liability {:amount 100M :commodity :USD}}
          b {:kind :b-kind :liability {:amount 100M :commodity :USD}}
          out (statute/compose-greater-of a b)]
      (is (= :tied-a (-> out :composition :prevailed))))))

(deftest compose-greater-of-handles-nil-liabilities
  (testing "missing :liability defaults to 0"
    (let [a {:kind :a :liability nil}
          b {:kind :b :liability {:amount 50M :commodity :USD}}
          out (statute/compose-greater-of a b)]
      (is (= :b (-> out :composition :prevailed)))
      (is (= 50M (:amount (:liability out)))))))

(deftest end-to-end-base-then-schedule-then-tax
  (testing "base-add + base-deduct → flat schedule → surtax = the canonical DE-style pipeline"
    (let [base       100000M
          base-items [{:op :base-add :code :§10-addback :amount 5000M}
                      {:op :base-deduct :code :§9-deduct :amount 2000M}]
          base'      (:base (ts/apply-base-adjustments base base-items {}))
          schedule   (ts/flat 0.15M)
          gross      (ts/apply-schedule schedule base')
          tax-items  [{:op :surtax :code :soli :amount (fn [{:keys [running]}] (* running 0.055M))}]
          liability  (:liability (ts/apply-adjustments gross tax-items {}))]
      (is (= 103000M base'))
      (is (= 15450.00M gross))
      (is (= 16299.75000M liability)))))

;; ============================================================================
;; §9 — Period-cliff condition builders (ADR-101 Addendum 2)
;; ============================================================================

(deftest period-from-on-or-after-shape
  (testing "builder returns the canonical predicate"
    (is (= [:geq [:period :from] #inst "2026-04-01"]
           (statute/period-from-on-or-after #inst "2026-04-01"))))
  (testing "and the predicate evaluates correctly via eval-condition"
    (let [cliff (statute/period-from-on-or-after #inst "2026-04-01")]
      ;; FY beginning 2026-04-01 — cliff fires
      (is (true? (statute/eval-condition
                  cliff {:period {:from #inst "2026-04-01" :to #inst "2027-03-31"}})))
      ;; FY beginning 2026-04-02 (later) — fires
      (is (true? (statute/eval-condition
                  cliff {:period {:from #inst "2026-04-02" :to #inst "2027-04-01"}})))
      ;; FY beginning 2026-01-01 (before cliff — calendar-year corp) — does NOT fire
      ;; Note 125 §1.5: this is the JP defense-surtax case that AS-OF-based gating got wrong.
      (is (false? (statute/eval-condition
                   cliff {:period {:from #inst "2026-01-01" :to #inst "2026-12-31"}})))
      ;; FY beginning 2025-04-01 — pre-cliff, does NOT fire
      (is (false? (statute/eval-condition
                   cliff {:period {:from #inst "2025-04-01" :to #inst "2026-03-31"}}))))))

(deftest period-from-before-shape
  (testing "sunset-style cliff — period beginning STRICTLY BEFORE the cutover"
    (let [sunset (statute/period-from-before #inst "2030-04-01")]
      (is (= [:lt [:period :from] #inst "2030-04-01"] sunset))
      (is (true?  (statute/eval-condition sunset {:period {:from #inst "2029-04-01"}}))
          "FY beginning 2029 — pre-sunset, fires")
      (is (false? (statute/eval-condition sunset {:period {:from #inst "2030-04-01"}}))
          "FY beginning EXACTLY on the cutover — does NOT fire (strict <)")
      (is (false? (statute/eval-condition sunset {:period {:from #inst "2030-05-01"}}))
          "FY beginning after cutover — does NOT fire"))))

(deftest period-cliff-composes-with-and
  (testing "cliff combines with other predicates via :and"
    (let [cond [:and
                (statute/period-from-on-or-after #inst "2026-04-01")
                [:eq :component :national]]]
      (is (true?  (statute/eval-condition
                   cond {:period {:from #inst "2026-04-01"} :component :national}))
          "both gates true → fires")
      (is (false? (statute/eval-condition
                   cond {:period {:from #inst "2025-04-01"} :component :national}))
          "pre-cliff fiscal year → does not fire even with component match")
      (is (false? (statute/eval-condition
                   cond {:period {:from #inst "2026-04-01"} :component :local}))
          "post-cliff but wrong component → does not fire"))))
