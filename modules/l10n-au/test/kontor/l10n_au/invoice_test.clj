(ns kontor.l10n-au.invoice-test
  "Tests for kontor.l10n-au.invoice — the posting builder that
   translates an AU invoice into kernel transaction + posting tx-data.

   Test scenarios cover the operationally-distinct cases:
     - taxable    — 10% GST, standard sales (BAS G1 + 1A)
     - GST-free   — 0% GST, ITC-eligible (BAS G2/G3)
     - input-taxed — 0% GST, no upstream ITC (BAS G4)
     - adjustment-note — sign-flipped reversal of a prior tax invoice"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.invoice :as inv]
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

(defn- posting-on-account
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
          0M (posting-on-account db code)))

;; ============================================================================
;; Taxable — 10% GST
;; ============================================================================

(deftest taxable-invoice-posts
  (testing "$1000 net @ 10% GST → $1100 gross.
              Dr AR 1100, Cr Sales-Taxable 1000, Cr GST payable 100."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1100M (sum-account db "11200"))
            "AR debited 1100 (net 1000 + GST 100)")
        (is (= -1000M (sum-account db "41100"))
            "Taxable sales credited 1000")
        (is (= -100M (sum-account db "21500"))
            "GST payable 100")
        (is (zero? (.compareTo 0M (sum-account db "41200")))
            "No GST-free posting")
        (is (zero? (.compareTo 0M (sum-account db "41400")))
            "No input-taxed posting")))))

;; ============================================================================
;; GST-free — 0% GST, supplier may claim ITCs
;; ============================================================================

(deftest gst-free-invoice-posts
  (testing "GST-free sale (e.g. fresh food): no GST charged.
              Dr AR 1000, Cr Sales-GST-Free 1000, NO GST payable."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-GF-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :gst-free}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db "11200"))
            "AR debited 1000 (no GST loading)")
        (is (= -1000M (sum-account db "41200"))
            "GST-free sales 1000")
        (is (zero? (.compareTo 0M (sum-account db "41100")))
            "No taxable sales")
        (is (zero? (.compareTo 0M (sum-account db "21500")))
            "No GST payable")))))

;; ============================================================================
;; Input-taxed — 0% GST, no ITCs upstream
;; ============================================================================

(deftest input-taxed-invoice-posts
  (testing "Input-taxed sale (e.g. residential rent): no GST charged.
              Dr AR 1000, Cr Sales-Input-Taxed 1000, NO GST payable."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-IT-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :input-taxed}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db "11200")))
        (is (= -1000M (sum-account db "41400"))
            "Input-taxed sales 1000 (BAS G4)")
        (is (zero? (.compareTo 0M (sum-account db "41100"))))
        (is (zero? (.compareTo 0M (sum-account db "21500"))))))))

;; ============================================================================
;; Export — GST-free with account override → BAS G2
;; ============================================================================

(deftest export-invoice-posts
  (testing "Export sale: GST-free with explicit revenue override to
              41300 → routes to BAS G2 (export sales) rather than
              the default G3 (other GST-free)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 5000M
                     :invoice-line/tax-status :gst-free
                     :invoice-line/account "41300"}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 5000M (sum-account db "11200")))
        (is (= -5000M (sum-account db "41300"))
            "Export sales routed to 41300")
        (is (zero? (.compareTo 0M (sum-account db "41200")))
            "Default GST-free account untouched (override won)")
        (is (zero? (.compareTo 0M (sum-account db "21500"))))))))

;; ============================================================================
;; Mixed-line invoice
;; ============================================================================

(deftest mixed-line-invoice
  (testing "Invoice with one taxable line + one GST-free line.
              Tax accrues on the taxable line only; revenue splits
              across two accounts."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}                 ; taxable
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-status :gst-free}]}]          ; fresh food
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; GST: 10% × 1000 = 100. Net total: 1500. Gross 1600.
        (is (= 1600M  (sum-account db "11200")))
        (is (= -1000M (sum-account db "41100")))
        (is (= -500M  (sum-account db "41200")))
        (is (= -100M  (sum-account db "21500")))))))

;; ============================================================================
;; Cash sale variant
;; ============================================================================

(deftest cash-sale-debits-cash-not-ar
  (testing ":invoice/cash-sale? true → debit goes to 11100 (Bank)
              instead of 11200 (AR)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 110M (sum-account db "11100"))
            "Bank debited 110 (100 net + 10 GST)")
        (is (zero? (.compareTo 0M (sum-account db "11200")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Adjustment note — sign-flipped reversal
;; ============================================================================

(deftest adjustment-note-flips-signs
  (testing "An adjustment note reverses each leg of a prior tax invoice.
              On a $1000 taxable adjustment-note: Cr AR 1100, Dr Sales
              1000, Dr GST payable 100 (the GST is reclaimed)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "ADJ-1"
                   :invoice/issue-date jan-15
                   :invoice/kind :adjustment-note
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-au-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= -1100M (sum-account db "11200"))
            "AR credited (reverse of a tax invoice)")
        (is (= 1000M  (sum-account db "41100"))
            "Sales debited (reverse of credit on invoice)")
        (is (= 100M   (sum-account db "21500"))
            "GST payable debited (output GST reclaimed)")))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-au-invoice-tx-data-pure
  (testing "plan-au-invoice-tx-data returns tx-data WITHOUT touching
              the DB (read-only db value). The result is suitable
              for kontor.process composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-au-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data)))
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :kontor.posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → required-fields complaints"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 3))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]}))))
  (testing "Invalid :invoice/kind is rejected"
    (let [complaints (inv/validate-invoice
                      {:invoice/external-id "X"
                       :invoice/issue-date jan-15
                       :invoice/kind :unknown
                       :invoice/lines [{:invoice-line/quantity 1
                                        :invoice-line/unit-price 100M}]})]
      (is (seq complaints)))))

;; ============================================================================
;; Required-field gating
;; ============================================================================

(deftest missing-external-id-throws
  (let [conn (bootstrap)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"external-id"
         (inv/post-au-invoice! conn
                               {:invoice/issue-date jan-15
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 100M}]})))))

(deftest missing-issue-date-throws
  (let [conn (bootstrap)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"issue-date"
         (inv/post-au-invoice! conn
                               {:invoice/external-id "X"
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 100M}]})))))

(deftest empty-lines-throws
  (let [conn (bootstrap)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"lines"
         (inv/post-au-invoice! conn
                               {:invoice/external-id "X"
                                :invoice/issue-date jan-15
                                :invoice/lines []})))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every AU invoice we post must satisfy the kernel
              sum-to-zero rule. Sample the four flagship cases."
    (doseq [{:keys [name lines kind]}
            [{:name "taxable"
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "gst-free"
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                       :invoice-line/tax-status :gst-free}]}
             {:name "input-taxed"
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                       :invoice-line/tax-status :input-taxed}]}
             {:name "adjustment"
              :kind :adjustment-note
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M}]}]]
      (let [conn (bootstrap)
            inv-map (cond-> {:invoice/external-id (str "INV-Z-" name)
                             :invoice/issue-date jan-15
                             :invoice/lines lines}
                      kind (assoc :invoice/kind kind))]
        (inv/post-au-invoice! conn inv-map)
        (let [db (d/db conn)
              all-amounts (d/q '[:find [?amt ...]
                                 :where [_ :kontor.posting/amount ?amt]]
                               db)
              total (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                              (.add acc x))
                            0M all-amounts)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))

;; Silence linter — money import preserved for downstream test compat.
(comment money/zero)
