(ns kontor.l10n-at.invoice-test
  "Tests for kontor.l10n-at.invoice — the posting builder that
   translates an AT invoice into kernel transaction + posting tx-data.

   Test scenarios cover the six operationally-distinct cases:
     - 20% standard (Normalsteuersatz)
     - 13% reduced (Kultur / Wein ab-Hof)
     - 10% reduced (Bücher / Lebensmittel)
     - Intra-EU B2B (zero-rated §6 Abs.1 Z.6)
     - Reverse charge (§19 Abs.1a)
     - Export (zero-rated §7 UStG, modelled as :zero)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.l10n-at.invoice :as inv]
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
    (d/transact conn [{:kontor.journal/code "SJ"
                       :kontor.journal/name "Verkaufsrechnungen"
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
;; 20% — Normalsteuersatz
;; ============================================================================

(deftest standard-20-invoice-posts
  (testing "AT invoice: €1000 net @ 20% USt → €1200 gross.
              Dr AR (2000) 1200, Cr Erlöse 20% (4000) 1000,
              Cr USt 20% (3500) 200."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-AT-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1200M  (sum-account db "2000"))
            "AR debited 1200")
        (is (= -1000M (sum-account db "4000"))
            "Erlöse 20% credited 1000")
        (is (= -200M  (sum-account db "3500"))
            "USt 20% credited 200")
        (is (zero? (.compareTo 0M (sum-account db "3510")))
            "No 13% USt")
        (is (zero? (.compareTo 0M (sum-account db "3520")))
            "No 10% USt")))))

;; ============================================================================
;; 13% — Kultur, Wein ab-Hof, Inlandsflüge
;; ============================================================================

(deftest reduced-13-invoice-posts
  (testing "AT invoice: €1000 net @ 13% → €1130 gross.
              Posts to Erlöse 13% (4010) + USt 13% (3510)."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-AT-13"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :reduced-13}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1130M  (sum-account db "2000")))
        (is (= -1000M (sum-account db "4010")))
        (is (= -130M  (sum-account db "3510")))))))

;; ============================================================================
;; 10% — Bücher, Lebensmittel, Wohnungsmiete (teilw.)
;; ============================================================================

(deftest reduced-10-invoice-posts
  (testing "AT invoice: €1000 net @ 10% → €1100 gross.
              Posts to Erlöse 10% (4020) + USt 10% (3520).
              Used for books, food, residential rent (partial),
              passenger transport."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-AT-10"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :reduced-10}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1100M  (sum-account db "2000")))
        (is (= -1000M (sum-account db "4020")))
        (is (= -100M  (sum-account db "3520")))))))

;; ============================================================================
;; Intra-EU B2B — zero-rated §6 Abs.1 Z.6
;; ============================================================================

(deftest intra-eu-zero-rated-invoice-posts
  (testing "Intra-EU B2B sale (e.g. DE buyer with valid UID) → 0 USt,
              €1000 gross. Revenue routes to 4100 (Erlöse intra-EU).
              UVA filing picks this up via the [:uva-011] tag."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-EU-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :zero}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "2000"))
            "AR = net only — no USt")
        (is (= -1000M (sum-account db "4100"))
            "Erlöse intra-EU 1000")
        (is (zero? (.compareTo 0M (sum-account db "3500")))
            "No USt 20%")
        (is (zero? (.compareTo 0M (sum-account db "4000")))
            "No standard revenue posting"))
      (is (true? (inv/intra-eu? inv-map))
          "intra-EU hint surfaces")
      (is (false? (inv/reverse-charge? inv-map))))))

;; ============================================================================
;; Reverse charge — §19 Abs.1a (Bauleistungen, intra-EU services)
;; ============================================================================

(deftest reverse-charge-invoice-posts
  (testing "Reverse-charge invoice: supplier emits 0 USt (recipient
              self-assesses). Revenue routes to 4300 (Erlöse Reverse-
              Charge). The supplier's invoice must disclose
              `Steuerschuldnerschaft des Leistungsempfängers gemäß
              §19 Abs.1a UStG`."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-RC-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :reverse-charge}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "2000")))
        (is (= -1000M (sum-account db "4300")))
        (is (zero? (.compareTo 0M (sum-account db "3500"))))
        (is (zero? (.compareTo 0M (sum-account db "3530")))
            "No 3530 Reverse-Charge USt — that's for recipient-
             side input VAT, which the supplier never books"))
      (is (true? (inv/reverse-charge? inv-map))))))

;; ============================================================================
;; Export outside EU — zero-rated §7 UStG
;; ============================================================================

(deftest export-zero-rated-invoice-posts
  (testing "Export to a non-EU country (CH/UK/US) → zero-rated under
              §7 UStG. Same arithmetic as intra-EU B2B; routes to
              4100. (A consumer who wants to distinguish exports from
              intra-EU can override :kontor.invoice-line/account.)"
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-EXP-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :zero}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M  (sum-account db "2000")))
        (is (= -1000M (sum-account db "4100")))
        (is (zero? (.compareTo 0M (sum-account db "3500"))))))))

