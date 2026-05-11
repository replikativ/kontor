(ns kontor.l10n-br.chart-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.chart :as chart]))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?c ...] :where [_ :account/code ?c]] db))]
    (is (>= n 40) (str "loaded " n " accounts"))
    (testing "Key dotted-code accounts present"
      (is (ace db "1.01.01.01.01") "Caixa Matriz")
      (is (ace db "1.01.01.02.01") "Bancos Conta Movimento – No País")
      (is (ace db "1.01.05.01.01") "ICMS a Recuperar")
      (is (ace db "2.01.04.01.01") "ICMS a Recolher")
      (is (ace db "2.01.04.02.01") "CBS a Recolher (post-2026)")
      (is (ace db "3.01.01.01.01") "Receita Bruta Mercadorias"))))

(deftest brl-commodity
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        brl (d/entity db [:commodity/symbol "BRL"])]
    (is (= 2 (:commodity/precision brl)))
    (is (= "BRL" (:commodity/iso-4217 brl)))))

(deftest legacy-and-new-vat-tags-installed
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        names (set (d/q '[:find [?n ...] :where [_ :account-tag/name ?n]] db))]
    (testing "Legacy ICMS/IPI/PIS/COFINS/ISS"
      (is (contains? names "br-icms-output"))
      (is (contains? names "br-icms-input"))
      (is (contains? names "br-ipi-output"))
      (is (contains? names "br-pis-output"))
      (is (contains? names "br-cofins-output"))
      (is (contains? names "br-iss-output")))
    (testing "New CBS/IBS dual-VAT (post-2026 reform scaffold)"
      (is (contains? names "br-cbs-output"))
      (is (contains? names "br-ibs-output")))))

(deftest plano-referencial-external-codes
  (testing "ADR-019: ECF De/Para mapping present"
    (let [conn (core/create-test-db)
          _ (chart/install! conn)
          db (d/db conn)
          ;; The 1.01.01.01.01 (Caixa Matriz) account should have a
          ;; Plano Referencial external code attached.
          eid (ace db "1.01.01.01.01")
          codes (d/q '[:find [?code ...]
                       :in $ ?acc
                       :where
                       [?ec :account-code/account ?acc]
                       [?ec :account-code/regulator :br/plano-referencial]
                       [?ec :account-code/code ?code]]
                     db eid)]
      (is (= ["L100A_1.01.01.01.01"] codes)))))
