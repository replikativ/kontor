(ns kontor.l10n-mx.invoice-test
  "Tests for kontor.l10n-mx.invoice — the posting builder that
   translates a CFDI-aligned invoice into kernel transaction +
   posting tx-data.

   Test scenarios cover:
     - 16% IVA invoice in central Mexico (default)
     - 8% border-zone IVA
     - 0% rate on food/medicine (Art. 2-A)
     - Exempt residential rent (Art. 9)
     - IEPS on a sugary drink (per-line :ieps-rate)
     - Honorario with retenciones (IVA + ISR withheld at source)
     - Cash-sale routing (IVA goes directly to 208.01 cobrado)
     - Export invoice (zero-rated, debits Clientes Extranjero)
     - Sum-to-zero across all flagship cases"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.invoice :as inv]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-15 #inst "2026-01-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- sum-account
  "Sum all posting amounts on the given account code (BigDecimal)."
  [db code]
  (let [a (ace db code)]
    (if (nil? a)
      0M
      (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                (.add acc x))
              0M
              (d/q '[:find [?amt ...]
                     :in $ ?a
                     :where
                     [?p :posting/account ?a]
                     [?p :posting/amount ?amt]]
                   db a)))))

;; ============================================================================
;; Central Mexico — 16% IVA (the dominant case)
;; ============================================================================

(deftest central-16pct-invoice-posts
  (testing "Default invoice (region :general) → 16% IVA, IVA lands
              on 208.02 (NO cobrado) because cash-basis IVA isn't
              recognised until payment.

                Dr Clientes 1160
                Cr Ingresos 16% 1000
                Cr IVA trasladado NO cobrado 16% 160"
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CENTRAL-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1160M (sum-account db chart/ar-code))
            "Clientes debited 1160")
        (is (= -1000M (sum-account db chart/sales-domestic-16-code))
            "Ingresos 16% credited 1000")
        (is (= -160M (sum-account db chart/iva-trasladado-no-cobrado-16-code))
            "IVA trasladado NO cobrado 16% credited 160
             — cash-basis: not yet recognised on cobrado")
        (is (zero? (.compareTo 0M (sum-account db
                                               chart/iva-trasladado-cobrado-16-code)))
            "208.01 cobrado is untouched on credit-sale issuance")))))

;; ============================================================================
;; Border zone — 8% IVA
;; ============================================================================

(deftest border-norte-8pct-invoice-posts
  (testing "Northern border-zone invoice → 8% IVA per the Decreto
              región fronteriza. Revenue lands on 401.01.002
              (ventas frontera) and IVA on 208.02.002 (no cobrado 8%)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-BORDER-1"
                   :invoice/issue-date jan-15
                   :invoice/region :border-norte
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1080M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-8-code))
            "Revenue on the 8% frontera account")
        (is (= -80M (sum-account db chart/iva-trasladado-no-cobrado-8-code))
            "IVA 8% no cobrado")
        (is (zero? (.compareTo 0M (sum-account db chart/sales-domestic-16-code)))
            "16% revenue account untouched")))))

;; ============================================================================
;; 0% rate — basic food / medicine
;; ============================================================================

(deftest zero-rated-food-invoice-posts
  (testing "Zero-rated supply (Art. 2-A: basic food / medicine /
              books / exports) → IVA 0. Revenue routes to 401.01.003."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FOOD-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-status :zero-rated}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 500M (sum-account db chart/ar-code))
            "AR carries net only — no IVA")
        (is (= -500M (sum-account db chart/sales-domestic-0-code))
            "Ventas 0% (food/medicine)")
        (is (zero? (.compareTo 0M (sum-account db
                                               chart/iva-trasladado-no-cobrado-16-code)))
            "No IVA on any 208 account")))))

;; ============================================================================
;; Exempt — residential rent
;; ============================================================================

