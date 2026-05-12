(ns kontor.invoice.bridge-test
  "Tests for kontor-invoice — ADR-036.

   Schema install, status-transition seeds, three-tier GL resolution,
   order→invoice bridge (full, partial-invoice via :order-item-
   billing), AcctgTrans posting (sum-to-zero balance, partner
   attribution), post-then-cancel."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.posting :as posting]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.sales.schema :as sales-schema]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (sales-schema/install! *conn*)
    (inv-schema/install! *conn*)
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Setup helpers
;; ============================================================================

(defn- seed-commodity! []
  (d/transact *conn* [{:commodity/symbol "EUR"
                       :commodity/name "Euro"
                       :commodity/precision 2
                       :commodity/iso-4217 "EUR"}]))

(defn- seed-partners! []
  (d/transact *conn*
              [{:partner/external-id "SELLER"
                :partner/type :org
                :partner/status :enabled
                :partner/name "Seller Co"}
               {:partner/external-id "BUYER"
                :partner/type :person
                :partner/status :enabled
                :partner/name "Customer Person"}]))

(defn- seed-accounts!
  "Seed minimal accounts for sales-revenue, sales-tax-payable, AR."
  []
  (d/transact *conn*
              [{:account/code "4000"
                :account/name "Sales Revenue"
                :account/path "4000"
                :account/type :revenue}
               {:account/code "1500"
                :account/name "Accounts Receivable"
                :account/path "1500"
                :account/type :asset}
               {:account/code "3800"
                :account/name "VAT Payable 19%"
                :account/path "3800"
                :account/type :liability}]))

(defn- seed-gl-defaults!
  "Seed tenant-wide :gl-account-default rows."
  []
  (d/transact *conn*
              [{:gl-account-default/account-type :sales-revenue
                :gl-account-default/account [:account/path "4000"]}
               {:gl-account-default/account-type :ar
                :gl-account-default/account [:account/path "1500"]}
               {:gl-account-default/account-type :sales-tax-payable
                :gl-account-default/account [:account/path "3800"]}]))

(defn- minimal-order! []
  (seed-commodity!)
  (seed-partners!)
  (d/transact *conn*
              [{:order/external-id "ORD-1"
                :order/type :sales
                :order/status :order.status/created
                :order/order-date #inst "2026-05-01"
                :order/entry-date #inst "2026-05-01"
                :order/currency [:commodity/symbol "EUR"]
                :order/bill-from-partner [:partner/external-id "SELLER"]
                :order/bill-to-partner [:partner/external-id "BUYER"]}
               {:order-item/order [:order/external-id "ORD-1"]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id "WIDGET-A"
                :order-item/description "Widget A"
                :order-item/quantity 10M
                :order-item/unit-price 25M
                :order-item/cancel-quantity 0M
                :order-item/status :order-item.status/approved}])
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :order/external-id ?xid]]
       (d/db *conn*) "ORD-1"))

;; ============================================================================
;; Schema + seeds
;; ============================================================================

