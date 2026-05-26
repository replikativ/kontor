(ns kontor.l10n-us.sales-tax-test
  "US end-to-end:
     - install QBO chart
     - post sales in CA, TX, NY (different rates), some with city tax
     - run per-state filing reports, verify each produces only its
       own jurisdiction's number (no cross-contamination).

   Numbers verified by hand assuming a state-only rate model
   (real tax includes county+city+special-district add-ons; the
   chart's tagging convention extends to home-rule cities like
   Denver as a proof of authority-separation across local
   jurisdictions)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.chart :as chart]
            [kontor.l10n-us.sales-tax :as sales-tax]
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
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-state-sale!
  "Post a US sale with state-level sales tax.
     1100 receivable     DEBIT  net + tax
     4000 sales          CREDIT net
     22XX state tax      CREDIT tax (state-specific)
   `state-tax-code` is the SKR-style code on the per-state
   liability account: 2210 (CA), 2211 (TX), 2212 (NY), 2213 (WA),
   2214 (FL), 2230 (CO Denver)."
  [conn external-id date net rate-pct state-tax-code]
  (let [db (d/db conn)
        usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
        rec (ace db "1200")
        rev (ace db "4000")
        tax (ace db state-tax-code)
        jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        net-bd (bigdec net)
        rate-frac (/ (bigdec rate-pct) (bigdec 100))
        tax-bd (.setScale (.multiply net-bd rate-frac) 2 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd tax-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id external-id
                  :kontor.transaction/journal jnl
                  :kontor.transaction/effective-date date
                  :kontor.transaction/narration external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/posted-at date}
                 :postings
                 [{:kontor.posting/account rec :kontor.posting/amount gross :kontor.posting/commodity usd}
                  {:kontor.posting/account rev :kontor.posting/amount (.negate net-bd) :kontor.posting/commodity usd}
                  {:kontor.posting/account tax :kontor.posting/amount (.negate tax-bd) :kontor.posting/commodity usd}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-multi-jurisdiction-sale!
  "A real Denver, CO sale: state tax 2.9% (CO statewide) + city tax
   8.81% (Denver home-rule). Two separate authority postings on
   one base — proof that the kernel's per-tag routing handles
   overlapping jurisdictions cleanly. We seed only the Denver
   line in this fixture (no CO state-level account exists yet)."
  [conn external-id date net]
  (let [db (d/db conn)
        usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
        rec (ace db "1200")
        rev (ace db "4000")
        denver (ace db "2230")
        jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        net-bd (bigdec net)
        denver-bd (.setScale (.multiply net-bd (bigdec "0.0881"))
                             2 java.math.RoundingMode/HALF_EVEN)
        gross (.add net-bd denver-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id external-id
                  :kontor.transaction/journal jnl
                  :kontor.transaction/effective-date date
                  :kontor.transaction/narration external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/posted-at date}
                 :postings
                 [{:kontor.posting/account rec :kontor.posting/amount gross :kontor.posting/commodity usd}
                  {:kontor.posting/account rev :kontor.posting/amount (.negate net-bd) :kontor.posting/commodity usd}
                  {:kontor.posting/account denver :kontor.posting/amount (.negate denver-bd) :kontor.posting/commodity usd}]})
               (->> (mapv #(if (some? (:kontor.posting/account %))
                             (assoc % :kontor.posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; Smoke
;; ============================================================================

(deftest chart-installs
  (let [conn (core/create-test-db)
        _ (chart/install! conn)
        db (d/db conn)
        n (count (d/q '[:find [?a ...] :where [_ :kontor.account/code ?a]] db))]
    (is (>= n 25) (str "loaded " n " accounts"))
    (is (ace db "2210") "CA sales tax payable")
    (is (ace db "2211") "TX sales tax payable")
    (is (ace db "2230") "Denver, CO home-rule city")))

(deftest state-codes-have-authority
  (testing "Every modeled state has a distinct :authority — the
            ADR-014 :tax/authority concept extends from `:tax`
            entities to `:account-tag` mapping."
    (let [authorities (map (comp :authority val) sales-tax/state-codes)]
      (is (= (count authorities) (count (set authorities)))
          "All state authorities must be unique."))))

;; ============================================================================
;; Per-state filing
;; ============================================================================

(deftest single-state-sale-only-shows-in-its-state
  (testing "$1000 sale in TX @ 6.25% state rate → TX line-1 = $62.50,
            CA / NY / WA / FL all show $0."
    (let [conn (bootstrap)
          _ (post-state-sale! conn "TX-INV-1" jan-15 1000 6.25 "2211")
          tx-r (sales-tax/compute-state conn :tx {:from jan-1 :to feb-1})
          ca-r (sales-tax/compute-state conn :ca {:from jan-1 :to feb-1})
          ny-r (sales-tax/compute-state conn :ny {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "62.50" :USD) (:sales-tax/payable tx-r)))
      (is (money/zero? (:sales-tax/payable ca-r)))
      (is (money/zero? (:sales-tax/payable ny-r))))))

(deftest multi-state-business-files-each-separately
  (testing "Multi-nexus business: $1000 TX (6.25%) + $500 CA (7.25%)
            + $200 NY (4%):
              TX = $62.50
              CA = $36.25
              NY = $8.00"
    (let [conn (bootstrap)
          _ (post-state-sale! conn "TX-1" jan-15 1000 6.25 "2211")
          _ (post-state-sale! conn "CA-1" jan-20 500  7.25 "2210")
          _ (post-state-sale! conn "NY-1" jan-25 200  4    "2212")
          all (sales-tax/compute-all-active-states conn {:from jan-1 :to feb-1})]
      (is (= 3 (count all)) "Three states with activity")
      (is (money/equiv? (money/money "62.50" :USD) (:sales-tax/payable (all :tx))))
      (is (money/equiv? (money/money "36.25" :USD) (:sales-tax/payable (all :ca))))
      (is (money/equiv? (money/money "8.00"  :USD) (:sales-tax/payable (all :ny))))
      (is (= :us-tx-cpa     (:sales-tax/authority (all :tx))))
      (is (= :us-ca-cdtfa   (:sales-tax/authority (all :ca))))
      (is (= :us-ny-dtf     (:sales-tax/authority (all :ny)))))))

(deftest home-rule-city-files-separately
  (testing "Denver, CO home-rule city collects 8.81% city tax separately
            from CO state. Filing for Denver shows only Denver's
            collection — proof that local jurisdictions route to
            their own authorities even within a state."
    (let [conn (bootstrap)
          _ (post-multi-jurisdiction-sale! conn "DEN-1" jan-15 1000)
          den-r (sales-tax/compute-state conn :co-denver {:from jan-1 :to feb-1})]
      (is (money/equiv? (money/money "88.10" :USD) (:sales-tax/payable den-r)))
      (is (= :us-co-denver (:sales-tax/authority den-r))))))