(deftest exempt-residential-rent-invoice-posts
  (testing "Exempt supply (Art. 9: renta habitacional / salud /
              educación) → no IVA, no ITC upstream. Revenue routes
              to 401.01.004."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-RENT-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 15000M
                     :invoice-line/tax-status :exempt}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 15000M (sum-account db chart/ar-code)))
        (is (= -15000M (sum-account db chart/sales-exempt-code))
            "Ingresos exentos")
        (is (zero? (.compareTo 0M (sum-account db
                                               chart/iva-trasladado-no-cobrado-16-code))))))))

;; ============================================================================
;; IEPS — sugary drink
;; ============================================================================

(deftest ieps-sugary-drink-invoice-posts
  (testing "Sugary-drink invoice at IEPS 26.5% (illustrative rate) +
              16% IVA. IEPS lands on 209.02 (no cobrado, cash-basis
              symmetric to IVA)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-SUGAR-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/ieps-rate 0.265M}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; net 1000, IVA 160, IEPS 265 → gross 1425
        (is (= 1425M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-16-code)))
        (is (= -160M (sum-account db chart/iva-trasladado-no-cobrado-16-code)))
        (is (= -265M (sum-account db chart/ieps-trasladado-no-cobrado-code))
            "IEPS no cobrado 265")))))

;; ============================================================================
;; Honorario with retenciones (IVA + ISR withheld at source)
;; ============================================================================

(deftest honorario-with-retenciones
  (testing "Persona moral pays a persona física for honorarios:
              the buyer withholds 10.6667% IVA + 10% ISR.

                Net invoice: 1000 net + 160 IVA = 1160 gross
                Withheld:    106.67 IVA + 100 ISR = 206.67
                Cash recv:   1160 − 206.67 = 953.33

              Postings:
                Dr Clientes (AR)               953.33
                Dr Retención IVA por cobrar    106.67
                Dr Retención ISR por cobrar    100.00
                Cr Ingresos 16%                1000.00
                Cr IVA NO cobrado 16%           160.00"
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-HONOR-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/retencion-iva-rate 0.106667M
                     :invoice-line/retencion-isr-rate 0.10M}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 953.33M (sum-account db chart/ar-code))
            "AR carries cash-receipt = gross − retención")
        (is (= 106.67M (sum-account db chart/iva-retenido-cobrar-code))
            "Retención IVA receivable (offset against own IVA later)")
        (is (= 100M (sum-account db chart/isr-retenido-cobrar-code))
            "Retención ISR receivable (offset against own ISR later)")
        (is (= -1000M (sum-account db chart/sales-domestic-16-code)))
        (is (= -160M (sum-account db chart/iva-trasladado-no-cobrado-16-code))
            "Full IVA still owed to SAT — retención reduces cash, not gross")))))

;; ============================================================================
;; Cash sale — IVA recognised immediately on 208.01 (cobrado)
;; ============================================================================

(deftest cash-sale-iva-cobrado
  (testing ":invoice/cash-sale? true → debit Caja AND route IVA
              directly to 208.01 (cobrado) instead of 208.02 (no
              cobrado). Payment is received simultaneously with
              issuance, so cash-basis IVA recognises immediately."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 116M (sum-account db chart/cash-code))
            "Caja debited 116 (100 net + 16 IVA)")
        (is (zero? (.compareTo 0M (sum-account db chart/ar-code)))
            "AR untouched on a cash sale")
        (is (= -16M (sum-account db chart/iva-trasladado-cobrado-16-code))
            "IVA on 208.01 (cobrado) — recognised at receipt")
        (is (zero? (.compareTo 0M (sum-account db
                                               chart/iva-trasladado-no-cobrado-16-code)))
            "208.02 (no cobrado) untouched on cash sale")))))

;; ============================================================================
;; Export — Clientes Extranjero, zero-rated
;; ============================================================================

(deftest export-invoice
  (testing "Sale to foreign buyer with goods exported (Art. 29) →
              non-resident tax-status + :invoice/export? routes the
              debit to 105.02 (Clientes Extranjero) and revenue to
              401.02.001 (Exportación)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/export? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :non-resident}]}]
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db chart/ar-export-code))
            "Clientes Extranjero debited net (no IVA)")
        (is (= -1000M (sum-account db chart/sales-export-code))
            "Ingresos por exportación")
        (is (zero? (.compareTo 0M (sum-account db chart/ar-code)))
            "Domestic AR untouched on export")))))

