(ns kontor.l10n-de.ustva-test
  "End-to-end DE VAT report test:
     - install SKR04
     - post a small fixture book (sales 19% / 7%, vorsteuer)
     - run the UStVA report
     - verify line numbers match what an accountant would compute by hand"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.ustva :as ustva]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")

(defn- bootstrap
  "Fresh DB with kernel schema + invariants + SKR04 + a sales journal."
  []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales invoices"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- account-eid [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-invoice-19!
  "Post a German VAT-19% sales invoice for `net` EUR.
   Three postings:
     - Forderungen (1400)            DEBIT  net + 19% VAT
     - Erlöse 19%   (4400)           CREDIT net
     - Umsatzsteuer 19% (3801)       CREDIT 19% VAT
   This is the standard SKR04 sales-with-VAT shape."
  [conn external-id date net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        receivable (account-eid db "1400")
        revenue    (account-eid db "4400")
        ust-19     (account-eid db "3801")
        jnl        (:db/id (d/entity db [:kontor.journal/code "INV"]))
        net-bd  (bigdec net)
        vat-bd  (.setScale (.multiply net-bd (bigdec "0.19"))
                           2 java.math.RoundingMode/HALF_EVEN)
        gross   (.add net-bd vat-bd)
        tx      (-> (posting/build-transaction
                     {:transaction
                      {:kontor.transaction/external-id external-id
                       :kontor.transaction/journal jnl
                       :kontor.transaction/effective-date date
                       :kontor.transaction/narration external-id
                       :kontor.transaction/state :posted
                       :kontor.transaction/posted-at date}
                      :postings
                      [{:kontor.posting/account receivable :kontor.posting/amount gross
                        :kontor.posting/commodity eur}
                       {:kontor.posting/account revenue :kontor.posting/amount (.negate net-bd)
                        :kontor.posting/commodity eur}
                       {:kontor.posting/account ust-19 :kontor.posting/amount (.negate vat-bd)
                        :kontor.posting/commodity eur}]})
                    (->> (mapv #(if (some? (:kontor.posting/account %))
                                  (assoc % :kontor.posting/posted-at date)
                                  %))))]
    (v/transact-with-validation conn tx)))

(defn- post-invoice-7!
  "Same shape with VAT 7% (4300 → 3806)."
  [conn external-id date net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        receivable (account-eid db "1400")
        revenue    (account-eid db "4300")
        ust-7      (account-eid db "3806")
        jnl        (:db/id (d/entity db [:kontor.journal/code "INV"]))
        net-bd (bigdec net)
        vat-bd (.setScale (.multiply net-bd (bigdec "0.07"))
                          2 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd vat-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id external-id
                  :kontor.transaction/journal jnl
                  :kontor.transaction/effective-date date
                  :kontor.transaction/narration external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/posted-at date}
                 :postings
                 [{:kontor.posting/account receivable :kontor.posting/amount gross
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account revenue :kontor.posting/amount (.negate net-bd)
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account ust-7 :kontor.posting/amount (.negate vat-bd)
                   :kontor.posting/commodity eur}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date)
                             %))))]
    (v/transact-with-validation conn tx)))

(defn- post-supplier-bill-19!
  "Vendor bill with input VAT 19%:
     - Aufwendungen 6800 (Bürobedarf)  DEBIT  net
     - Vorsteuer 19% (1576)            DEBIT  19% VAT
     - Verbindlichkeiten (3300)        CREDIT net + 19% VAT"
  [conn external-id date net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        expense    (account-eid db "6800")
        vorst-19   (account-eid db "1576")
        payable    (account-eid db "3300")
        jnl        (:db/id (d/entity db [:kontor.journal/code "INV"]))
        net-bd  (bigdec net)
        vat-bd  (.setScale (.multiply net-bd (bigdec "0.19"))
                           2 java.math.RoundingMode/HALF_EVEN)
        gross   (.add net-bd vat-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id external-id
                  :kontor.transaction/journal jnl
                  :kontor.transaction/effective-date date
                  :kontor.transaction/narration external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/posted-at date}
                 :postings
                 [{:kontor.posting/account expense :kontor.posting/amount net-bd
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account vorst-19 :kontor.posting/amount vat-bd
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account payable :kontor.posting/amount (.negate gross)
                   :kontor.posting/commodity eur}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date)
                             %))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; SKR04 install
;; ============================================================================

