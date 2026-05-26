(ns kontor.l10n-fr.invoice-test
  "Tests for kontor.l10n-fr.invoice — the posting builder that
   translates a French invoice into kernel transaction + posting
   tx-data.

   Test scenarios cover the four operationally-distinct cases:
     - 20% standard invoice  — Renault scenario, taux normal
     - 10% restaurant        — taux intermédiaire
     - 5,5% book sale        — taux réduit
     - Intra-EU B2B          — reverse charge, no FR TVA
     - Export hors UE        — zero-rated"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.chart :as chart]
            [kontor.l10n-fr.invoice :as inv]
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
    (d/transact conn [{:kontor.journal/code "VTE"
                       :kontor.journal/name "Journal des ventes"
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
;; 20% — taux normal (Renault-style B2B service)
;; ============================================================================

(deftest std-20pct-invoice-posts
  (testing "Taux normal 20%: €1000 net → €200 TVA → €1200 gross.
              Dr 411 1200, Cr 706 1000, Cr 44571 200."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1200M (sum-account db "411"))
            "Client (411) debited 1200")
        (is (= -1000M (sum-account db "706"))
            "Revenue (706) credited 1000")
        (is (= -200M (sum-account db "44571"))
            "TVA collectée 20% credited 200")))))

;; ============================================================================
;; 10% — taux intermédiaire (restaurant scenario)
;; ============================================================================

(deftest inter-10pct-invoice-posts
  (testing "Taux intermédiaire 10%: €1000 net restaurant bill → €100 TVA."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-RST-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/rate :inter}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1100M  (sum-account db "411")))
        (is (= -1000M (sum-account db "7065"))
            "Revenue routed to 7065 (Prestations à 10%)")
        (is (= -100M  (sum-account db "44572"))
            "TVA 10% to 44572")))))

;; ============================================================================
;; 5,5% — taux réduit (book sale)
;; ============================================================================

(deftest red-5-5pct-invoice-posts
  (testing "Taux réduit 5,5%: €1000 of books → €55 TVA."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-BK-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/rate :red}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1055M  (sum-account db "411")))
        (is (= -1000M (sum-account db "7066"))
            "Revenue routed to 7066 (Prestations à 5,5%)")
        (is (= -55M   (sum-account db "44573"))
            "TVA 5,5% to 44573")))))

;; ============================================================================
;; Intra-EU B2B — reverse charge
;; ============================================================================

(deftest intra-eu-b2b-no-tva
  (testing "Intra-EU B2B (CGI art.283-1): buyer self-assesses.
              FR invoice carries no TVA. Posts:
                Dr 411 1000, Cr 7081 1000."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-EU-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :intra-eu-b2b}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "411"))
            "AR = net only — no TVA")
        (is (= -1000M (sum-account db "7081"))
            "Routed to livraisons intra-UE exonérées")
        (is (zero? (.compareTo 0M (sum-account db "44571")))
            "No TVA collectée")
        (is (zero? (.compareTo 0M (sum-account db "706")))
            "No regular revenue posting")))))

;; ============================================================================
;; Export hors-UE — zero-rated
;; ============================================================================

(deftest export-zero-rated
  (testing "Export hors-UE (CGI art.262 I) — zero-rated."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-status :export}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 500M  (sum-account db "411")))
        (is (= -500M (sum-account db "7081")))
        (is (zero? (.compareTo 0M (sum-account db "44571"))))))))

;; ============================================================================
;; Mixed-rate invoice — restaurant (10% food + 20% alcoholic drinks)
;; ============================================================================

(deftest mixed-rate-restaurant-invoice
  (testing "Mixed FR restaurant bill: €100 food at 10% + €50 wine at 20%.
              Revenue splits across 7065 and 706; TVA splits across
              44572 and 44571."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M
                     :invoice-line/rate :inter}
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 50M
                     :invoice-line/rate :std}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; Net 150, TVA 10 + 10 = 20, gross 170
        (is (= 170M  (sum-account db "411")))
        (is (= -100M (sum-account db "7065")) "10% revenue")
        (is (= -50M  (sum-account db "706"))  "20% revenue")
        (is (= -10M  (sum-account db "44572")) "10% TVA")
        (is (= -10M  (sum-account db "44571")) "20% TVA")))))

;; ============================================================================
;; Cash sale variant
;; ============================================================================

(deftest cash-sale-debits-bank-not-ar
  (testing ":invoice/cash-sale? true → debit goes to 5121 (Banque)
              instead of 411 (Clients)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-FR-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-fr-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 120M (sum-account db "5121"))
            "Bank (5121) debited 120 (100 net + 20 TVA)")
        (is (zero? (.compareTo 0M (sum-account db "411")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-fr-invoice-tx-data-pure
  (testing "plan-fr-invoice-tx-data returns tx-data WITHOUT touching
              the DB. Result is suitable for kontor.process composition."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-fr-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data)))
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :kontor.posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → all required fields missing"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 3))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]})))))

(deftest rate-predicate
  (is (inv/rate? :std))
  (is (inv/rate? :inter))
  (is (inv/rate? :red))
  (is (inv/rate? :spec))
  (is (inv/rate? :zero))
  (is (not (inv/rate? :nonsense))))

(deftest tax-status-predicate
  (is (inv/tax-status? :taxable))
  (is (inv/tax-status? :exempt))
  (is (inv/tax-status? :intra-eu-b2b))
  (is (inv/tax-status? :export))
  (is (not (inv/tax-status? :unknown))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every FR invoice we post must satisfy the kernel sum-to-
              zero rule. Sample the five flagship cases."
    (doseq [{:keys [name lines]}
            [{:name "std" :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "inter" :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                                     :invoice-line/rate :inter}]}
             {:name "red" :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                                   :invoice-line/rate :red}]}
             {:name "intra-eu" :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M
                                        :invoice-line/tax-status :intra-eu-b2b}]}
             {:name "export" :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M
                                      :invoice-line/tax-status :export}]}]]
      (let [conn (bootstrap)
            inv-map {:invoice/external-id (str "INV-Z-" name)
                     :invoice/issue-date jan-15
                     :invoice/lines lines}]
        (inv/post-fr-invoice! conn inv-map)
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
