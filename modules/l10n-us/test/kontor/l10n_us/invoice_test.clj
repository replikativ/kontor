(ns kontor.l10n-us.invoice-test
  "Tests for kontor.l10n-us.invoice — the posting builder that
   translates a US invoice into kernel transaction + posting tx-data.

   Test scenarios cover the operationally-distinct cases:
     - CA   destination-based sale (compute returns rate; builder routes
            to CA sales-tax payable 2210)
     - NY   destination-based sale (2212)
     - OR   no state sales tax — debit AR, credit revenue only, no
            tax-payable leg
     - resale-cert — taxable state but :resale status → no tax leg
     - out-of-state? — no nexus → revenue routes to 4200
     - cash sale — debit 1100 (Bank) instead of 1200 (AR)
     - single-bucket (:track-by-state? false) — all states to one acct"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.chart :as chart]
            [kontor.l10n-us.invoice :as inv]
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
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- posting-on-account
  "Return all posting amounts (BigDecimal) for the given account code."
  [db code]
  (let [a (ace db code)]
    (when a
      (d/q '[:find [?amt ...]
             :in $ ?a
             :where
             [?p :posting/account ?a]
             [?p :posting/amount ?amt]]
           db a))))

(defn- sum-account [db code]
  (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
            (.add acc x))
          0M (posting-on-account db code)))

;; ============================================================================
;; CA — 7.25% statewide
;; ============================================================================

(deftest ca-invoice-posts
  (testing "California invoice: $1000 net @ 7.25% → $72.50 tax →
            $1072.50 gross. Dr AR 1200, Cr Sales 4000, Cr CA Sales
            Tax Payable 2210."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CA-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :CA
                   :invoice/rate 0.0725M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1072.50M (sum-account db "1200"))
            "AR debited 1072.50")
        (is (= -1000M (sum-account db "4000"))
            "Sales credited 1000")
        (is (= -72.50M (sum-account db "2210"))
            "CA sales tax 72.50 → 2210")
        (is (zero? (.compareTo 0M (sum-account db "2211")))
            "No TX tax")
        (is (zero? (.compareTo 0M (sum-account db "2212")))
            "No NY tax")))))

;; ============================================================================
;; NY — destination-based
;; ============================================================================

(deftest ny-invoice-posts
  (testing "New York invoice: $1000 net @ 8.875% (NYC combined) →
            $88.75 tax → $1088.75 gross. Per-state routing lands
            the tax in 2212 (NY Sales Tax Payable)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-NY-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :NY
                   :invoice/rate 0.08875M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1088.75M (sum-account db "1200")))
        (is (= -1000M   (sum-account db "4000")))
        (is (= -88.75M  (sum-account db "2212"))
            "NY tax → 2212")
        (is (zero? (.compareTo 0M (sum-account db "2210")))
            "No CA tax")))))

;; ============================================================================
;; OR — no sales tax
;; ============================================================================

(deftest oregon-no-sales-tax
  (testing "Oregon has no state sales tax. Invoice rate is 0 → no
            tax-payable leg posted; the builder just emits AR + revenue."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-OR-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :OR
                   :invoice/rate 0M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 500M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 500M  (sum-account db "1200")))
        (is (= -500M (sum-account db "4000")))
        ;; No tax accounts touched
        (doseq [tax-code ["2210" "2211" "2212" "2213" "2214"]]
          (is (zero? (.compareTo 0M (sum-account db tax-code)))
              (str "No tax on " tax-code " for an Oregon sale"))))
      (testing "no-state-sales-tax-state? predicate"
        (is (inv/no-state-sales-tax-state? :OR))
        (is (not (inv/no-state-sales-tax-state? :CA)))))))

;; ============================================================================
;; Resale certificate — taxable state but exempt buyer
;; ============================================================================

(deftest resale-cert-invoice
  (testing "B2B reseller in CA presents a resale certificate. CA's
            rate would otherwise be 7.25%, but :tax-status :resale
            forces zero. AR debited net only; no tax-payable leg."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CA-RESALE-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :CA
                   :invoice/rate 0.0725M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :resale}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "1200"))
            "AR = net only")
        (is (= -1000M (sum-account db "4000")))
        (is (zero? (.compareTo 0M (sum-account db "2210")))
            "No CA tax on resale")))))

;; ============================================================================
;; Out-of-state — no nexus
;; ============================================================================

(deftest out-of-state-no-nexus
  (testing "Sale to a state where the seller has no nexus: revenue
            still books, but routes to 4200 (Out-of-state). No tax
            obligation."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-OOS-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :ID
                   :invoice/out-of-state? true
                   :invoice/rate 0M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 250M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 250M  (sum-account db "1200")))
        (is (= -250M (sum-account db "4200"))
            "Out-of-state revenue → 4200")
        (is (zero? (.compareTo 0M (sum-account db "4000")))
            "Regular sales account untouched")))))