(deftest skr04-installs-cleanly
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        accounts (d/q '[:find [?code ...] :where [_ :kontor.account/code ?code]] db)
        tags (d/q '[:find [?n ...] :where [_ :kontor.account-tag/name ?n]] db)]
    (is (>= (count accounts) 30) (str "loaded " (count accounts) " accounts"))
    (is (account-eid db "4400") "Erlöse 19% present")
    (is (account-eid db "1576") "Vorsteuer 19% present")
    (is (contains? (set tags) "ust-81") "tag :ust-81 materialized")
    (is (contains? (set tags) "ust-66") "tag :ust-66 materialized")))

(deftest skr04-install-is-idempotent
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        n1 (count (d/q '[:find [?a ...] :where [?a :kontor.account/code _]] (d/db conn)))
        _ (chart/install! conn)
        n2 (count (d/q '[:find [?a ...] :where [?a :kontor.account/code _]] (d/db conn)))]
    (is (= n1 n2) "Re-install must not duplicate accounts.")))

;; ============================================================================
;; UStVA: small fixture book → known line values
;; ============================================================================

(deftest ustva-empty-book
  (testing "Empty book → all UStVA lines are zero in EUR."
    (let [conn (bootstrap)
          r (ustva/compute conn {:from jan-1 :to feb-1})]
      (is (every? money/zero? (vals (:ustva/lines r)))
          "all 8 lines zero")
      (is (money/zero? (:ustva/zahllast r))
          "Zahllast zero"))))

(deftest ustva-single-19-percent-sale
  (testing "One €1000 net invoice at 19% → line 81 = 1000, line 81-ust = 190,
            Zahllast = 190."
    (let [conn (bootstrap)
          _ (post-invoice-19! conn "INV-1" jan-15 1000)
          r (ustva/compute conn {:from jan-1 :to feb-1})
          lines (:ustva/lines r)]
      (is (money/equiv? (money/money "1000" :EUR) (:81 lines))
          (str "line 81 = " (money/money->str (:81 lines))))
      (is (money/equiv? (money/money "190.00" :EUR) (:81-ust lines))
          (str "line 81-ust = " (money/money->str (:81-ust lines))))
      (is (money/equiv? (money/money "190.00" :EUR) (:ustva/zahllast r))
          "Zahllast = 190.00 EUR (USt 19% - Vorsteuer 0)"))))

(deftest ustva-mixed-rates-with-vorsteuer
  (testing "Mixed 19% + 7% sales + a vendor bill (Vorsteuer 19%):
              €1000 net 19% sale  → Box 81 = 1000, USt 81-ust = 190
              €500  net 7%  sale  → Box 86 = 500,  USt 86-ust = 35
              €200  net 19% bill  → Vorsteuer 66 = 38
              Zahllast = 190 + 35 - 38 = 187"
    (let [conn (bootstrap)
          _ (post-invoice-19!      conn "INV-1" jan-15 1000)
          _ (post-invoice-7!       conn "INV-2" jan-20  500)
          _ (post-supplier-bill-19! conn "BILL-1" jan-25  200)
          r (ustva/compute conn {:from jan-1 :to feb-1})
          lines (:ustva/lines r)]
      (is (money/equiv? (money/money "1000" :EUR) (:81 lines)))
      (is (money/equiv? (money/money "500"  :EUR) (:86 lines)))
      (is (money/equiv? (money/money "190.00" :EUR) (:81-ust lines)))
      (is (money/equiv? (money/money "35.00"  :EUR) (:86-ust lines)))
      (is (money/equiv? (money/money "38.00"  :EUR) (:66 lines))
          (str "Vorsteuer = " (money/money->str (:66 lines))))
      (is (money/equiv? (money/money "187.00" :EUR) (:ustva/zahllast r))
          "Zahllast = 190 + 35 - 38 = 187"))))

(deftest ustva-window-excludes-out-of-period-postings
  (testing "Postings dated before/after the window must not contribute."
    (let [conn (bootstrap)
          _ (post-invoice-19! conn "EARLY" #inst "2025-12-15T00:00:00Z" 999)
          _ (post-invoice-19! conn "INSIDE" jan-15 1000)
          _ (post-invoice-19! conn "LATE"  feb-1 999)  ;; on the boundary
          r (ustva/compute conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "1000" :EUR) (:81 (:ustva/lines r)))
          "Only INSIDE counted; EARLY and LATE excluded."))))

(deftest ustva-draft-postings-excluded
  (testing "Drafts don't contribute (default :include-states #{:posted})."
    (let [conn (bootstrap)
          db (d/db conn)
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          receivable (account-eid db "1400")
          revenue    (account-eid db "4400")
          ust-19     (account-eid db "3801")
          jnl        (:db/id (d/entity db [:kontor.journal/code "INV"]))
          ;; A DRAFT (no :posted state)
          tx (posting/build-transaction
              {:transaction
               {:kontor.transaction/external-id "DRAFT-1"
                :kontor.transaction/journal jnl
                :kontor.transaction/effective-date jan-15
                :kontor.transaction/narration "draft"
                :kontor.transaction/state :draft}
               :postings
               [{:kontor.posting/account receivable :kontor.posting/amount 1190M :kontor.posting/commodity eur}
                {:kontor.posting/account revenue :kontor.posting/amount -1000M :kontor.posting/commodity eur}
                {:kontor.posting/account ust-19 :kontor.posting/amount -190M :kontor.posting/commodity eur}]})
          _ (v/transact-with-validation conn tx)
          r (ustva/compute conn {:from jan-1 :to feb-1})]
      (is (money/zero? (:81 (:ustva/lines r))) "Draft excluded by default."))))
