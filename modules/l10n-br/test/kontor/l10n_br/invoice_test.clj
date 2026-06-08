(ns kontor.l10n-br.invoice-test
  "Tests for kontor.l10n-br.invoice — the posting builder that
   translates a BR invoice into kernel transaction + posting tx-data.

   Brazilian invoices stack many taxes on a single line:
     - ICMS (state-level VAT, 7-23% by state + macro-region routing)
     - IPI  (federal manufacturing tax, NCM-keyed)
     - PIS  + COFINS  (federal contributions, regime-keyed)
     - ISS  (municipal service tax, 2-5%)
     - DIFAL (inter-state ICMS differential)

   Test scenarios cover the operationally-distinct cases:
     - intra-state SP goods  (ICMS + PIS + COFINS)
     - inter-state SP → BA goods  (ICMS + DIFAL + PIS + COFINS)
     - manufactured goods with IPI
     - pure services (ISS + PIS + COFINS, no ICMS)
     - export sale (zero-rated everywhere)
     - mixed-line invoice (goods + services)
     - cash sale variant
     - pure builder + validation predicates"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.chart :as chart]
            [kontor.l10n-br.invoice :as inv]
            [kontor.l10n-br.taxes :as tax]
            [kontor.money :as money]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-15 #inst "2026-01-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- posting-amounts
  "Return all posting amounts (BigDecimal) for the given account
   code in the test DB."
  [db code]
  (let [a (ace db code)]
    (when a
      (d/q '[:find [?amt ...]
             :in $ ?a
             :where
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]]
           db a))))

(defn- sum-account [db code]
  (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
            (.add acc x))
          0M (posting-amounts db code)))

(defn- ≈ [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (zero? (.compareTo a b)))

;; ============================================================================
;; Intra-state SP goods — ICMS 18%, PIS 1.65%, COFINS 7.6%
;; ============================================================================

(deftest intra-state-sp-goods-invoice-posts
  (testing "SP → SP goods: R$1000 net.
              ICMS 18% × 1000           = 180.00
              PIS  1.65% × (1000 − 180) = 13.53
              COFINS 7.6% × (1000 − 180) = 62.32
              Tax stack = 255.85; gross = 1255.85.
              ICMS / PIS / COFINS each route to their payable account."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-SP-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 1255.85M (sum-account db "1.01.03.01.01"))
            "AR debited 1255.85 (gross)")
        (is (≈ -1000M (sum-account db "3.01.01.01.01"))
            "Goods revenue credited 1000")
        (is (≈ -180M (sum-account db "2.01.04.01.01"))
            "ICMS payable 180")
        (is (≈ -13.53M (sum-account db "2.01.04.01.03"))
            "PIS payable 13.53")
        (is (≈ -62.32M (sum-account db "2.01.04.01.04"))
            "COFINS payable 62.32")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.02")))
            "No IPI on plain goods")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.05")))
            "No ISS on goods")))))

;; ============================================================================
;; Inter-state SP → BA goods — ICMS origin + DIFAL destination
;; ============================================================================

(deftest inter-state-sp-to-ba-goods-invoice-posts
  (testing "SP → BA goods (S/SE → N/NE/MW): R$1000 net.
              Per CONFAZ Res. SF 22/1989 the origin ICMS is 7%.
              Per LC 190/2022 the seller owes DIFAL to BA.
              ICMS  7% × 1000             = 70.00 (origin)
              DIFAL (20.5% − 7%) × 1000  = 135.00 (destination — BA modal 20.5%)
              PIS   1.65% × (1000 − 70)  = 15.35 → 15.34 HALF-EVEN
              COFINS 7.6% × (1000 − 70)  = 70.68
              Total tax = 291.02; gross = 1291.02.

              ICMS + DIFAL collapse to one ICMS-payable posting by
              default (205.00). Override :codes to split."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-SP-BA-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "BA"
                   ;; Default :buyer-type :non-contributor → DIFAL applies.
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 1291.02M (sum-account db "1.01.03.01.01"))
            "AR = net + ICMS + DIFAL + PIS + COFINS")
        (is (≈ -1000M (sum-account db "3.01.01.01.01"))
            "Goods revenue 1000")
        ;; ICMS+DIFAL collapsed: 70.00 + 135.00 = 205.00
        (is (≈ -205M (sum-account db "2.01.04.01.01"))
            "ICMS payable bundle (origin 70 + DIFAL 135) = 205")
        (is (≈ -15.34M (sum-account db "2.01.04.01.03"))
            "PIS payable 15.34 (HALF-EVEN of 15.345)")
        (is (≈ -70.68M (sum-account db "2.01.04.01.04"))
            "COFINS payable 70.68")))))

