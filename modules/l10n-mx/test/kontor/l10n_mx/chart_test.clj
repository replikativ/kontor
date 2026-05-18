(ns kontor.l10n-mx.chart-test
  "Tests for kontor.l10n-mx.chart — SAT Código Agrupador starter chart.

   Covers:
     - MXN commodity is created
     - All five accounting classes (Activo / Pasivo / Capital /
       Ingresos / Costos+Gastos) have at least one account
     - Cash-basis IVA structure: cobrado + no-cobrado pairs on both
       output (208.01/208.02) and input (119.01/119.02) sides
     - IEPS holding accounts (output + input) are present
     - Retención accounts (ISR / IVA — payable + receivable)
     - Utilidades Retenidas equity account (the close target)
     - Tags materialise as :account-tag entities with country-code MX
     - install! is idempotent."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.chart :as chart]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (chart/install! conn)
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

;; ============================================================================
;; MXN commodity
;; ============================================================================

(deftest mxn-commodity-installed
  (testing "Installing the chart creates the MXN commodity."
    (let [conn (bootstrap)
          db (d/db conn)
          mxn (d/entity db [:commodity/symbol "MXN"])]
      (is (some? mxn))
      (is (= "Peso Mexicano" (:commodity/name mxn)))
      (is (= 2 (:commodity/precision mxn)))
      (is (= "MXN" (:commodity/iso-4217 mxn))))))

;; ============================================================================
;; All five classes covered
;; ============================================================================

(deftest sat-classes-covered
  (testing "Every SAT class (Activo / Pasivo / Capital / Ingresos /
            Costos / Gastos) has at least one account."
    (let [conn (bootstrap)
          db (d/db conn)
          types (set (d/q '[:find [?t ...] :where [_ :account/type ?t]] db))]
      (is (contains? types :asset))
      (is (contains? types :equity))
      (is (contains? types :liability))
      (is (contains? types :income))
      (is (contains? types :expense)))))

(deftest account-count-is-reasonable
  (testing "Starter chart loads at least 60 accounts."
    (let [conn (bootstrap)
          db (d/db conn)
          n (count (d/q '[:find [?a ...] :where [?a :account/code _]] db))]
      (is (>= n 60) (str "loaded " n " accounts")))))

;; ============================================================================
;; Cash-basis IVA — the key MX-specific structure
;; ============================================================================

(deftest iva-output-cobrado-no-cobrado-pairs
  (testing "Output IVA splits into 208.01.xxx (cobrado, owed to SAT)
            and 208.02.xxx (no cobrado, pending receipt). Each rate
            (16% / 8% / 0%) has both."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/iva-trasladado-cobrado-16-code)
          "IVA trasladado cobrado 16% (208.01.001)")
      (is (ace db chart/iva-trasladado-cobrado-8-code)
          "IVA trasladado cobrado 8% frontera (208.01.002)")
      (is (ace db chart/iva-trasladado-cobrado-0-code)
          "IVA trasladado cobrado 0% (208.01.003)")
      (is (ace db chart/iva-trasladado-no-cobrado-16-code)
          "IVA trasladado NO cobrado 16% (208.02.001)")
      (is (ace db chart/iva-trasladado-no-cobrado-8-code)
          "IVA trasladado NO cobrado 8% (208.02.002)")
      (is (ace db chart/iva-trasladado-no-cobrado-0-code)
          "IVA trasladado NO cobrado 0% (208.02.003)"))))

(deftest iva-input-pagado-pendiente-pairs
  (testing "Input IVA mirrors the output split: 119.01.xxx (pagado,
            recoverable now) and 119.02.xxx (pendiente, pending
            payment to supplier)."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/iva-acreditable-pagado-16-code)
          "IVA acreditable pagado 16% (119.01.001)")
      (is (ace db chart/iva-acreditable-pagado-8-code)
          "IVA acreditable pagado 8% (119.01.002)")
      (is (ace db chart/iva-acreditable-pendiente-16-code)
          "IVA acreditable pendiente 16% (119.02.001)"))))

(deftest ieps-accounts-present
  (testing "IEPS (federal excise) accounts: output cobrado /
            no-cobrado on the seller side, input acreditable on the
            manufacturer side."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/ieps-trasladado-cobrado-code)
          "IEPS trasladado cobrado (209.01.001)")
      (is (ace db chart/ieps-trasladado-no-cobrado-code)
          "IEPS trasladado NO cobrado (209.02.001)")
      (is (ace db chart/ieps-acreditable-code)
          "IEPS acreditable pagado (216.01.001)"))))

