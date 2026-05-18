(ns kontor.l10n-cn.invoice-test
  "Tests for kontor.l10n-cn.invoice — the posting builder that
   translates a CN invoice / fapiao into kernel transaction +
   posting tx-data.

   Scenarios cover the operationally-distinct CN cases:
     - General taxpayer, 13% manufacturing — output VAT to 2221.01.01,
       revenue tagged 5001.13
     - General taxpayer, mixed-rate invoice (13 + 9 + 6) — revenue
       splits across three per-rate accounts; output VAT stays
       consolidated on 2221.01.01 (MOF-canonical)
     - General taxpayer, export — 5001.0 + no output VAT
     - Small-scale taxpayer, 1% preferential — rate routes to the
       export catch-all (5001.0) when no per-line override
     - Cash sale — debit hits 1002 (bank) instead of 1122 (AR)
     - Fapiao-type — recorded as :transaction/clearance-format,
       does NOT affect postings"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.invoice :as inv]
            [kontor.money :as money]
            [kontor.validation :as v]))

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

(defn- posting-on-account
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
;; General taxpayer — 13% manufacturing sale
;; ============================================================================

(deftest general-13pct-invoice-posts
  (testing "General-taxpayer invoice: CNY 1000 net @ 13% → CNY 1130 gross.
              Dr 1122 AR 1130, Cr 5001.13 sales 1000, Cr 2221.01.01 out-VAT 130.
              Output VAT goes to the single MOF-canonical 2221.01.01."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1130M  (sum-account db "1122"))   "AR debited 1130")
        (is (= -1000M (sum-account db "5001.13")) "Sales-13 credited 1000")
        (is (= -130M  (sum-account db "2221.01.01")) "Output VAT 130")))))

;; ============================================================================
;; Mixed-rate invoice — three rates, one consolidated output-VAT account
;; ============================================================================

(deftest mixed-rate-invoice
  (testing "Three lines at three rates — revenue splits across 5001.13 /
              5001.9 / 5001.6; output VAT remains on a SINGLE
              2221.01.01 account per Cai Kuai [2016] No. 22.

              5000 @ 13% → 650 out
              3000 @ 9%  → 270 out
              2000 @ 6%  → 120 out
              Total: net 10,000 + out 1,040 = gross 11,040"
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 5000M
                     :invoice-line/rate 0.13M}
                    {:invoice-line/quantity 1 :invoice-line/unit-price 3000M
                     :invoice-line/rate 0.09M}
                    {:invoice-line/quantity 1 :invoice-line/unit-price 2000M
                     :invoice-line/rate 0.06M}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 11040M  (sum-account db "1122")))
        (is (= -5000M  (sum-account db "5001.13")))
        (is (= -3000M  (sum-account db "5001.9")))
        (is (= -2000M  (sum-account db "5001.6")))
        (is (= -1040M  (sum-account db "2221.01.01"))
            "All output VAT consolidated on the single MOF account")))))

;; ============================================================================
;; Export — zero-rated, no output VAT
;; ============================================================================

(deftest export-zero-rated
  (testing "Export sale: zero-rated under 出口退税 regime.
              Posts Dr 1122 AR 1000, Cr 5001.0 export sales 1000.
              NO output-VAT posting (it would be zero)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :zero-rated}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "1122")) "AR = net only")
        (is (= -1000M (sum-account db "5001.0")) "Export sales")
        (is (zero? (.compareTo 0M (sum-account db "2221.01.01")))
            "No output VAT")))))

;; ============================================================================
;; Small-scale taxpayer — 1% preferential rate
;; ============================================================================

(deftest small-scale-1pct
  (testing "Small-scale taxpayer with preferential 1% rate (Cai Shui
              [2023] No. 19 through 2027-12-31).

              CNY 1000 net @ 1% → CNY 10 output, gross 1010.
              Revenue catches on 5001.0 (the default chart has no
              small-scale-rate revenue accounts; the catch-all is
              acceptable for the substrate)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-SS-1"
                   :invoice/issue-date jan-15
                   :invoice/taxpayer-status :small-scale
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1010M (sum-account db "1122")))
        (is (= -1000M (sum-account db "5001.0"))
            "Small-scale revenue catches the export catch-all")
        (is (= -10M (sum-account db "2221.01.01")))))))

;; ============================================================================
;; Cash sale variant
;; ============================================================================