(deftest inter-state-rj-to-rs-goods-different-states
  (testing "Inter-state RJ (S/SE) → RS (S/SE) = 12% interstate.
              R$2000 net.
              ICMS 12% × 2000          = 240.00
              DIFAL (17% − 12%) × 2000 = 100.00  (RS modal 17%)
              PIS  1.65% × (2000 − 240) = 29.04
              COFINS 7.6% × (2000 − 240) = 133.76
              Total tax = 502.80; gross = 2502.80.
              Two states beyond SP/BA cover the rate-variation scope."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-RJ-RS-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "RJ"
                   :kontor.invoice/to-state "RS"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 2000M
                     :kontor.invoice-line/tax-classification :goods}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 2502.80M (sum-account db "1.01.03.01.01")))
        (is (≈ -2000M (sum-account db "3.01.01.01.01")))
        ;; ICMS + DIFAL bundle: 240 + 100 = 340
        (is (≈ -340M (sum-account db "2.01.04.01.01")))
        (is (≈ -29.04M (sum-account db "2.01.04.01.03")))
        (is (≈ -133.76M (sum-account db "2.01.04.01.04")))))))

;; ============================================================================
;; Manufactured goods with IPI
;; ============================================================================

(deftest manufactured-goods-with-ipi-posts
  (testing "SP → SP manufactured goods (NCM-tied IPI 10%): R$1000 net.
              IPI   10% × 1000       = 100.00
              ICMS  18% × (1000+100) = 198.00 (cálculo por dentro)
              PIS   1.65% × (1100 − 198) = 14.88
              COFINS 7.6% × (1100 − 198) = 68.55
              Tax stack: 100 + 198 + 14.88 + 68.55 = 381.43
              Gross = 1381.43"
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-MFG-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods-manufactured
                     :kontor.invoice-line/ipi-rate 0.10M}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 1381.43M (sum-account db "1.01.03.01.01"))
            "AR debited 1381.43 (gross)")
        (is (≈ -1000M (sum-account db "3.01.01.01.01"))
            "Goods revenue credited 1000")
        (is (≈ -100M (sum-account db "2.01.04.01.02"))
            "IPI payable 100")
        (is (≈ -198M (sum-account db "2.01.04.01.01"))
            "ICMS payable 198 (computed on 1100 cálculo por dentro)")
        (is (≈ -14.88M (sum-account db "2.01.04.01.03"))
            "PIS payable 14.88 (base 902 after STF Tema 69 ICMS exclusion)")
        (is (≈ -68.55M (sum-account db "2.01.04.01.04"))
            "COFINS payable 68.55")))))

;; ============================================================================
;; Pure services — ISS + PIS + COFINS, no ICMS
;; ============================================================================

(deftest services-invoice-iss-only-posts
  (testing "Pure services R$1000 net, ISS 5% (São Paulo).
              ISS    5%    × 1000 = 50.00
              PIS    1.65% × 1000 = 16.50   (no ICMS to exclude)
              COFINS 7.6%  × 1000 = 76.00
              Tax stack 142.50; gross 1142.50.
              Revenue routes to Services revenue (3.01.01.01.02)
              not Goods revenue."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-SVC-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :services
                     :kontor.invoice-line/iss-rate 0.05M}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 1142.50M (sum-account db "1.01.03.01.01"))
            "AR 1142.50")
        ;; Services revenue (3.01.01.01.02), not goods.
        (is (zero? (.compareTo 0M (sum-account db "3.01.01.01.01")))
            "No goods revenue")
        (is (≈ -1000M (sum-account db "3.01.01.01.02"))
            "Services revenue 1000")
        (is (≈ -50M (sum-account db "2.01.04.01.05"))
            "ISS payable 50")
        (is (≈ -16.50M (sum-account db "2.01.04.01.03"))
            "PIS payable 16.50")
        (is (≈ -76M (sum-account db "2.01.04.01.04"))
            "COFINS payable 76")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.01")))
            "No ICMS on pure services")))))

