(ns kontor.fx.consensus-provider-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.mutation :as mut]
            [kontor.fx.fx-rate-provider :as frp]))

(defn- stub
  ([id rate] (stub id rate []))
  ([id rate period-rates]
   (reify frp/FxRateProvider
     (provider-id [_] id)
     (resolve-rate [_ _] rate)
     (resolve-period-rates [_ _] period-rates))))

(def ^:private exploding
  (reify frp/FxRateProvider
    (provider-id [_] :exploding)
    (resolve-rate [_ _] (throw (ex-info "scaffold not implemented" {})))
    (resolve-period-rates [_ _] (throw (ex-info "scaffold not implemented" {})))))

(def ^:private q
  {:from-commodity "XMR" :to-commodity "EUR" :at-date #inst "2026-06-30"})

(defn- =rate? [a b] (zero? (.compareTo ^java.math.BigDecimal a b)))

;; ---------------------------------------------------------------------------
;; generators

(def ^:private gen-rate
  "A rate in [0.9000, 1.1000] at scale 4."
  (gen/fmap #(BigDecimal/valueOf ^long % 4) (gen/choose 9000 11000)))

(def ^:private gen-rates
  (gen/vector gen-rate 2 7))

;; ---------------------------------------------------------------------------
;; properties

(defspec the-answer-is-always-a-rate-some-source-published 300
  (prop/for-all [rates gen-rates]
                (let [p (apply frp/consensus {:max-spread-bps 100000} (map-indexed stub rates))
                      r (frp/resolve-rate p q)]
                  (and (some? r) (boolean (some #(=rate? r %) rates))))))

(defspec the-answer-does-not-depend-on-the-order-sources-were-given 200
  (prop/for-all [rates gen-rates
                 perm  (gen/vector gen/nat 2 7)]
                (let [ask     (fn [rs] (frp/resolve-rate
                                        (apply frp/consensus {:max-spread-bps 100000}
                                               (map-indexed stub rs))
                                        q))
                      shuffled (mapv second (sort-by first (map vector (cycle perm) rates)))]
                  (=rate? (ask rates) (ask shuffled)))))

(defspec unanimous-sources-are-reported-as-that-rate 200
  (prop/for-all [rate  gen-rate
                 n     (gen/choose 2 6)]
                (let [p (apply frp/consensus {:max-spread-bps 0}
                               (map #(stub % rate) (range n)))]
                  (=rate? rate (frp/resolve-rate p q)))))

(defspec an-answer-never-falls-outside-the-observed-range 300
  (prop/for-all [rates gen-rates
                 bps   (gen/choose 0 100000)]
                (let [p (apply frp/consensus {:max-spread-bps bps} (map-indexed stub rates))]
                  (if-let [r (frp/resolve-rate p q)]
                    (and (<= (.compareTo ^BigDecimal (apply min-key #(.doubleValue ^BigDecimal %) rates) r) 0)
                         (<= (.compareTo ^BigDecimal r (apply max-key #(.doubleValue ^BigDecimal %) rates)) 0))
                    true))))

(defspec fewer-answering-sources-than-required-is-no-opinion 200
  (prop/for-all [rates gen-rates]
                (let [p (apply frp/consensus {:min-sources (inc (count rates)) :max-spread-bps 100000}
                               (map-indexed stub rates))]
                  (nil? (frp/resolve-rate p q)))))

;; ---------------------------------------------------------------------------
;; worked cases

(deftest median-of-agreeing-sources-is-the-answer
  (let [p (frp/consensus {:max-spread-bps 2000}
                         (stub :a 1.00M) (stub :b 1.05M) (stub :c 1.08M)
                         (stub :d 1.10M) (stub :e 1.20M))]
    (is (=rate? 1.08M (frp/resolve-rate p q)))))

(deftest an-even-count-takes-the-lower-of-the-two-central-values
  (let [p (frp/consensus {:max-spread-bps 500} (stub :a 1.00M) (stub :b 1.02M))]
    (is (=rate? 1.00M (frp/resolve-rate p q)))))

(deftest identity-short-circuits-before-any-source-is-polled
  (let [p (frp/consensus {:max-spread-bps 1} exploding)]
    (is (=rate? 1M (frp/resolve-rate p {:from-commodity "EUR"
                                        :to-commodity   "EUR"
                                        :at-date        #inst "2026-06-30"})))))

(deftest disagreement-beyond-the-band-refuses-rather-than-picking
  (let [p (frp/consensus {:max-spread-bps 1000}
                         (stub :a 1.00M) (stub :b 1.08M) (stub :c 1.20M))]
    (is (nil? (frp/resolve-rate p q)))))

(deftest the-band-is-inclusive-at-its-edge
  (let [sources [(stub :a 1.00M) (stub :b 1.02M)]]
    (is (some? (frp/resolve-rate (apply frp/consensus {:max-spread-bps 200} sources) q)))
    (is (nil? (frp/resolve-rate (apply frp/consensus {:max-spread-bps 199} sources) q)))))

(deftest a-zero-rate-is-unavailability-not-a-price
  (let [p (frp/consensus {:min-sources 3 :max-spread-bps 5000}
                         (stub :a 1.00M) (stub :b 1.02M) (stub :c 0M))]
    (is (nil? (frp/resolve-rate p q)))))

(deftest the-band-must-be-declared
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":max-spread-bps"
                        (frp/consensus {} (stub :a 1M) (stub :b 1M)))))

(deftest a-consensus-of-one-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":min-sources"
                        (frp/consensus {:min-sources 1 :max-spread-bps 10} (stub :a 1M)))))

(deftest a-scaffold-in-the-set-explodes-exactly-as-it-does-in-a-chain
  (let [p (frp/consensus {:max-spread-bps 500} (stub :a 1.00M) exploding)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scaffold not implemented"
                          (frp/resolve-rate p q)))))

(deftest provider-id-is-consensus
  (is (= :consensus (frp/provider-id (frp/consensus {:max-spread-bps 500}
                                                    (stub :a 1M) (stub :b 1M))))))

(deftest period-rates-reach-consensus-per-date
  (let [d1 #inst "2026-06-01"
        d2 #inst "2026-06-02"
        p  (frp/consensus {:max-spread-bps 5000}
                          (stub :a nil [{:at-date d1 :rate 1.00M} {:at-date d2 :rate 2.00M}])
                          (stub :b nil [{:at-date d1 :rate 1.04M} {:at-date d2 :rate 2.04M}]))
        rs (frp/resolve-period-rates p (assoc q :from-date d1 :to-date d2))]
    (is (= [d1 d2] (mapv :at-date rs)))
    (is (=rate? 1.00M (:rate (first rs))))))

(deftest a-date-only-one-source-covers-is-dropped
  (let [d1 #inst "2026-06-01"
        d2 #inst "2026-06-02"
        p  (frp/consensus {:max-spread-bps 5000}
                          (stub :a nil [{:at-date d1 :rate 1.00M} {:at-date d2 :rate 2.00M}])
                          (stub :b nil [{:at-date d1 :rate 1.04M}]))]
    (is (= [d1] (mapv :at-date (frp/resolve-period-rates p (assoc q :from-date d1 :to-date d2)))))))

;; ---------------------------------------------------------------------------
;; mutation — the assertions above must kill each of these

(mut/deftest-mutations lower-median-mutations-are-caught
  kontor.fx.fx-rate-provider/lower-median
  [["returns-the-minimum" (fn [sorted] (first sorted))]
   ["returns-the-maximum" (fn [sorted] (peek sorted))]
   ["off-by-one-upper"    (fn [sorted] (nth sorted (quot (count sorted) 2)))]]
  (fn []
    (is (=rate? 1.08M (frp/resolve-rate
                       (frp/consensus {:max-spread-bps 2000}
                                      (stub :a 1.00M) (stub :b 1.05M) (stub :c 1.08M)
                                      (stub :d 1.10M) (stub :e 1.20M))
                       q)))
    (is (=rate? 1.00M (frp/resolve-rate
                       (frp/consensus {:max-spread-bps 500}
                                      (stub :a 1.00M) (stub :b 1.02M))
                       q)))))

(mut/deftest-mutations within-band-mutations-are-caught
  kontor.fx.fx-rate-provider/within-band?
  [["always-agrees"       (fn [_ _ _] true)]
   ["ignores-the-band"    (fn [sorted _ _] (boolean (seq sorted)))]
   ["strict-at-the-edge"  (fn [sorted consensus bps]
                            (neg? (.compareTo ^BigDecimal
                                   (.multiply ^BigDecimal
                                    (.subtract ^BigDecimal (peek sorted)
                                               ^BigDecimal (first sorted))
                                              (BigDecimal/valueOf 10000))
                                              (.multiply ^BigDecimal consensus
                                                         (BigDecimal/valueOf (long bps))))))]]
  (fn []
    (is (nil? (frp/resolve-rate
               (frp/consensus {:max-spread-bps 1000}
                              (stub :a 1.00M) (stub :b 1.08M) (stub :c 1.20M))
               q)))
    (is (some? (frp/resolve-rate
                (frp/consensus {:max-spread-bps 200}
                               (stub :a 1.00M) (stub :b 1.02M))
                q)))))

(mut/deftest-mutations observed-rates-mutations-are-caught
  kontor.fx.fx-rate-provider/observed-rates
  [["keeps-zero-rates"  (fn [providers q']
                          (vec (sort (keep #(frp/resolve-rate % q') providers))))]
   ["does-not-sort"     (fn [providers q']
                          (vec (keep #(let [r (frp/resolve-rate % q')]
                                        (when (and r (not (zero? (.compareTo ^BigDecimal r 0M)))) r))
                                     providers)))]]
  (fn []
    ;; a dropped zero leaves an in-band pair; a kept zero widens the spread
    ;; past the band and the provider would fall silent instead
    (is (=rate? 1.00M (frp/resolve-rate
                       (frp/consensus {:max-spread-bps 5000}
                                      (stub :z 0M) (stub :a 1.00M) (stub :b 1.02M))
                       q)))
    ;; presented out of order, so an unsorted median lands on 1.20M
    (is (=rate? 1.08M (frp/resolve-rate
                       (frp/consensus {:max-spread-bps 2000}
                                      (stub :a 1.00M) (stub :c 1.08M) (stub :e 1.20M)
                                      (stub :b 1.05M) (stub :d 1.10M))
                       q)))))

;; ---------------------------------------------------------------------------
;; a commodity with no reference-rate authority

(def ^:private xmr-q
  {:from-commodity "XMR" :to-commodity "EUR" :at-date #inst "2026-06-30"})

(deftest tickers-that-agree-price-a-commodity-with-no-central-bank
  (let [p (frp/consensus {:min-sources 3 :max-spread-bps 200}
                         (stub :ticker-a 142.310000000000M)
                         (stub :ticker-b 142.550000000000M)
                         (stub :ticker-c 142.480000000000M))]
    (is (=rate? 142.480000000000M (frp/resolve-rate p xmr-q)))))

(deftest one-stale-ticker-silences-the-quote-rather-than-skewing-it
  (let [p (frp/consensus {:min-sources 3 :max-spread-bps 200}
                         (stub :ticker-a 142.310000000000M)
                         (stub :ticker-b 142.550000000000M)
                         (stub :stale    119.000000000000M))]
    (is (nil? (frp/resolve-rate p xmr-q)))))

(deftest a-twelve-decimal-rate-survives-the-provider-unrounded
  (let [rate 0.000000000123M
        p    (frp/consensus {:max-spread-bps 0}
                            (stub :a rate) (stub :b rate) (stub :c rate))
        out  (frp/resolve-rate p xmr-q)]
    (is (=rate? rate out))
    (is (= 12 (.scale ^BigDecimal out)))))
