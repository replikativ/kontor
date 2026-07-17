(ns kontor.book-build-cljs-test
  "Phase-C acceptance (note 192, rung 1): the browser materializes balanced
   posting tx-data from a friendly verb map, client-side, via
   `kontor.book.build/entry-tx-data` — pure, no db. Exercises the extracted
   builder chain (book.build -> posting.build -> bitemporal + posting.validate)
   and money's cljs Bigdec arithmetic (the credit leg is negate-amount of the
   debit; the two legs sum to zero)."
  (:require [cljs.test :refer [deftest is]]
            [kontor.book.build :as bb]
            [kontor.money :as money]))

(defn- postings-of [tx-data] (filter :kontor.posting/amount tx-data))
(defn- tx-base-of  [tx-data] (first (filter :kontor.transaction/journal tx-data)))

(defn- check-balance [ps]
  (let [by (money/sum-by-commodity (map money/posting->money ps))]
    (is (= 1 (count by)) "single commodity")
    (is (money/zero? (first (vals by)))
        "debit + credit sum to zero — Bigdec arithmetic in the browser"))
  (let [amts (sort (map #(money/money->str (money/posting->money %)) ps))]
    (is (= ["-100.00 [:kontor.commodity/symbol \"EUR\"]"
            "100.00 [:kontor.commodity/symbol \"EUR\"]"]
           amts)
        "debit +100.00, credit -100.00 (credit = negate-amount of debit)")))

(deftest two-leg-entry-builds-balanced-sealed-tx-data
  (let [tx (bb/entry-tx-data {:journal        [:kontor.journal/code "CASH"]
                              :effective-date #inst "2026-03-15"
                              :debit-account  [:kontor.account/path "Assets:Cash"]
                              :credit-account [:kontor.account/path "Income:Sales"]
                              :amount "100.00" :commodity :EUR})
        ps (postings-of tx)
        base (tx-base-of tx)]
    (is (= 2 (count ps)) "two posting entities")
    (is (= :posted (:kontor.transaction/state base)) "sealed: state :posted")
    (is (some? (:kontor.transaction/posted-at base)) ":posted-at stamped")
    (is (= [:kontor.commodity/symbol "EUR"] (:kontor.posting/commodity (first ps)))
        "bare :EUR auto-promoted to the canonical lookup-ref")
    (check-balance ps)))

(deftest multi-leg-with-dimensions
  (let [tx (bb/entry-tx-data {:journal :GEN :effective-date #inst "2026-03-15" :commodity :EUR
                              :postings [{:account :exp :amount "60.00"
                                          :dimensions {:cost-centre :ops :project "P1"}}
                                         {:account :cash :amount "-60.00"}]})
        ps (postings-of tx)
        dims (:kontor.posting/dimensions (first (filter :kontor.posting/dimensions ps)))]
    (is (= 2 (count ps)))
    (is (= #{{:kontor.posting-dimension/axis :cost-centre :kontor.posting-dimension/value "ops"}
             {:kontor.posting-dimension/axis :project     :kontor.posting-dimension/value "P1"}}
           (set dims))
        "friendly {axis value} dimensions map -> :posting-dimension entities (ADR-097)")
    (let [by (money/sum-by-commodity (map money/posting->money ps))]
      (is (money/zero? (first (vals by))) "multi-leg sums to zero"))))
