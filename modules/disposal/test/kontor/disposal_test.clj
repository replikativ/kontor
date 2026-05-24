(ns kontor.disposal-test
  "ADR-102 — kontor-disposal companion tests. Exercises:
     §1  install! + schema attrs present
     §2  record-disposal! — round-trip + required-fields validation
     §3  recognize! + void! state transitions
     §4  disposals-of / disposals-in-period
     §5  realized-gain (proceeds − basis − rollover)
     §6  realized-gain-summary grouped by :loss-bucket
     §7  jurisdiction-specific extension fields round-trip (DE §8b
         shape; US §1245 depreciation-taken; UK BADR ownership-fraction
         + exemption-claimed; JP residence?)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disp]
            [kontor.disposal.schema :as disp-schema]))

(defn- fresh
  "Fresh test DB with the disposal schema installed plus one test
   entity (`HoldCo`) and one commodity (USD)."
  []
  (let [conn (core/create-test-db)]
    (disp/install! conn)
    (d/transact conn [{:commodity/symbol "USD" :commodity/name "US Dollar"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "US"
                       :entity/functional-commodity [:commodity/symbol "USD"]}])
    conn))

(def ^:private usd [:commodity/symbol "USD"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record-basic
  "Record a minimal disposal with the named external-id; return the conn."
  [conn xid proceeds basis & [overrides]]
  (disp/record-disposal!
   conn (merge {:entity          holdco
                :external-id     xid
                :kind            :sale
                :subject         [:commodity/symbol "USD"]  ; any stable ref
                :subject-kind    :fixed-asset
                :acquired-on     #inst "2020-01-01"
                :disposed-on     #inst "2025-06-15"
                :proceeds        {:amount proceeds :commodity usd}
                :basis           {:amount basis    :commodity usd}
                :recorded-by-uid "test-user"}
               overrides))
  conn)

;; ============================================================================
;; §1. Install + schema
;; ============================================================================

(deftest install-and-schema-present
  (let [conn (fresh)
        idents (set (d/q '[:find [?i ...]
                           :where [_ :db/ident ?i]] (d/db conn)))]
    (is (contains? idents :disposal/external-id))
    (is (contains? idents :disposal/entity))
    (is (contains? idents :disposal/kind))
    (is (contains? idents :disposal/subject))
    (is (contains? idents :disposal/subject-kind))
    (is (contains? idents :disposal/acquired-on))
    (is (contains? idents :disposal/disposed-on))
    (is (contains? idents :disposal/proceeds-amount))
    (is (contains? idents :disposal/basis-amount))
    (is (contains? idents :disposal/realizing-tx))
    (is (contains? idents :disposal/state)
        ":disposal/state is the ADR-034 facet, transitions are seeded")))

(deftest install-attrs-are-idempotent
  (testing "schema attrs (:db/ident) ARE idempotent across re-installs.
            Status-transition seeds are NOT (composite-tuple identity per
            commitment's caveat) — install once per DB."
    (let [conn (core/create-test-db)]
      (d/transact conn disp-schema/all)
      (d/transact conn disp-schema/all)
      (is (= 1 (d/q '[:find (count ?e) .
                      :where [?e :db/ident :disposal/external-id]]
                    (d/db conn)))))))

;; ============================================================================
;; §2. record-disposal! — round-trip + validation
;; ============================================================================

(deftest record-disposal-round-trip
  (let [conn (fresh)]
    (record-basic conn "d-1" 100000M 60000M)
    (let [d (disp/pull-disposal (d/db conn) "d-1")]
      (is (= :sale (:disposal/kind d)))
      (is (= :fixed-asset (:disposal/subject-kind d)))
      (is (= :recorded (:disposal/state d)))
      (is (== 100000M (:disposal/proceeds-amount d)))
      (is (== 60000M (:disposal/basis-amount d)))
      (is (= "USD" (-> d :disposal/proceeds-commodity :commodity/symbol))))))

(deftest record-disposal-required-fields-trap
  (let [conn (fresh)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":entity required"
                          (disp/record-disposal! conn {:kind :sale})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":external-id required"
                          (disp/record-disposal! conn {:entity holdco :kind :sale})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                          (disp/record-disposal! conn {:entity holdco :external-id "x" :kind :bogus})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":subject required"
                          (disp/record-disposal! conn {:entity holdco :external-id "x" :kind :sale})))))

;; ============================================================================
;; §3. State transitions — recognize! + void!
;; ============================================================================