(deftest schema-attrs-and-seeds-present
  (let [db (d/db *conn*)
        idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
    (doseq [a [:invoice/type :invoice/order :invoice/posted-at :invoice/entity
               :invoice-line/parent-line :invoice-line/order-item
               :invoice-line/gl-account-type :invoice-line/tax-auth-party
               :invoice-line/amount
               :order-item-billing/order-item :order-item-billing/invoice-line
               :order-item-billing/quantity :order-item-billing/identity
               :gl-account-default/account-type :gl-account-default/entity
               :gl-account-default/account :gl-account-default/identity]]
      (is (contains? idents a) (str "missing: " a)))
    (testing "invoice status transitions are seeded"
      (is (true? (sm/legal-transition? db :invoice :invoice/status
                                       :invoice.status/draft :invoice.status/ready)))
      (is (true? (sm/legal-transition? db :invoice :invoice/status
                                       :invoice.status/ready :invoice.status/sent)))
      (is (true? (sm/legal-transition? db :invoice :invoice/status
                                       :invoice.status/draft :invoice.status/sent)))
      (is (true? (sm/legal-transition? db :invoice :invoice/status
                                       :invoice.status/sent :invoice.status/paid))))))

;; ============================================================================
;; Three-tier GL resolution
;; ============================================================================

(deftest gl-resolution-tier-1-explicit-override
  (seed-accounts!)
  (let [db (d/db *conn*)
        override-eid (d/q '[:find ?a . :where [?a :account/path "4000"]] db)]
    (is (= override-eid
           (posting/resolve-gl-account
            db {:override-account override-eid
                :account-type :sales-revenue
                :entity nil})))))

(deftest gl-resolution-tier-2-tenant-default
  (seed-accounts!)
  (seed-gl-defaults!)
  (let [db (d/db *conn*)
        revenue-eid (d/q '[:find ?a . :where [?a :account/path "4000"]] db)]
    (is (= revenue-eid
           (posting/resolve-gl-account
            db {:account-type :sales-revenue
                :entity nil})))))

(deftest gl-resolution-throws-on-missing
  (seed-accounts!)
  ;; No defaults seeded — should throw
  (let [db (d/db *conn*)]
    (is (thrown? Exception
                 (posting/resolve-gl-account
                  db {:account-type :sales-revenue
                      :entity nil})))))

(deftest gl-resolution-tier-1-trumps-tier-2
  (seed-accounts!)
  (seed-gl-defaults!)
  (let [db (d/db *conn*)
        ar-eid (d/q '[:find ?a . :where [?a :account/path "1500"]] db)]
    ;; tier 1 wins even though tier 2 would point at the revenue acct
    (is (= ar-eid
           (posting/resolve-gl-account
            db {:override-account ar-eid
                :account-type :sales-revenue
                :entity nil})))))

;; ============================================================================
;; Order → invoice bridge
;; ============================================================================

(deftest make-invoice-from-order-creates-line-and-billing
  (let [order-eid (minimal-order!)]
    (inv/make-invoice-from-order! *conn* "ORD-1"
                                  {:external-id "INV-2026-0001"
                                   :issue-date #inst "2026-05-05"})
    (let [db (d/db *conn*)
          inv-eid (inv/by-external-id db "INV-2026-0001")
          invoice (inv/pull-invoice db inv-eid)
          lines (inv/lines-of db inv-eid)]
      (testing "invoice was created with order back-ref"
        (is (some? inv-eid))
        (is (= :sales (:invoice/type invoice)))
        (is (= "ORD-1" (-> invoice :invoice/order :order/external-id)))
        (is (= :invoice.status/draft (:invoice/status invoice))))
      (testing "one line per order-item, with order-item ref + amount"
        (is (= 1 (count lines)))
        (let [line (first lines)
              order-item-eid (d/q '[:find ?i . :in $ ?o
                                    :where [?i :order-item/order ?o]
                                            [?i :order-item/seq-id "00001"]]
                                  db order-eid)]
          (is (= order-item-eid (-> line :invoice-line/order-item :db/id)))
          (is (= 10M (:invoice-line/quantity line)))
          (is (= 25M (:invoice-line/unit-price line)))
          (is (= 250M (:invoice-line/amount line)))
          (is (= :sales-revenue (:invoice-line/gl-account-type line)))))
      (testing ":order-item-billing junction was created"
        (let [order-item-eid (d/q '[:find ?i . :in $ ?o
                                    :where [?i :order-item/order ?o]]
                                  db order-eid)]
          (is (= 10M (inv/partial-billed-quantity db order-item-eid))))))))

(deftest partial-invoice-subtracts-already-billed-quantity
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :order-item/order ?o]]
                      (d/db *conn*) order-eid)]
    ;; Make a first invoice for ALL 10 first; then prepare a second
    ;; invoice that should bill 0 (everything already billed).
    (inv/make-invoice-from-order! *conn* "ORD-1"
                                  {:external-id "INV-2026-0001"})
    (is (= 10M (inv/partial-billed-quantity (d/db *conn*) item-eid))
        "first invoice billed 10 units")
    ;; Second invoice — bill-qty should be 0
    (inv/make-invoice-from-order! *conn* "ORD-1"
                                  {:external-id "INV-2026-0002"})
    (let [lines-2 (inv/lines-of (d/db *conn*) "INV-2026-0002")]
      (is (= 1 (count lines-2)))
      (is (= 0M (-> lines-2 first :invoice-line/quantity))
          "second invoice has zero remaining quantity")
      (is (= 0M (-> lines-2 first :invoice-line/amount))))))

(deftest adjustment-lines-include-tax-discount-shipping
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :order-item/order ?o]]
                      (d/db *conn*) order-eid)]
    (d/transact *conn*
                [;; Header-level discount
                 {:order-adjustment/order order-eid
                  :order-adjustment/scope order-eid
                  :order-adjustment/type :discount
                  :order-adjustment/amount -10M}
                 ;; Line-level tax
                 {:order-adjustment/order order-eid
                  :order-adjustment/scope item-eid
                  :order-adjustment/type :tax
                  :order-adjustment/amount 47.50M
                  :order-adjustment/tax-auth-geo-id "DE"}])
    (inv/make-invoice-from-order! *conn* "ORD-1"
                                  {:external-id "INV-ADJ-1"})
    (let [db (d/db *conn*)
          lines (inv/lines-of db "INV-ADJ-1")
          types (set (map :invoice-line/gl-account-type lines))]
      (is (= 3 (count lines)) "1 product + 1 discount + 1 tax line")
      (is (= #{:sales-revenue :discount-given :sales-tax-payable} types)))))

;; ============================================================================
;; Posting bridge
;; ============================================================================

(deftest post-to-ledger-creates-balanced-transaction
  (let [_order-eid (minimal-order!)]
    (seed-accounts!)
    (seed-gl-defaults!)
    (inv/make-invoice-from-order! *conn* "ORD-1"
                                  {:external-id "INV-POST-1"})
    (let [{:keys [transaction-eid invoice-eid]}
          (inv/post-to-ledger! *conn* "INV-POST-1"
                               {:posted-at #inst "2026-05-10"})
          db (d/db *conn*)
          postings (->> (d/q '[:find [?p ...]
                               :in $ ?tx
                               :where [?p :posting/transaction ?tx]]
                             db transaction-eid)
                        (map #(d/pull db '[*] %)))
          sum (reduce (fn [acc {:posting/keys [amount]}]
                        (.add ^java.math.BigDecimal acc
                              ^java.math.BigDecimal amount))
                      0M postings)]
      (testing "invoice transitioned to :sent + posted-at set"
        (let [inv (inv/pull-invoice db invoice-eid)]
          (is (= :invoice.status/sent (:invoice/status inv)))
          (is (= #inst "2026-05-10" (:invoice/posted-at inv)))
          (is (some? (:invoice/transaction inv)))))
      (testing "postings sum to zero (sum-to-zero invariant)"
        (is (= 0 (.compareTo ^java.math.BigDecimal sum 0M))))
      (testing "one product line (credit revenue) + one AR (debit)"
        (is (= 2 (count postings)))))))

(deftest post-to-ledger-rejects-already-posted-invoice
  (let [_ (minimal-order!)
        _ (seed-accounts!)
        _ (seed-gl-defaults!)
        _ (inv/make-invoice-from-order! *conn* "ORD-1"
                                        {:external-id "INV-POST-2"})]
    (inv/post-to-ledger! *conn* "INV-POST-2")
    (testing "posting an already-:sent invoice throws"
      (is (thrown? Exception
                   (inv/post-to-ledger! *conn* "INV-POST-2"))))))

(deftest post-then-mark-paid
  (let [_ (minimal-order!)
        _ (seed-accounts!)
        _ (seed-gl-defaults!)
        _ (inv/make-invoice-from-order! *conn* "ORD-1"
                                        {:external-id "INV-PAID-1"})]
    (inv/post-to-ledger! *conn* "INV-PAID-1")
    (inv/mark-paid! *conn* "INV-PAID-1" {:reason "bank reconciled"})
    (let [db (d/db *conn*)
          inv (inv/pull-invoice db "INV-PAID-1")]
      (is (= :invoice.status/paid (:invoice/status inv))))))

(deftest cancel-draft-invoice
  (let [_ (minimal-order!)
        _ (inv/make-invoice-from-order! *conn* "ORD-1"
                                        {:external-id "INV-CANCEL-1"})]
    (inv/cancel! *conn* "INV-CANCEL-1" {:reason "customer abandoned"})
    (is (= :invoice.status/cancelled
           (:invoice/status (inv/pull-invoice (d/db *conn*) "INV-CANCEL-1"))))))

;; ============================================================================
;; Totals
;; ============================================================================

(deftest total-of-sums-line-amounts
  (minimal-order!)
  (inv/make-invoice-from-order! *conn* "ORD-1" {:external-id "INV-T-1"})
  (is (= 0 (.compareTo ^java.math.BigDecimal
                       (inv/total-of (d/db *conn*) "INV-T-1")
                       250M))
      "10 × 25 = 250"))