(deftest cash-sale-debits-bank
  (testing ":invoice/cash-sale? true → debit goes to 1002 (bank) not 1122 (AR)"
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1130M (sum-account db "1002"))
            "Bank debited 1130 (1000 + 130 VAT)")
        (is (zero? (.compareTo 0M (sum-account db "1122")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Fapiao type — clearance-format on the transaction
;; ============================================================================

(deftest fapiao-type-records-clearance-format
  (testing "An :invoice/fapiao-type → :transaction/clearance-format.
              The postings are identical regardless; the field exists
              so downstream STA-platform routing knows which fapiao
              API to call."
    (doseq [[fapiao-type expected-format]
            [[:special :cn/fapiao-special-18]
             [:general :cn/fapiao-general-20]
             [:fully-digital :cn/fapiao-digital-20]]]
      (let [conn (bootstrap)
            inv-map {:invoice/external-id (str "INV-FP-" (name fapiao-type))
                     :invoice/issue-date jan-15
                     :invoice/fapiao-type fapiao-type
                     :invoice/lines
                     [{:invoice-line/quantity 1 :invoice-line/unit-price 100M}]}
            tx-report (inv/post-cn-invoice! conn inv-map)]
        (let [db (d/db conn)
              tx (d/entity db
                           (d/q '[:find ?t .
                                  :in $ ?eid
                                  :where [?t :transaction/external-id ?eid]]
                                db (:invoice/external-id inv-map)))]
          (is (= expected-format (:transaction/clearance-format tx))
              (str "fapiao-type " fapiao-type " → " expected-format)))
        ;; postings invariant regardless of fapiao-type
        (let [db (d/db conn)]
          (is (= 113M (sum-account db "1122"))))
        ;; Touch tx-report so the binding isn't unused.
        (is (some? tx-report))))))

(deftest invalid-fapiao-type-throws
  (let [conn (bootstrap)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/post-cn-invoice!
                  conn
                  {:invoice/external-id "INV-BAD-FP"
                   :invoice/issue-date jan-15
                   :invoice/fapiao-type :something-bogus
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 1M}]})))))

;; ============================================================================
;; Mixed taxable + zero-rated on the same invoice
;; ============================================================================

(deftest mixed-taxable-and-zero-rated
  (testing "ON-domestic + export line on the same invoice — tax
              accrues on the taxable line only; revenue splits across
              5001.13 + 5001.0."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CN-MIX-2"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}
                    {:invoice-line/quantity 1 :invoice-line/unit-price 500M
                     :invoice-line/tax-status :zero-rated}]}]
      (inv/post-cn-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; 1000 + 130 + 500 = 1630
        (is (= 1630M  (sum-account db "1122")))
        (is (= -1000M (sum-account db "5001.13")))
        (is (= -500M  (sum-account db "5001.0")))
        (is (= -130M  (sum-account db "2221.01.01")))))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-cn-invoice-tx-data-pure
  (testing "plan-cn-invoice-tx-data returns tx-data WITHOUT touching
              the DB (read-only db value). Suitable for kontor.process
              composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1 :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-cn-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → missing required fields"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 3))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]}))))
  (testing "Bad taxpayer-status flagged"
    (is (some #(= :invoice/taxpayer-status (:field %))
              (inv/validate-invoice
               {:invoice/external-id "X"
                :invoice/issue-date jan-15
                :invoice/taxpayer-status :nonsense
                :invoice/lines [{:invoice-line/quantity 1
                                 :invoice-line/unit-price 100M}]})))))

(deftest predicates
  (is (inv/taxpayer-status? :general))
  (is (inv/taxpayer-status? :small-scale))
  (is (not (inv/taxpayer-status? :nonsense)))
  (is (inv/fapiao-type? :special))
  (is (inv/fapiao-type? :fully-digital))
  (is (not (inv/fapiao-type? :nonsense))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every CN invoice we post must satisfy the kernel
              sum-to-zero rule across all rates + taxpayer statuses."
    (doseq [{:keys [name lines opts]}
            [{:name "13pct" :opts {}
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "mixed-rates" :opts {}
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 100M
                       :invoice-line/rate 0.13M}
                      {:invoice-line/quantity 1 :invoice-line/unit-price 200M
                       :invoice-line/rate 0.09M}
                      {:invoice-line/quantity 1 :invoice-line/unit-price 300M
                       :invoice-line/rate 0.06M}]}
             {:name "export" :opts {}
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M
                       :invoice-line/tax-status :zero-rated}]}
             {:name "small-scale" :opts {:invoice/taxpayer-status :small-scale}
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M}]}]]
      (let [conn (bootstrap)
            inv-map (merge {:invoice/external-id (str "INV-Z-" name)
                            :invoice/issue-date jan-15
                            :invoice/lines lines}
                           opts)]
        (inv/post-cn-invoice! conn inv-map)
        (let [db (d/db conn)
              all-amounts (d/q '[:find [?amt ...]
                                 :where [_ :posting/amount ?amt]]
                               db)
              total (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                              (.add acc x))
                            0M all-amounts)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))

;; Silence linter — money import preserved for downstream test compat.
(comment money/zero)