(deftest retencion-accounts-present
  (testing "Retención (withholding) pairs: ISR + IVA on both the
            buyer-as-withholder side (payable) and the supplier side
            (receivable)."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/isr-retenido-pagar-honorarios-code)
          "ISR retenido por pagar — honorarios (206.01.001)")
      (is (ace db chart/iva-retenido-pagar-code)
          "IVA retenido por pagar (206.02.001)")
      (is (ace db chart/isr-retenido-cobrar-code)
          "ISR retenido por cobrar (120.01.001)")
      (is (ace db chart/iva-retenido-cobrar-code)
          "IVA retenido por cobrar (120.02.001)"))))

;; ============================================================================
;; IVA output accounts are liabilities; input ITC are assets
;; ============================================================================

(deftest output-iva-accounts-are-liabilities
  (testing "208.xx.xxx accounts (output IVA) classify as :liability."
    (let [conn (bootstrap)
          db (d/db conn)]
      (doseq [code [chart/iva-trasladado-cobrado-16-code
                    chart/iva-trasladado-cobrado-8-code
                    chart/iva-trasladado-no-cobrado-16-code]]
        (let [e (d/entity db (ace db code))]
          (is (= :liability (:account/type e))
              (str code " should be :liability")))))))

(deftest input-iva-accounts-are-assets
  (testing "119.xx.xxx accounts (input ITC) classify as :asset."
    (let [conn (bootstrap)
          db (d/db conn)]
      (doseq [code [chart/iva-acreditable-pagado-16-code
                    chart/iva-acreditable-pendiente-16-code]]
        (let [e (d/entity db (ace db code))]
          (is (= :asset (:account/type e))
              (str code " should be :asset")))))))

;; ============================================================================
;; Utilidades Retenidas — the year-end close target
;; ============================================================================

(deftest utilidades-retenidas-present
  (testing "305.01.001 (Utilidades Retenidas / Resultados de
            Ejercicios Anteriores) is the canonical year-end
            close target on the equity side."
    (let [conn (bootstrap)
          db (d/db conn)
          retained (d/entity db (ace db chart/utilidades-retenidas-code))]
      (is (some? retained))
      (is (= :equity (:account/type retained))))))

(deftest utilidad-del-ejercicio-present
  (testing "304.01.001 (Utilidad del Ejercicio — current year profit)
            distinct from Utilidades Retenidas (prior years)."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (ace db chart/utilidades-ejercicio-code)))))

;; ============================================================================
;; Reconcilable flag
;; ============================================================================

(deftest reconcilable-flag-applied
  (testing "Clientes / Proveedores / Bancos / Caja are flagged
            :reconcilable? true; income/expense are not."
    (let [conn (bootstrap)
          db (d/db conn)
          reconcilable? (fn [code]
                          (:account/reconcilable
                           (d/entity db (ace db code))))]
      (is (reconcilable? chart/ar-code))
      (is (reconcilable? chart/ap-code))
      (is (reconcilable? chart/bank-code))
      (is (reconcilable? chart/cash-code))
      (is (not (reconcilable? chart/sales-domestic-16-code))))))

;; ============================================================================
;; Tags materialise with country-code MX
;; ============================================================================

(deftest tags-materialise-with-country-code
  (testing "Every distinct :tags keyword from the chart becomes an
            :account-tag entity with :account-tag/country-code MX."
    (let [conn (bootstrap)
          db (d/db conn)
          tag-countries (d/q '[:find [?cc ...]
                               :where
                               [_ :account-tag/name _]
                               [?t :account-tag/country-code ?cc]]
                             db)]
      (is (some #{"MX"} tag-countries)
          "At least one MX-scoped tag entity exists"))))

(deftest output-iva-accounts-have-cobrado-tag
  (testing ":mx-dpi-iva-cobrado is the aggregation tag for output
            IVA on the cobrado (cash-recognised) side. Used by the
            DPI return aggregator."
    (let [conn (bootstrap)
          db (d/db conn)
          acct (d/entity db (ace db chart/iva-trasladado-cobrado-16-code))
          tag-names (set (map :account-tag/name (:account/tags acct)))]
      (is (contains? tag-names "mx-dpi-iva-cobrado")))))

;; ============================================================================
;; Idempotency
;; ============================================================================

(deftest install-is-idempotent
  (testing "Running install! twice produces the same chart shape."
    (let [conn (bootstrap)
          before (d/q '[:find ?c :where [_ :account/code ?c]] (d/db conn))
          _ (chart/install! conn)
          after (d/q '[:find ?c :where [_ :account/code ?c]] (d/db conn))]
      (is (= before after)
          "Re-running install! does not duplicate accounts"))))

;; ============================================================================
;; account-by-code lookup
;; ============================================================================

(deftest account-by-code-lookup
  (testing "account-by-code returns a numeric eid for known codes,
            nil for unknown."
    (let [conn (bootstrap)
          db (d/db conn)]
      (is (number? (chart/account-by-code db chart/sales-domestic-16-code)))
      (is (nil? (chart/account-by-code db "999.99.999"))))))