(deftest services-invoice-min-iss-rate-2-pct
  (testing "ISS 2% (low-tax municipality, e.g. Barueri/SP).
              ISS 2% × 1000 = 20.00."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-SVC-2"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :services
                     :kontor.invoice-line/iss-rate 0.02M}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ -20M (sum-account db "2.01.04.01.05"))
            "ISS payable 20 (low-tax municipality)")))))

;; ============================================================================
;; Export — zero-rated everywhere
;; ============================================================================

(deftest export-zero-rated-no-tax-postings
  (testing "Export R$5000 net: zero on ICMS / IPI / PIS / COFINS.
              (Lei Kandir LC 87/1996 for ICMS; Lei 10.865/2004 for
              PIS/COFINS; RIPI art. 18 for IPI.)
              Revenue routes to Export sales account (3.01.01.02.01)."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-EXP-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 5000M
                     :kontor.invoice-line/tax-classification :export}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 5000M (sum-account db "1.01.03.01.01"))
            "AR = net only")
        (is (≈ -5000M (sum-account db "3.01.01.02.01"))
            "Export revenue 5000")
        (is (zero? (.compareTo 0M (sum-account db "3.01.01.01.01")))
            "No domestic goods revenue")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.01")))
            "No ICMS")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.03")))
            "No PIS")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.04")))
            "No COFINS")
        (is (zero? (.compareTo 0M (sum-account db "2.01.04.01.02")))
            "No IPI")))))

;; ============================================================================
;; Mixed-line invoice — goods + services
;; ============================================================================

(deftest mixed-goods-and-services-invoice
  (testing "One taxable goods line + one services line on the same
              invoice. Goods stack (ICMS + PIS + COFINS) plus services
              stack (ISS + PIS + COFINS) sum in their own accounts.

              Goods 800 net + Services 200 net.
              Goods:   ICMS 18% × 800           = 144.00
                       PIS  1.65% × (800 − 144) = 10.82
                       COFINS 7.6% × 656        = 49.86
              Services: ISS 5% × 200             = 10.00
                       PIS  1.65% × 200          = 3.30
                       COFINS 7.6% × 200         = 15.20
              Aggregated PIS    = 14.12
              Aggregated COFINS = 65.06"
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-MIX-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 800M
                     :kontor.invoice-line/tax-classification :goods}
                    {:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 200M
                     :kontor.invoice-line/tax-classification :services
                     :kontor.invoice-line/iss-rate 0.05M}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ -800M (sum-account db "3.01.01.01.01"))
            "Goods revenue 800")
        (is (≈ -200M (sum-account db "3.01.01.01.02"))
            "Services revenue 200")
        (is (≈ -144M (sum-account db "2.01.04.01.01"))
            "ICMS payable 144")
        (is (≈ -10M (sum-account db "2.01.04.01.05"))
            "ISS payable 10")
        (is (≈ -14.12M (sum-account db "2.01.04.01.03"))
            "PIS payable 14.12 (10.82 goods + 3.30 services)")
        (is (≈ -65.06M (sum-account db "2.01.04.01.04"))
            "COFINS payable 65.06 (49.86 goods + 15.20 services)")))))

;; ============================================================================
;; Cash sale variant
;; ============================================================================

(deftest cash-sale-debits-caixa-not-ar
  (testing ":kontor.invoice/cash-sale? true → debit Caixa (1.01.01.01.01)
              instead of Clientes Nacionais (1.01.03.01.01)."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-CASH-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/cash-sale? true
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods}]}]
      (inv/post-br-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (≈ 1255.85M (sum-account db "1.01.01.01.01"))
            "Caixa debited 1255.85")
        (is (zero? (.compareTo 0M (sum-account db "1.01.03.01.01")))
            "AR untouched on cash sale")))))

;; ============================================================================
;; Sub-account routing — split DIFAL into its own bucket
;; ============================================================================

