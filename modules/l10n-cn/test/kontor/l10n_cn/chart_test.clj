(ns kontor.l10n-cn.chart-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.chart :as chart]))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?c ...] :where [_ :kontor.account/code ?c]] db))]
    (is (>= n 50) (str "loaded " n " accounts — expanded with MOF-canonical 2221 sub-tree"))
    (testing "Key ASBE-coded accounts present"
      (is (ace db "1001") "Cash on hand 库存现金")
      (is (ace db "1002") "Bank deposits 银行存款")
      (is (ace db "1122") "Accounts receivable")
      (is (ace db "2202") "Accounts payable")
      (is (ace db "2221") "Taxes payable parent"))
    (testing "MOF-canonical 2221 sub-tree (corrected 2026-05-11)"
      (is (ace db "2221.01.01") "销项税额 Output VAT (single account, no per-rate split)")
      (is (ace db "2221.01.02") "进项税额 Input VAT")
      (is (ace db "2221.01.04") "进项税额转出 Input VAT reversal")
      (is (ace db "2221.02") "未交增值税 Unpaid VAT")
      (is (ace db "2221.07") "留抵税额 Retained credit")
      (is (ace db "2221.12") "城建税 UMCT")
      (is (ace db "2221.13") "教育费附加 Education surcharge"))))

(deftest cny-commodity
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        cny (d/entity db [:kontor.commodity/symbol "CNY"])]
    (is (= 2 (:kontor.commodity/precision cny)))
    (is (= "CNY" (:kontor.commodity/iso-4217 cny)))))

(deftest vat-tags-installed
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        names (set (d/q '[:find [?n ...] :where [_ :kontor.account-tag/name ?n]] db))]
    (testing "Per-rate sales tags (revenue side)"
      (is (contains? names "cn-vat-line-sales-13"))
      (is (contains? names "cn-vat-line-sales-9"))
      (is (contains? names "cn-vat-line-sales-6")))
    (testing "Output / input VAT tags (MOF-canonical: single account per side, rate via posting)"
      (is (contains? names "cn-vat-output"))
      (is (contains? names "cn-vat-input")))
    (testing "Surcharge tags"
      (is (contains? names "cn-surcharge-umct"))
      (is (contains? names "cn-surcharge-edu"))
      (is (contains? names "cn-surcharge-local-edu")))))

(deftest external-codes-installed
  (testing "ADR-019 external-codes — ASBE statutory codes attached"
    (let [conn (core/create-test-db)
          _ (chart/install! conn)
          db (d/db conn)
          ;; The 1001 account's ASBE external code should resolve via
          ;; the composite identity [account, regulator].
          cash-id (ace db "1001")
          asbe-codes (d/q '[:find [?code ...]
                            :in $ ?acc
                            :where
                            [?ec :kontor.account-code/account ?acc]
                            [?ec :kontor.account-code/regulator :cn/asbe]
                            [?ec :kontor.account-code/code ?code]]
                          db cash-id)]
      (is (= ["1001"] asbe-codes)
          "Account 1001 should have ASBE code 1001 attached"))))
