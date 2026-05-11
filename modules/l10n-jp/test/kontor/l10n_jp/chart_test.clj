(ns kontor.l10n-jp.chart-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.chart :as chart]))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?c ...] :where [_ :account/code ?c]] db))]
    (is (>= n 30) (str "loaded " n " accounts"))
    (testing "Key accounts present"
      (is (ace db "121000") "Accounts receivable")
      (is (ace db "215100") "Output JCT 10% liability")
      (is (ace db "215200") "Output JCT 8% liability")
      (is (ace db "180100") "Input JCT 10% asset")
      (is (ace db "411000") "Sales 10% revenue")
      (is (ace db "412000") "Sales 8% revenue"))))

(deftest jpy-commodity-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        jpy (d/entity db [:commodity/symbol "JPY"])]
    (is jpy)
    (is (= 0 (:commodity/precision jpy))
        "JPY has no fractional digits (commodity-precision)")
    (is (= "JPY" (:commodity/iso-4217 jpy)))))

(deftest jct-tags-installed
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        tag-names (set (d/q '[:find [?n ...] :where [_ :account-tag/name ?n]] db))]
    (is (contains? tag-names "jp-jct-line-sales-10"))
    (is (contains? tag-names "jp-jct-line-sales-8"))
    (is (contains? tag-names "jp-jct-line-output-10"))
    (is (contains? tag-names "jp-jct-line-input-10"))))