(defn- some-eid
  "Convenience — return ANY eid in the DB to act as a stand-in for a
   `:transaction` ref in tests that only care about the link target."
  [conn]
  (d/q '[:find ?c . :where [?c :commodity/symbol "USD"]] (d/db conn)))

(deftest recognize-advances-state
  (let [conn (fresh)]
    (record-basic conn "d-rec" 100000M 60000M)
    (disp/recognize! conn {:disposal "d-rec"
                           :transaction (some-eid conn)
                           :recorded-by-uid "test-user"})
    (let [d (disp/pull-disposal (d/db conn) "d-rec")]
      (is (= :recognized (:disposal/state d)))
      (is (some? (:disposal/realizing-tx d))))))

(deftest recognize-rejects-already-recognized
  (let [conn (fresh)]
    (record-basic conn "d-rec2" 100M 50M)
    (disp/recognize! conn {:disposal "d-rec2" :transaction (some-eid conn) :recorded-by-uid "u"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":recorded state"
                          (disp/recognize! conn {:disposal "d-rec2"
                                                 :transaction (some-eid conn)
                                                 :recorded-by-uid "u"})))))

(deftest void-from-recorded
  (let [conn (fresh)]
    (record-basic conn "d-void" 100M 50M)
    (disp/void! conn {:disposal "d-void" :recorded-by-uid "u" :reason :user-correction})
    (is (= :voided (:disposal/state (disp/pull-disposal (d/db conn) "d-void"))))))

(deftest void-from-recognized
  (let [conn (fresh)]
    (record-basic conn "d-void2" 100M 50M)
    (disp/recognize! conn {:disposal "d-void2" :transaction (some-eid conn) :recorded-by-uid "u"})
    (disp/void! conn {:disposal "d-void2" :recorded-by-uid "u" :reason :post-recognition-correction})
    (is (= :voided (:disposal/state (disp/pull-disposal (d/db conn) "d-void2"))))))

;; ============================================================================
;; §4. Queries — disposals-of + disposals-in-period
;; ============================================================================

(deftest disposals-in-period-window
  (let [conn (fresh)]
    (record-basic conn "d-2024-q1" 100M 50M {:disposed-on #inst "2024-02-15"})
    (record-basic conn "d-2024-q3" 200M 80M {:disposed-on #inst "2024-08-15"})
    (record-basic conn "d-2025-q1" 300M 100M {:disposed-on #inst "2025-02-15"})
    (testing "the 2024 window picks up only 2024 disposals"
      (is (= #{"d-2024-q1" "d-2024-q3"}
             (set (map :disposal/external-id
                       (disp/disposals-in-period (d/db conn)
                                                 {:from #inst "2024-01-01"
                                                  :to   #inst "2025-01-01"}))))))
    (testing "voided disposals are excluded"
      (disp/void! conn {:disposal "d-2024-q3" :recorded-by-uid "u"})
      (is (= ["d-2024-q1"]
             (map :disposal/external-id
                  (disp/disposals-in-period (d/db conn)
                                            {:from #inst "2024-01-01"
                                             :to   #inst "2025-01-01"})))))))

(deftest disposals-in-period-entity-scoped
  (testing "two-arity form filters to the named entity (multi-tenant CGT)"
    (let [conn (fresh)
          _    (d/transact conn [{:entity/code "OTHERCO" :entity/name "OtherCo"
                                  :entity/kind :company :entity/country "US"
                                  :entity/functional-commodity usd}])
          other [:entity/code "OTHERCO"]
          db1   (d/db conn)
          holdco-eid (d/q '[:find ?e . :in $ ?c
                            :where [?e :entity/code ?c]]
                          db1 "HOLDCO")
          other-eid  (d/q '[:find ?e . :in $ ?c
                            :where [?e :entity/code ?c]]
                          db1 "OTHERCO")]
      (record-basic conn "d-holdco-1" 100M 50M)
      (record-basic conn "d-other-1"  100M 50M {:entity other
                                                :external-id "d-other-1"})
      (let [period {:from #inst "2024-01-01" :to #inst "2026-01-01"}]
        (is (= ["d-holdco-1"]
               (map :disposal/external-id
                    (disp/disposals-in-period (d/db conn) holdco-eid period))))
        (is (= ["d-other-1"]
               (map :disposal/external-id
                    (disp/disposals-in-period (d/db conn) other-eid period))))))))

(deftest disposals-of-subject
  (let [conn (fresh)]
    (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}])
    (let [eur-eid (d/q '[:find ?c . :where [?c :commodity/symbol "EUR"]] (d/db conn))]
      (record-basic conn "d-eur-1" 100M 50M {:subject eur-eid})
      (record-basic conn "d-eur-2" 200M 80M {:subject eur-eid})
      (record-basic conn "d-usd-1" 300M 100M)
      (is (= 2 (count (disp/disposals-of (d/db conn) eur-eid)))))))

;; ============================================================================
;; §5. realized-gain — proceeds − basis − rollover-deferred
;; ============================================================================

(deftest realized-gain-no-rollover
  (let [conn (fresh)]
    (record-basic conn "d-gain" 100000M 60000M)
    (let [d (disp/pull-disposal (d/db conn) "d-gain")]
      (is (== 40000M (disp/realized-gain d))))))

(deftest realized-gain-with-rollover-deferral
  (let [conn (fresh)]
    (record-basic conn "d-§1031" 100000M 60000M
                  {:rollover {:into-asset [:commodity/symbol "USD"]
                              :amount    30000M
                              :commodity usd
                              :deadline  #inst "2026-06-15"}})
    (let [d (disp/pull-disposal (d/db conn) "d-§1031")]
      ;; proceeds 100k − basis 60k = 40k gain; defer 30k → recognise 10k now
      (is (== 10000M (disp/realized-gain d))))))

(deftest realized-loss
  (let [conn (fresh)]
    (record-basic conn "d-loss" 50000M 100000M)
    (is (== -50000M (disp/realized-gain (disp/pull-disposal (d/db conn) "d-loss"))))))

;; ============================================================================
;; §6. realized-gain-summary grouped by :loss-bucket
;; ============================================================================

(deftest realized-gain-summary-by-bucket
  (let [conn (fresh)]
    (record-basic conn "d-st" 100M 50M {:disposed-on #inst "2025-06-15"
                                        :loss-bucket :st-capital})
    (record-basic conn "d-lt" 500M 200M {:disposed-on #inst "2025-08-15"
                                         :loss-bucket :lt-capital})
    (record-basic conn "d-st-2" 200M 100M {:disposed-on #inst "2025-09-15"
                                           :loss-bucket :st-capital})
    (let [summary (disp/realized-gain-summary (d/db conn)
                                              {:from #inst "2025-01-01"
                                               :to   #inst "2026-01-01"})]
      (is (== 150M (:st-capital summary)) "50 + 100")
      (is (== 300M (:lt-capital summary)) "500 − 200"))))

;; ============================================================================
;; §7. Jurisdiction-specific extension fields round-trip
;; ============================================================================

(deftest de-§8b-participation-shape
  (let [conn (fresh)]
    (record-basic conn "de-§8b"
                  4000000M 2000000M
                  {:subject-kind      :participation
                   :asset-class       :de-§8b-participation
                   :subject-form      :corp
                   :ownership-fraction 0.15M
                   :exemption-claimed [:de-§8b-95pct]
                   :loss-bucket       :de-§8b})
    (let [d (disp/pull-disposal (d/db conn) "de-§8b")]
      (is (= :participation (:disposal/subject-kind d)))
      (is (= :de-§8b-participation (:disposal/asset-class d)))
      (is (= :corp (:disposal/subject-form d)))
      (is (== 0.15M (:disposal/ownership-fraction d)))
      (is (= #{:de-§8b-95pct} (set (:disposal/exemption-claimed d))))
      (is (= :de-§8b (:disposal/loss-bucket d))))))

(deftest us-§1245-depreciation-recapture-shape
  (let [conn (fresh)]
    (record-basic conn "us-§1245"
                  150000M 40000M
                  {:subject-kind       :fixed-asset
                   :asset-class        :us-personal-property-§1245
                   :depreciation-taken {:amount 60000M :commodity usd}
                   :loss-bucket        :§1245-recapture})
    (let [d (disp/pull-disposal (d/db conn) "us-§1245")]
      (is (== 60000M (:disposal/depreciation-taken-amount d))
          "the depreciation US §1245 will recapture as ordinary income"))))

(deftest uk-badr-shape
  (let [conn (fresh)]
    (record-basic conn "uk-badr"
                  1400000M 100000M
                  {:subject-kind       :participation
                   :asset-class        :uk-trading-company-shares
                   :subject-form       :corp
                   :ownership-fraction 0.08M
                   :acquired-on        #inst "2018-09-01"
                   :exemption-claimed  [:uk-badr]})
    (let [d (disp/pull-disposal (d/db conn) "uk-badr")]
      (is (= #{:uk-badr} (set (:disposal/exemption-claimed d))))
      (is (== 0.08M (:disposal/ownership-fraction d))))))

(deftest jp-residence-§35-shape
  (let [conn (fresh)]
    (record-basic conn "jp-residence"
                  60000000M 30000000M
                  {:subject-kind      :real-estate-private
                   :asset-class       :jp-residence-§35
                   :residence?        true
                   :acquired-on       #inst "2010-04-01"
                   :exemption-claimed [:jp-§35-residence]})
    (let [d (disp/pull-disposal (d/db conn) "jp-residence")]
      (is (true? (:disposal/residence? d)))
      (is (= :jp-residence-§35 (:disposal/asset-class d))))))