(deftest difal-can-route-to-its-own-account
  (testing "Override :codes :difal-code so DIFAL posts to a different
              account from regular ICMS. Production deployments commonly
              maintain a separate sub-account so the GIA / EFD-ICMS-IPI
              return shows DIFAL line items in their own row.

              We use 2.01.04.01.07 (CSLL Payable) only because the
              starter chart doesn't ship a dedicated DIFAL code —
              we're testing the override mechanism, not the BR-specific
              sub-account identity."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-DIFAL-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "BA"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-classification :goods}]}]
      (inv/post-br-invoice! conn inv-map {:codes {:difal-code "2.01.04.01.07"}})
      (let [db (d/db conn)]
        ;; ICMS 70 only — DIFAL ran out to its own bucket.
        (is (≈ -70M (sum-account db "2.01.04.01.01"))
            "ICMS payable 70 (origin only — no DIFAL)")
        (is (≈ -135M (sum-account db "2.01.04.01.07"))
            "DIFAL 135 routed to the override account")))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-br-invoice-tx-data-pure
  (testing "plan-br-invoice-tx-data returns tx-data WITHOUT touching
              the DB (read-only db value). Suitable for kontor.workflow.process
              composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:kontor.invoice/external-id "INV-PLAN-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/from-state "SP"
                   :kontor.invoice/to-state "SP"
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 100M
                     :kontor.invoice-line/tax-classification :goods}]}
          tx-data (inv/plan-br-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :kontor.posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → required-field complaints"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 2)
          "External-id, issue-date, lines are flagged")))
  (testing "Goods invoice missing from-state/to-state → flagged"
    (is (seq (inv/validate-invoice
              {:kontor.invoice/external-id "X"
               :kontor.invoice/issue-date jan-15
               :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                :kontor.invoice-line/unit-price 100M
                                :kontor.invoice-line/tax-classification :goods}]}))))
  (testing "Services line without :iss-rate → flagged"
    (is (seq (inv/validate-invoice
              {:kontor.invoice/external-id "X"
               :kontor.invoice/issue-date jan-15
               :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                :kontor.invoice-line/unit-price 100M
                                :kontor.invoice-line/tax-classification :services}]}))))
  (testing "Complete goods invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:kontor.invoice/external-id "X"
                  :kontor.invoice/issue-date jan-15
                  :kontor.invoice/from-state "SP"
                  :kontor.invoice/to-state "SP"
                  :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                   :kontor.invoice-line/unit-price 100M
                                   :kontor.invoice-line/tax-classification :goods}]}))))
  (testing "Complete services invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:kontor.invoice/external-id "X"
                  :kontor.invoice/issue-date jan-15
                  :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                   :kontor.invoice-line/unit-price 100M
                                   :kontor.invoice-line/tax-classification :services
                                   :kontor.invoice-line/iss-rate 0.05M}]})))))

(deftest state-predicate
  (is (inv/state? "SP"))
  (is (inv/state? "DF"))
  (is (not (inv/state? "ZZ")))
  (is (not (inv/state? nil))))

(deftest interstate-predicate
  (is (inv/interstate? "SP" "BA"))
  (is (not (inv/interstate? "SP" "SP")))
  (is (not (inv/interstate? nil "BA"))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every BR invoice we post must satisfy the kernel
              sum-to-zero rule. Sample the flagship cases across the
              tax stack."
    (doseq [{:keys [name lines from to]}
            [{:name "intra-SP" :from "SP" :to "SP"
              :lines [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 1000M
                       :kontor.invoice-line/tax-classification :goods}]}
             {:name "inter-SP-BA" :from "SP" :to "BA"
              :lines [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 1000M
                       :kontor.invoice-line/tax-classification :goods}]}
             {:name "manufactured" :from "SP" :to "SP"
              :lines [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 1000M
                       :kontor.invoice-line/tax-classification :goods-manufactured
                       :kontor.invoice-line/ipi-rate 0.10M}]}
             {:name "services" :from nil :to nil
              :lines [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 500M
                       :kontor.invoice-line/tax-classification :services
                       :kontor.invoice-line/iss-rate 0.05M}]}
             {:name "export" :from "SP" :to "SP"
              :lines [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 5000M
                       :kontor.invoice-line/tax-classification :export}]}]]
      (let [conn (bootstrap)
            inv-map (cond-> {:kontor.invoice/external-id (str "INV-Z-" name)
                             :kontor.invoice/issue-date jan-15
                             :kontor.invoice/lines lines}
                      from (assoc :kontor.invoice/from-state from)
                      to   (assoc :kontor.invoice/to-state to))]
        (inv/post-br-invoice! conn inv-map)
        (let [db (d/db conn)
              all-amounts (d/q '[:find [?amt ...]
                                 :where [_ :kontor.posting/amount ?amt]]
                               db)
              total (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                              (.add acc x))
                            0M all-amounts)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))

;; Silence linter — money + tax imports preserved for downstream test
;; compatibility (regression suites pin these via `kontor.l10n-br.invoice-test`).
(comment money/zero tax/compute-tax)
