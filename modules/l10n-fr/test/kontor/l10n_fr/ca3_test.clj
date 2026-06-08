(ns kontor.l10n-fr.ca3-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.ca3 :as ca3]
            [kontor.l10n-fr.chart :as chart]
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
    (d/transact conn [{:kontor.journal/code "VTE"
                       :kontor.journal/name "Journal des ventes"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-sale!
  "Post a French VAT sale.
   Three postings: 411 client (debit gross) /
                   706 (or 7065/7066/7067) revenue (credit net) /
                   44571..44574 TVA collectée (credit vat).
   rate-pct ∈ {20 10 5.5 2.1}"
  [conn external-id date net rate-pct]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        client (ace db "4111")
        revenue (case rate-pct
                  20  (ace db "706")
                  10  (ace db "7065")
                  5.5 (ace db "7066")
                  2.1 (ace db "7067"))
        tva (case rate-pct
              20  (ace db "44571")
              10  (ace db "44572")
              5.5 (ace db "44573")
              2.1 (ace db "44574"))
        jnl (:db/id (d/entity db [:kontor.journal/code "VTE"]))
        net-bd (bigdec net)
        rate-frac (/ (bigdec rate-pct) (bigdec 100))
        vat-bd (.setScale (.multiply net-bd rate-frac) 2 java.math.RoundingMode/HALF_EVEN)
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
                 [{:kontor.posting/account client :kontor.posting/amount gross :kontor.posting/commodity eur}
                  {:kontor.posting/account revenue :kontor.posting/amount (.negate net-bd) :kontor.posting/commodity eur}
                  {:kontor.posting/account tva :kontor.posting/amount (.negate vat-bd) :kontor.posting/commodity eur}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-bill!
  "Vendor bill 20% VAT — déductible. Postings: 606 expense + 44566 TVA déd + 401 fournisseur."
  [conn external-id date net]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        exp (ace db "606")
        vor (ace db "44566")
        pay (ace db "401")
        jnl (:db/id (d/entity db [:kontor.journal/code "VTE"]))
        net-bd (bigdec net)
        vat-bd (.setScale (.multiply net-bd (bigdec "0.20")) 2 java.math.RoundingMode/HALF_EVEN)
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
                 [{:kontor.posting/account exp :kontor.posting/amount net-bd :kontor.posting/commodity eur}
                  {:kontor.posting/account vor :kontor.posting/amount vat-bd :kontor.posting/commodity eur}
                  {:kontor.posting/account pay :kontor.posting/amount (.negate gross) :kontor.posting/commodity eur}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?a ...] :where [_ :kontor.account/code ?a]] db))]
    (is (>= n 25) (str "loaded " n " accounts"))
    (is (ace db "44571") "TVA collectée 20%")
    (is (ace db "44566") "TVA déductible biens/services")
    (is (ace db "706")   "Prestations de services")))

(deftest ca3-empty-book
  (let [conn (bootstrap)
        r (ca3/compute conn {:from jan-1 :to feb-1})]
    (is (every? money/zero? (vals (:ca3/lines r))))
    (is (money/zero? (:ca3/tva-a-payer r)))))

(deftest ca3-mixed-rates-sales
  (testing "Mixed sales:
              €1000 net 20%  → 01-base = 1000, 08-vt20 = 200
              €500  net 10%  → 02-base = 500,  08-vt10 = 50
              €200  net 5.5% → 03-base = 200,  08-vt55 = 11
              No vorsteuer
              TVA à payer = 200 + 50 + 11 = 261"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 1000 20)
          _ (post-sale! conn "INV-2" jan-20 500 10)
          _ (post-sale! conn "INV-3" jan-25 200 5.5)
          r (ca3/compute conn {:from jan-1 :to feb-1})
          lines (:ca3/lines r)]
      (is (money/equiv? (money/money "1000" :EUR) (:01-base lines)))
      (is (money/equiv? (money/money "500"  :EUR) (:02-base lines)))
      (is (money/equiv? (money/money "200"  :EUR) (:03-base lines)))
      (is (money/equiv? (money/money "200.00" :EUR) (:08-vt20 lines)))
      (is (money/equiv? (money/money "50.00"  :EUR) (:08-vt10 lines)))
      (is (money/equiv? (money/money "11.00"  :EUR) (:08-vt55 lines)))
      (is (money/equiv? (money/money "261.00" :EUR) (:ca3/tva-a-payer r))))))

(deftest ca3-with-vorsteuer
  (testing "20% sale + 20% bill: TVA à payer = 200 - 100 = 100"
    (let [conn (bootstrap)
          _ (post-sale! conn "INV-1" jan-15 1000 20)
          _ (post-bill! conn "BILL-1" jan-25 500)
          r (ca3/compute conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "100.00" :EUR) (:20 (:ca3/lines r))))
      (is (money/equiv? (money/money "100.00" :EUR) (:ca3/tva-a-payer r))))))