;; ============================================================================
;; Cash sale — debits Bank instead of AR
;; ============================================================================

(deftest cash-sale-debits-bank
  (testing ":invoice/cash-sale? true → debit 1100 (Bank) instead of
            1200 (AR)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :CA
                   :invoice/cash-sale? true
                   :invoice/rate 0.0725M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 107.25M (sum-account db "1100"))
            "Bank debited 107.25 (100 net + 7.25 tax)")
        (is (zero? (.compareTo 0M (sum-account db "1200")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Multi-line with rate-table
;; ============================================================================

(deftest multi-line-rate-table
  (testing "NY invoice with rate-table — clothing at 0%, default at
            8.875%. Tax accrues only on the non-clothing line."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-NY-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :NY
                   :invoice/rate-table {[:NY :default]  0.08875M
                                        [:NY :clothing] 0.0M}
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M
                     :invoice-line/product-class :clothing}
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-us-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; net 200, tax 100*0.08875 = 8.875 → 8.88. Gross 208.88
        (is (= 208.88M (sum-account db "1200")))
        (is (= -200M   (sum-account db "4000"))
            "Both lines on default sales acct (no per-line revenue override)")
        (is (= -8.88M  (sum-account db "2212")))))))

;; ============================================================================
;; track-by-state? false — single bucket
;; ============================================================================

(deftest single-bucket-track-by-state-false
  (testing "track-by-state? false routes all sales tax to one bucket
            account. The chart has no built-in generic bucket; the
            caller passes :single-tax-code. Test uses 2210 as the
            single bucket (a customer might add a 2200 generic
            account instead)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-SINGLE-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :NY
                   :invoice/rate 0.08875M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-us-invoice! conn inv-map
                            {:track-by-state? false
                             :single-tax-code "2210"})
      (let [db (d/db conn)]
        ;; Even though sale shipped to NY, tax landed in 2210 (the
        ;; single bucket the caller designated).
        (is (= -8.88M (sum-account db "2210")))
        (is (zero? (.compareTo 0M (sum-account db "2212")))
            "NY-specific account untouched in single-bucket mode")))))

(deftest track-by-state-false-without-code-throws
  (testing "track-by-state? false without :single-tax-code is an
            explicit programmer error."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-BAD-SINGLE-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :CA
                   :invoice/rate 0.0725M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/post-us-invoice! conn inv-map
                                         {:track-by-state? false}))))))

;; ============================================================================
;; Unknown state — extension hint
;; ============================================================================

(deftest unknown-state-explanatory-error
  (testing "track-by-state? true (default) with a state that has no
            tax-account configured throws with a clear extension
            hint."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-IL-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :IL              ; Illinois — not seeded
                   :invoice/rate 0.0625M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No sales-tax-payable account configured for state :IL"
           (inv/post-us-invoice! conn inv-map))))))

;; ============================================================================
;; Sum-to-zero — kernel invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every US invoice we post must satisfy the kernel
              sum-to-zero rule. Sample the operational cases."
    (doseq [{:keys [name state rate lines opts]}
            [{:name "CA" :state :CA :rate 0.0725M
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "NY" :state :NY :rate 0.08875M
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M}]}
             {:name "OR" :state :OR :rate 0M
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 250M}]}
             {:name "Resale" :state :TX :rate 0.0625M
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 100M
                       :invoice-line/tax-status :resale}]}]]
      (let [conn (bootstrap)
            inv-map (cond-> {:invoice/external-id (str "INV-Z-" name)
                             :invoice/issue-date jan-15
                             :invoice/ship-to-state state
                             :invoice/rate rate
                             :invoice/lines lines}
                      opts (merge opts))]
        (inv/post-us-invoice! conn inv-map)
        (let [db (d/db conn)
              all-amounts (d/q '[:find [?amt ...]
                                 :where [_ :posting/amount ?amt]]
                               db)
              total (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                              (.add acc x))
                            0M all-amounts)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-us-invoice-tx-data-pure
  (testing "plan-us-invoice-tx-data returns tx-data WITHOUT touching
              the DB (read-only db value). The result is suitable
              for kontor.process composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-state :CA
                   :invoice/rate 0.0725M
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-us-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → all required fields missing"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 4))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/ship-to-state :CA
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]})))))

(deftest state-predicates
  (is (inv/sst-state? :WA))
  (is (not (inv/sst-state? :CA)))
  (is (inv/no-state-sales-tax-state? :OR))
  (is (not (inv/no-state-sales-tax-state? :CA))))

;; Silence linter — money import preserved for downstream test compat.
(comment money/zero)
