(ns kontor.l10n-at.uva-test
  "Austrian UVA end-to-end:
     - install Kontenrahmen
     - post fixture invoices at 20% / 13% / 10%
     - run UVA and verify field codes against hand-computed expected
       values, mirroring the DE UStVA test pattern."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.l10n-at.uva :as uva]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Verkaufsrechnungen"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- account-eid [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- post-sale!
  "Post an Austrian VAT-rated sale.
   Three postings: receivable (gross debit) / revenue (net credit) /
   USt (vat credit). Rate-specific revenue + USt accounts:
     20% → 4000 / 3500
     13% → 4010 / 3510
     10% → 4020 / 3520"
  [conn external-id date net rate-pct]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
        receivable (account-eid db "2000")
        revenue (case rate-pct
                  20 (account-eid db "4000")
                  13 (account-eid db "4010")
                  10 (account-eid db "4020"))
        ust     (case rate-pct
                  20 (account-eid db "3500")
                  13 (account-eid db "3510")
                  10 (account-eid db "3520"))
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        rate-frac (/ (bigdec rate-pct) (bigdec 100))
        vat-bd (.setScale (.multiply net-bd rate-frac)
                          2 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd vat-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account receivable :posting/amount gross
                   :posting/commodity eur}
                  {:posting/account revenue :posting/amount (.negate net-bd)
                   :posting/commodity eur}
                  {:posting/account ust :posting/amount (.negate vat-bd)
                   :posting/commodity eur}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date)
                             %))))]
    (v/transact-with-validation conn tx)))

(defn- post-vorsteuer-bill!
  "Vendor bill with input VAT 20%.
   6000 (Personal) is wrong category — pick :code 7400 Bürobedarf
   for office supplies. Postings: 7400 expense / 2500 vorsteuer / 3300 payable."
  [conn external-id date net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
        expense (account-eid db "7400")
        vor (account-eid db "2500")
        pay (account-eid db "3300")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        vat-bd (.setScale (.multiply net-bd (bigdec "0.20"))
                          2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd vat-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account expense :posting/amount net-bd
                   :posting/commodity eur}
                  {:posting/account vor :posting/amount vat-bd
                   :posting/commodity eur}
                  {:posting/account pay :posting/amount (.negate gross)
                   :posting/commodity eur}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date)
                             %))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; Smoke
;; ============================================================================

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?a ...] :where [_ :account/code ?a]] db))]
    (is (>= n 25) (str "Loaded " n " accounts"))
    (is (account-eid db "4000") "Erlöse 20%")
    (is (account-eid db "4010") "Erlöse 13%")
    (is (account-eid db "4020") "Erlöse 10%")
    (is (account-eid db "2500") "Vorsteuer 20%")))

(deftest uva-empty-book
  (let [conn (bootstrap)
        r (uva/compute conn {:from jan-1 :to feb-1})]
    (is (every? money/zero? (vals (:uva/lines r))))
    (is (money/zero? (:uva/zahllast r)))))

(deftest uva-mixed-rates-sales
  (testing "20% + 13% + 10% sales:
              €1000 net 20% sale → field 022 = 1000, 022-ust = 200
              €500  net 13% sale → field 006 = 500,  006-ust = 65
              €300  net 10% sale → field 029 = 300,  029-ust = 30
              No vorsteuer → Vorsteuer 066 = 0
              Zahllast = 200 + 65 + 30 - 0 = 295"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 1000 20)
          _ (post-sale! conn "INV-2" jan-20 500 13)
          _ (post-sale! conn "INV-3" jan-25 300 10)
          r (uva/compute conn {:from jan-1 :to feb-1})
          lines (:uva/lines r)]
      (is (money/equiv? (money/money "1000" :EUR) (:022 lines)))
      (is (money/equiv? (money/money "500"  :EUR) (:006 lines)))
      (is (money/equiv? (money/money "300"  :EUR) (:029 lines)))
      (is (money/equiv? (money/money "200.00" :EUR) (:022-ust lines)))
      (is (money/equiv? (money/money "65.00"  :EUR) (:006-ust lines)))
      (is (money/equiv? (money/money "30.00"  :EUR) (:029-ust lines)))
      (is (money/equiv? (money/money "295.00" :EUR) (:uva/zahllast r))))))

(deftest uva-with-vorsteuer
  (testing "20% sale + Vorsteuer 20% bill:
              €1000 net 20% sale → 022-ust = 200
              €500  net 20% bill → Vorsteuer 066 = 100
              Zahllast = 200 - 100 = 100"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 1000 20)
          _ (post-vorsteuer-bill! conn "BILL-1" jan-25 500)
          r (uva/compute conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "100.00" :EUR) (:066 (:uva/lines r))))
      (is (money/equiv? (money/money "100.00" :EUR) (:uva/zahllast r))))))

(deftest uva-window-excludes-out-of-period
  (let [conn (bootstrap)
        _ (post-sale! conn "EARLY" #inst "2025-12-15T00:00:00Z" 999 20)
        _ (post-sale! conn "INSIDE" jan-15 1000 20)
        r (uva/compute conn {:from jan-1 :to feb-1})]
    (is (money/equiv? (money/money "1000" :EUR) (:022 (:uva/lines r))))))