;; ============================================================================
;; Multi-line — mixed tax statuses
;; ============================================================================

(deftest mixed-line-invoice
  (testing "Invoice mixing taxable 16% + zero-rated food + exempt
              services. Each line routes to its own revenue + IVA
              accounts; the totals add up correctly."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}                  ; 16%
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 200M
                     :invoice-line/tax-status :zero-rated}            ; food
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 5000M
                     :invoice-line/tax-status :exempt}]}]            ; rent
      (inv/post-mx-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; Net total: 1000 + 200 + 5000 = 6200; IVA: 160 (only line 1)
        ;; Gross: 6360
        (is (= 6360M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-16-code)))
        (is (= -200M  (sum-account db chart/sales-domestic-0-code)))
        (is (= -5000M (sum-account db chart/sales-exempt-code)))
        (is (= -160M  (sum-account db chart/iva-trasladado-no-cobrado-16-code)))))))

;; ============================================================================
;; Pure planner — no DB writes
;; ============================================================================

(deftest plan-mx-invoice-tx-data-pure
  (testing "plan-mx-invoice-tx-data returns tx-data WITHOUT touching
              the DB. Result is suitable for kontor.process
              composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-mx-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data)))
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → all required fields missing"
    (let [complaints (inv/validate-invoice {})]
      (is (>= (count complaints) 3))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]}))))
  (testing "Unknown region rejected"
    (let [c (inv/validate-invoice
             {:invoice/external-id "X"
              :invoice/issue-date jan-15
              :invoice/region :antarctica
              :invoice/lines [{:invoice-line/quantity 1
                               :invoice-line/unit-price 100M}]})]
      (is (some #{:invoice/region} (map :field c))))))

;; ============================================================================
;; Sum-to-zero across flagship cases
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every MX invoice posting must sum to zero per the kernel
              rule. Sample all five operationally distinct cases."
    (doseq [{:keys [name invoice]}
            [{:name "16pct-central"
              :invoice {:invoice/external-id "Z-CTR"
                        :invoice/issue-date jan-15
                        :invoice/lines [{:invoice-line/quantity 1
                                         :invoice-line/unit-price 1000M}]}}
             {:name "8pct-border"
              :invoice {:invoice/external-id "Z-BDR"
                        :invoice/issue-date jan-15
                        :invoice/region :border-norte
                        :invoice/lines [{:invoice-line/quantity 1
                                         :invoice-line/unit-price 1000M}]}}
             {:name "zero-rated-food"
              :invoice {:invoice/external-id "Z-FD"
                        :invoice/issue-date jan-15
                        :invoice/lines [{:invoice-line/quantity 1
                                         :invoice-line/unit-price 500M
                                         :invoice-line/tax-status :zero-rated}]}}
             {:name "ieps-sugar"
              :invoice {:invoice/external-id "Z-IEPS"
                        :invoice/issue-date jan-15
                        :invoice/lines [{:invoice-line/quantity 1
                                         :invoice-line/unit-price 1000M
                                         :invoice-line/ieps-rate 0.265M}]}}
             {:name "honorario-retenciones"
              :invoice {:invoice/external-id "Z-HON"
                        :invoice/issue-date jan-15
                        :invoice/lines [{:invoice-line/quantity 1
                                         :invoice-line/unit-price 1000M
                                         :invoice-line/retencion-iva-rate 0.106667M
                                         :invoice-line/retencion-isr-rate 0.10M}]}}]]
      (let [conn (bootstrap)]
        (inv/post-mx-invoice! conn invoice)
        (let [db (d/db conn)
              pairs (d/q '[:find ?p ?amt
                           :where [?p :posting/amount ?amt]] db)
              total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                              (.add acc x))
                            0M pairs)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))
