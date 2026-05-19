(ns kontor.import-edgar.core-test
  "Tests for kontor-import-edgar — SEC companyfacts ingest +
   bitemporal-restatement supersession."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.import-edgar.core :as edgar]
            [kontor.import-edgar.schema :as edgar-schema])
  (:import [java.util Date]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (edgar-schema/install! conn)
    (d/transact conn
                [{:entity/code "APPLE"
                  :entity/name "Apple Inc."
                  :entity/active true}])
    conn))

(defn- apple-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "APPLE"]] (d/db conn)))

;; ============================================================================
;; Parser tests
;; ============================================================================

(deftest parse-companyfacts-fixture-yields-fact-rows
  (let [json-str (slurp (io/resource
                         "kontor/import_edgar/fixtures/apple-companyfacts-2008-2012.json"))
        facts (vec (edgar/parse-companyfacts json-str))]
    (testing "parser returns a non-empty seq"
      (is (pos? (count facts))))
    (testing "every fact has the expected shape"
      (let [sample (first facts)]
        (is (every? sample [:cik :entity-name :taxonomy :concept
                            :concept-iri :unit :end :val :accn :form :filed]))))
    (testing "the AccruedLiabilities 10-K/A restatement is present in the fixture"
      (let [accr (filter #(= "us-gaap:AccruedLiabilitiesCurrent"
                             (:concept-iri %))
                         facts)
            for-2008-fy (filter #(= "2008-09-27" (:end %)) accr)
            forms (set (map :form for-2008-fy))]
        (is (contains? forms "10-K"))
        (is (contains? forms "10-K/A"))
        (testing "values differ between 10-K and 10-K/A (restatement)"
          (let [orig (->> for-2008-fy (filter #(= "10-K" (:form %))) first)
                amended (->> for-2008-fy (filter #(= "10-K/A" (:form %))) first)]
            (is (= 3719000000 (long (:val orig))))
            (is (= 4224000000 (long (:val amended))))))))))

;; ============================================================================
;; Ingest tests — supersession + bitemporal valid-time
;; ============================================================================

(deftest ingest-a-single-fact-creates-one-reported-fact-row
  (let [conn (bootstrap)
        eid (apple-eid conn)
        fact {:cik 320193 :entity-name "Apple Inc."
              :taxonomy :us-gaap :concept :AccruedLiabilitiesCurrent
              :concept-iri "us-gaap:AccruedLiabilitiesCurrent"
              :unit :usd
              :end "2009-09-26" :val 3376000000
              :accn "0001193125-09-214859" :form "10-K"
              :filed "2009-10-27"}
        result (edgar/ingest-facts! conn [fact]
                                    {:entity-eid eid
                                     :source "edgar://test"})]
    (testing "summary reports one ingested, zero superseded, zero skipped"
      (is (= 1 (:ingested result)))
      (is (= 0 (:superseded result)))
      (is (= 0 (:skipped result))))
    (testing "one :reported-fact row landed"
      (let [n (count (d/q '[:find [?f ...]
                            :where [?f :reported-fact/external-id]]
                          (d/db conn)))]
        (is (= 1 n))))))

(deftest re-ingesting-the-same-fact-is-a-noop
  (let [conn (bootstrap)
        eid (apple-eid conn)
        fact {:cik 320193 :entity-name "Apple Inc."
              :taxonomy :us-gaap :concept :AccruedLiabilitiesCurrent
              :concept-iri "us-gaap:AccruedLiabilitiesCurrent"
              :unit :usd
              :end "2009-09-26" :val 3376000000
              :accn "0001193125-09-214859" :form "10-K"
              :filed "2009-10-27"}]
    (edgar/ingest-facts! conn [fact] {:entity-eid eid :source "test"})
    (let [r (edgar/ingest-facts! conn [fact] {:entity-eid eid :source "test"})]
      (is (= 0 (:ingested r)))
      (is (= 1 (:skipped r))))
    (testing "still one :reported-fact row"
      (is (= 1 (count (d/q '[:find [?f ...]
                             :where [?f :reported-fact/external-id]]
                           (d/db conn))))))))

(deftest restatement-records-supersession-chain-and-closes-prior-vt-window
  (let [conn (bootstrap)
        eid (apple-eid conn)
        original {:cik 320193 :entity-name "Apple Inc."
                  :taxonomy :us-gaap :concept :AccruedLiabilitiesCurrent
                  :concept-iri "us-gaap:AccruedLiabilitiesCurrent"
                  :unit :usd
                  :end "2008-09-27" :val 3719000000
                  :accn "0001193125-09-214859" :form "10-K"
                  :filed "2009-10-27"}
        amended (assoc original
                       :val 4224000000
                       :accn "0001193125-10-012091"
                       :form "10-K/A"
                       :filed "2010-01-25")]
    ;; Ingest original 10-K
    (edgar/ingest-facts! conn [original] {:entity-eid eid :source "test"})
    ;; Ingest 10-K/A amendment
    (let [r (edgar/ingest-facts! conn [amended] {:entity-eid eid :source "test"})]
      (is (= 1 (:ingested r)))
      (is (= 1 (:superseded r))))

    (testing "two :reported-fact rows exist now"
      (is (= 2 (count (d/q '[:find [?f ...]
                             :where [?f :reported-fact/external-id]]
                           (d/db conn))))))

    (testing "the original carries :superseded-by ref to the amended"
      (let [orig-eid (d/q '[:find ?f . :in $ ?accn
                            :where [?f :reported-fact/accession-number ?accn]]
                          (d/db conn) "0001193125-09-214859")
            amend-eid (d/q '[:find ?f . :in $ ?accn
                             :where [?f :reported-fact/accession-number ?accn]]
                           (d/db conn) "0001193125-10-012091")
            orig (d/pull (d/db conn) [:reported-fact/superseded-by] orig-eid)]
        (is (= amend-eid
               (-> orig :reported-fact/superseded-by :db/id)))))

    (testing "current-fact returns the amended value"
      (let [head (edgar/current-fact conn eid "us-gaap:AccruedLiabilitiesCurrent"
                                     #inst "2008-09-27" :usd)]
        (is (= 4224000000M (:reported-fact/value-bigdec head)))
        (is (= "10-K/A" (:reported-fact/form head)))))

    (testing "fact-history returns both in chronological order"
      (let [hist (edgar/fact-history conn eid "us-gaap:AccruedLiabilitiesCurrent"
                                     #inst "2008-09-27" :usd)]
        (is (= 2 (count hist)))
        (is (= [3719000000M 4224000000M]
               (mapv :reported-fact/value-bigdec hist)))
        (is (= ["10-K" "10-K/A"]
               (mapv :reported-fact/form hist)))))

    (testing "bitemporal :as-of-valid BEFORE amendment returns ORIGINAL fact"
      ;; Query at 2009-12-01 (between original 2009-10-27 and amend 2010-01-25):
      ;; the prior tx is still in its valid window, the amendment's tx
      ;; isn't visible yet → see the original 10-K value.
      (let [before-amendment #inst "2009-12-01"
            db (d/valid-at (d/db conn) before-amendment)
            n  (count (d/q '[:find [?f ...]
                             :where [?f :reported-fact/external-id]]
                           db))]
        (is (= 1 n) "only one fact visible at the pre-amendment timestamp")
        (let [head (->> (d/q '[:find [?f ...]
                               :where [?f :reported-fact/external-id]]
                             db)
                        (mapv #(d/pull db '[*] %))
                        first)]
          (is (= 3719000000M (:reported-fact/value-bigdec head))
              "original 10-K value at pre-amendment vt"))))

    (testing "bitemporal :as-of-valid AFTER amendment returns AMENDED fact"
      (let [after-amendment #inst "2010-02-01"
            db (d/valid-at (d/db conn) after-amendment)
            head (->> (d/q '[:find [?f ...]
                             :where [?f :reported-fact/external-id]]
                           db)
                      (mapv #(d/pull db '[*] %))
                      ;; The original's tx-vt has been closed at the
                      ;; amendment's filed date, so only the amendment's
                      ;; fact appears in the valid-at view.
                      first)]
        (is (some? head))
        (is (= 4224000000M (:reported-fact/value-bigdec head))
            "amended 10-K/A value at post-amendment vt")))))

(deftest full-fixture-ingest-records-multiple-supersessions
  (let [conn (bootstrap)
        eid (apple-eid conn)
        json-str (slurp (io/resource
                         "kontor/import_edgar/fixtures/apple-companyfacts-2008-2012.json"))
        facts (edgar/parse-companyfacts json-str)
        ;; Only ingest AccruedLiabilities + AccumulatedOCI to keep the
        ;; test focused on the canonical restatements
        focused (filter #(#{"us-gaap:AccruedLiabilitiesCurrent"
                            "us-gaap:AccumulatedOtherComprehensiveIncomeLossNetOfTax"}
                          (:concept-iri %))
                        facts)
        result (edgar/ingest-facts! conn focused
                                    {:entity-eid eid
                                     :source "edgar://test/apple-2008-2012"})]
    (testing "supersession count > 0 (multiple restatement events in window)"
      (is (pos? (:superseded result))))
    (testing "ingested count == distinct (concept, end, unit, accession) quads"
      (is (= (count focused)
             (+ (:ingested result) (:skipped result)))))
    (testing "AccumulatedOCI for FY2008 end exhibits the sign-flip restatement"
      ;; Original 10-K: +$8M; 10-K/A: -$9M. The bitemporal head at
      ;; post-amendment time should be the -$9M value.
      (let [head (edgar/current-fact
                  conn eid
                  "us-gaap:AccumulatedOtherComprehensiveIncomeLossNetOfTax"
                  #inst "2008-09-27" :usd)]
        (is (some? head))
        ;; Apple AccumulatedOCI restatement; sign flipped per the
        ;; ASC 605-25 revenue-recognition restatement narrative.
        (is (neg? (.signum ^java.math.BigDecimal
                           (:reported-fact/value-bigdec head)))
            "amended value is negative (sign flipped from +8M to -9M)")))))
