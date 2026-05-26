(ns kontor.l10n-au.chart-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.chart :as chart]))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?c ...] :where [_ :account/code ?c]] db))]
    (is (>= n 30) (str "loaded " n " accounts"))
    (is (ace db "11200") "Trade debtors")
    (is (ace db "11700") "GST receivable")
    (is (ace db "21500") "GST payable")
    (is (ace db "41100") "Taxable sales")
    (is (ace db "51200") "Capital purchases")))

(deftest aud-commodity
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        aud (d/entity db [:kontor.commodity/symbol "AUD"])]
    (is (= 2 (:kontor.commodity/precision aud)))
    (is (= "AUD" (:kontor.commodity/iso-4217 aud)))))

(deftest bas-tags-installed
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        names (set (d/q '[:find [?n ...] :where [_ :account-tag/name ?n]] db))]
    (is (contains? names "au-bas-1a-gst"))
    (is (contains? names "au-bas-1b-itc"))
    (is (contains? names "au-bas-g1-total-sales"))
    (is (contains? names "au-bas-w1-total-wages"))))