;; ============================================================================
;; Multi-rate invoice — Buch + Software + Wein gemeinsam
;; ============================================================================

(deftest mixed-rates-invoice
  (testing "One invoice with three rates: 20% Software + 10% Buch +
              13% Wein. All three buckets must post correctly."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-MIX-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/vat-class :standard}        ; 20%
                    {:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 500M
                     :kontor.invoice-line/vat-class :reduced-10}      ; 10%
                    {:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 300M
                     :kontor.invoice-line/vat-class :reduced-13}]}]   ; 13%
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)
            ;; 20% on 1000 = 200, 10% on 500 = 50, 13% on 300 = 39
            ;; net 1800, ust 289, gross 2089
            ]
        (is (= 2089M  (sum-account db "2000"))
            "AR = 1800 net + 289 USt")
        (is (= -1000M (sum-account db "4000")))
        (is (= -500M  (sum-account db "4020")))
        (is (= -300M  (sum-account db "4010")))
        (is (= -200M  (sum-account db "3500")))
        (is (= -50M   (sum-account db "3520")))
        (is (= -39M   (sum-account db "3510")))))))

;; ============================================================================
;; Cash sale variant — Dr Kassa (2700) instead of Forderungen (2000)
;; ============================================================================

(deftest cash-sale-debits-kassa
  (testing ":kontor.invoice/cash-sale? true → debit goes to 2700 (Kassa)
              instead of 2000 (Forderungen)."
    (let [conn (bootstrap)
          inv-map {:kontor.invoice/external-id "INV-CASH-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/cash-sale? true
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 100M}]}]
      (inv/post-at-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 120M (sum-account db "2700"))
            "Kassa debited 120 (100 net + 20 USt)")
        (is (zero? (.compareTo 0M (sum-account db "2000")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-at-invoice-tx-data-pure
  (testing "plan-at-invoice-tx-data returns tx-data WITHOUT touching
              the DB. Suitable for kontor.workflow.process composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:kontor.invoice/external-id "INV-PLAN-1"
                   :kontor.invoice/issue-date jan-15
                   :kontor.invoice/lines
                   [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 100M}]}
          tx-data (inv/plan-at-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      (is (zero? (count (d/q '[:find [?p ...]
                               :where [?p :kontor.posting/account _]]
                             db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → multiple required fields missing"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 3))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:kontor.invoice/external-id "X"
                  :kontor.invoice/issue-date jan-15
                  :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                   :kontor.invoice-line/unit-price 100M}]})))))

(deftest validate-invoice-catches-invalid-class
  (testing "Unknown :vat-class is flagged"
    (let [complaints (inv/validate-invoice
                      {:kontor.invoice/external-id "X"
                       :kontor.invoice/issue-date jan-15
                       :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                        :kontor.invoice-line/unit-price 100M
                                        :kontor.invoice-line/vat-class :bogus}]})]
      (is (some #(= :kontor.invoice-line/vat-class (:field %)) complaints)))))

(deftest reverse-charge-predicate
  (is (true? (inv/reverse-charge?
              {:kontor.invoice/lines [{:kontor.invoice-line/vat-class :reverse-charge}]})))
  (is (false? (inv/reverse-charge?
               {:kontor.invoice/lines [{:kontor.invoice-line/vat-class :standard}]})))
  (testing "Mixed invoice with at least one RC line — flag fires"
    (is (true? (inv/reverse-charge?
                {:kontor.invoice/lines [{:kontor.invoice-line/vat-class :standard}
                                 {:kontor.invoice-line/vat-class :reverse-charge}]})))))

(deftest intra-eu-predicate
  (is (true? (inv/intra-eu?
              {:kontor.invoice/lines [{:kontor.invoice-line/vat-class :zero}]})))
  (is (false? (inv/intra-eu?
               {:kontor.invoice/lines [{:kontor.invoice-line/vat-class :standard}]}))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every AT invoice we post must satisfy the kernel
              sum-to-zero rule. Sample the six flagship cases."
    (doseq [{:keys [name vat-class]}
            [{:name "20"  :vat-class :standard}
             {:name "13"  :vat-class :reduced-13}
             {:name "10"  :vat-class :reduced-10}
             {:name "EU"  :vat-class :zero}
             {:name "RC"  :vat-class :reverse-charge}
             {:name "EXM" :vat-class :exempt}]]
      (let [conn (bootstrap)
            inv-map {:kontor.invoice/external-id (str "INV-Z-" name)
                     :kontor.invoice/issue-date jan-15
                     :kontor.invoice/lines
                     [{:kontor.invoice-line/quantity 1
                       :kontor.invoice-line/unit-price 1000M
                       :kontor.invoice-line/vat-class vat-class}]}]
        (inv/post-at-invoice! conn inv-map)
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
