(ns kontor.procurement.posting-test
  "End-to-end posting tests for the procurement P2P chain — ADR-042
 + fixes.

     PO → receipt + post-receipt-with-inventory! (Dr inventory /
                                                   Cr GR-IR)
        → make-invoice-from-order!
        → post-to-ledger!                        (Dr GR-IR /
                                                   Cr AP)

   The GR-IR residual per (order-item, commodity) should be zero
   after both legs land — that is the queryable invariant ADR-042
   sells."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.provider.costing-provider :as costing]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.posting :as inv-post]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.procurement.match :as match]
            [kontor.procurement.receipt :as receipt]
            [kontor.procurement.returns :as returns]
            [kontor.procurement.schema :as proc-schema]
            [kontor.sales.schema :as sales-schema]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (sales-schema/install! *conn*)
    (inv-schema/install! *conn*)
    (proc-schema/install! *conn*)
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Setup
;; ============================================================================

(defn- seed! []
  (d/transact *conn*
              [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:kontor.entity/code "ACME" :kontor.entity/name "Acme Inc"
                :kontor.entity/kind :operating :kontor.entity/active true}
               {:kontor.partner/external-id "BUYER" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Acme Inc"}
               {:kontor.partner/external-id "SUPPLIER" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Supplier Co"}
               {:kontor.partner/external-id "CUSTOMER" :kontor.partner/type :person
                :kontor.partner/status :enabled :kontor.partner/name "Customer Jane"}
               ;; Accounts
               {:kontor.account/code "1400" :kontor.account/name "Inventory"
                :kontor.account/path "1400" :kontor.account/type :asset}
               {:kontor.account/code "1200" :kontor.account/name "Accounts Receivable"
                :kontor.account/path "1200" :kontor.account/type :asset}
               {:kontor.account/code "4000" :kontor.account/name "Sales Revenue"
                :kontor.account/path "4000" :kontor.account/type :revenue}
               {:kontor.account/code "5000" :kontor.account/name "Purchase Expense"
                :kontor.account/path "5000" :kontor.account/type :expense}
               {:kontor.account/code "2000" :kontor.account/name "Accounts Payable"
                :kontor.account/path "2000" :kontor.account/type :liability}
               {:kontor.account/code "2150" :kontor.account/name "GR/IR Clearing"
                :kontor.account/path "2150" :kontor.account/type :liability}
               ;; GL defaults
               {:kontor.gl-account-default/account-type :inventory
                :kontor.gl-account-default/account [:kontor.account/path "1400"]}
               {:kontor.gl-account-default/account-type :ar
                :kontor.gl-account-default/account [:kontor.account/path "1200"]}
               {:kontor.gl-account-default/account-type :sales-revenue
                :kontor.gl-account-default/account [:kontor.account/path "4000"]}
               {:kontor.gl-account-default/account-type :purchase-expense
                :kontor.gl-account-default/account [:kontor.account/path "5000"]}
               {:kontor.gl-account-default/account-type :ap
                :kontor.gl-account-default/account [:kontor.account/path "2000"]}
               {:kontor.gl-account-default/account-type :gr-ir-clearing
                :kontor.gl-account-default/account [:kontor.account/path "2150"]}
               ;; Journals
               {:kontor.journal/code "PURCH" :kontor.journal/name "Purchase Journal"
                :kontor.journal/type :purchase}
               {:kontor.journal/code "SALES" :kontor.journal/name "Sales Journal"
                :kontor.journal/type :sales}]))

(defn- create-purchase-order!
  [{:keys [external-id qty unit-price]
    :or {external-id "PO-1" qty 10M unit-price 25M}}]
  (d/transact *conn*
              [{:kontor.order/external-id external-id
                :kontor.order/type :purchase
                :kontor.order/status :order.status/created
                :kontor.order/order-date #inst "2026-05-01"
                :kontor.order/entry-date #inst "2026-05-01"
                :kontor.order/currency [:kontor.commodity/symbol "EUR"]
                :kontor.order/bill-from-partner [:kontor.partner/external-id "SUPPLIER"]
                :kontor.order/bill-to-partner [:kontor.partner/external-id "BUYER"]
                :kontor.order/entity [:kontor.entity/code "ACME"]}
               {:kontor.sales.order-item/order [:kontor.order/external-id external-id]
                :kontor.sales.order-item/seq-id "00001"
                :kontor.sales.order-item/type :product
                :kontor.sales.order-item/product-id "WIDGET-A"
                :kontor.sales.order-item/quantity qty
                :kontor.sales.order-item/unit-price unit-price
                :kontor.sales.order-item/cancel-quantity 0M
                :kontor.sales.order-item/status :order-item.status/approved
                :kontor.procurement.order-item/category :direct
                :kontor.procurement.order-item/requires-receipt? true}])
  (let [db (d/db *conn*)
        order-eid (d/q '[:find ?e . :in $ ?xid
                         :where [?e :kontor.order/external-id ?xid]]
                       db external-id)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :kontor.sales.order-item/order ?o]]
                      db order-eid)]
    {:order-eid order-eid :item-eid item-eid}))

;; ============================================================================
;;: post-receipt-with-inventory!
;; ============================================================================

(deftest post-receipt-emits-dr-inventory-cr-gr-ir
  (testing ": receipt posts Dr Inventory 250 / Cr GR-IR 250"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      (receipt/make-receipt! *conn*
                             {:external-id "RCPT-1"
                              :order order-eid
                              :items [{:order-item item-eid
                                       :product-id "WIDGET-A"
                                       :quantity-accepted 10M
                                       :unit-cost 25M}]})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-1"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      (let [db (d/db *conn*)]
        (testing "receipt transitioned :pending → :accepted"
          (is (= :accepted
                 (sm/current-status db
                                    (receipt/by-external-id db "RCPT-1")
                                    :kontor.receipt/status))))
        (testing "valuation-layer materialized"
          (is (= 1 (d/q '[:find (count ?l) .
                          :in $ ?oi
                          :where [?l :kontor.valuation-layer/item ?oi]]
                        db item-eid))))
        (testing "Dr inventory 250.00"
          (is (= 0 (.compareTo (bigdec "250.00")
                               (or (d/q '[:find (sum ?amt) . :with ?p
                                          :where
                                          [?a :kontor.account/path "1400"]
                                          [?p :kontor.posting/account ?a]
                                          [?p :kontor.posting/amount ?amt]]
                                        db) 0M)))))
        (testing "Cr GR-IR 250.00 (= negative 250)"
          (is (= 0 (.compareTo (bigdec "-250.00")
                               (or (d/q '[:find (sum ?amt) . :with ?p
                                          :where
                                          [?a :kontor.account/path "2150"]
                                          [?p :kontor.posting/account ?a]
                                          [?p :kontor.posting/amount ?amt]]
                                        db) 0M)))))))))

(deftest post-receipt-fails-on-non-pending
  (testing ": posting a non-pending receipt throws"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      (receipt/make-receipt! *conn*
                             {:external-id "RCPT-2"
                              :order order-eid
                              :items [{:order-item item-eid
                                       :quantity-accepted 5M
                                       :unit-cost 25M}]})
      (receipt/accept-receipt! *conn* "RCPT-2")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must be :pending"
           (receipt/post-receipt-with-inventory!
            *conn* "RCPT-2"
            {:provider (costing/make-fifo-provider)
             :journal-ref [:kontor.journal/code "PURCH"]}))))))

(deftest post-receipt-fails-without-unit-cost
  (testing ": missing :kontor.receipt-item/unit-cost throws clearly"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      (receipt/make-receipt! *conn*
                             {:external-id "RCPT-3"
                              :order order-eid
                              :items [{:order-item item-eid
                                       :quantity-accepted 5M}]}) ; no unit-cost
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unit-cost required"
           (receipt/post-receipt-with-inventory!
            *conn* "RCPT-3"
            {:provider (costing/make-fifo-provider)
             :journal-ref [:kontor.journal/code "PURCH"]}))))))

(deftest post-receipt-with-multiple-items-atomic
  (testing ": multi-item receipt posts in one tx with distinct
            tempids per item (Dr inventory 250 + 180 = 430)"
    (seed!)
    (d/transact *conn*
                [{:kontor.order/external-id "PO-MULTI"
                  :kontor.order/type :purchase
                  :kontor.order/status :order.status/created
                  :kontor.order/order-date #inst "2026-05-01"
                  :kontor.order/entry-date #inst "2026-05-01"
                  :kontor.order/currency [:kontor.commodity/symbol "EUR"]
                  :kontor.order/bill-from-partner [:kontor.partner/external-id "SUPPLIER"]
                  :kontor.order/bill-to-partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order/entity [:kontor.entity/code "ACME"]}
                 {:kontor.sales.order-item/order [:kontor.order/external-id "PO-MULTI"]
                  :kontor.sales.order-item/seq-id "00001"
                  :kontor.sales.order-item/type :product
                  :kontor.sales.order-item/product-id "WIDGET-A"
                  :kontor.sales.order-item/quantity 10M
                  :kontor.sales.order-item/unit-price 25M
                  :kontor.sales.order-item/cancel-quantity 0M
                  :kontor.sales.order-item/status :order-item.status/approved
                  :kontor.procurement.order-item/category :direct
                  :kontor.procurement.order-item/requires-receipt? true}
                 {:kontor.sales.order-item/order [:kontor.order/external-id "PO-MULTI"]
                  :kontor.sales.order-item/seq-id "00002"
                  :kontor.sales.order-item/type :product
                  :kontor.sales.order-item/product-id "WIDGET-B"
                  :kontor.sales.order-item/quantity 6M
                  :kontor.sales.order-item/unit-price 30M
                  :kontor.sales.order-item/cancel-quantity 0M
                  :kontor.sales.order-item/status :order-item.status/approved
                  :kontor.procurement.order-item/category :direct
                  :kontor.procurement.order-item/requires-receipt? true}])
    (let [db (d/db *conn*)
          order-eid (d/q '[:find ?e . :where [?e :kontor.order/external-id "PO-MULTI"]] db)
          [item-a item-b] (vec (sort (d/q '[:find [?i ...]
                                            :in $ ?o
                                            :where [?i :kontor.sales.order-item/order ?o]]
                                          db order-eid)))]
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-MULTI"
        :order order-eid
        :items [{:order-item item-a :quantity-accepted 10M :unit-cost 25M}
                {:order-item item-b :quantity-accepted 6M  :unit-cost 30M}]})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-MULTI"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      (let [db (d/db *conn*)]
        (testing "two valuation-layers"
          (is (= 2 (d/q '[:find (count ?l) . :where [?l :kontor.valuation-layer/book _]] db))))
        (testing "Dr Inventory total = 250 + 180 = 430"
          (is (= 0 (.compareTo (bigdec "430.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "1400"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "Cr GR-IR total = -430"
          (is (= 0 (.compareTo (bigdec "-430.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "2150"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))))))

;; ============================================================================
;;: end-to-end purchase posting via post-to-ledger!
;; ============================================================================

(deftest receipt-invoice-billing-junction-written
  (testing ": make-invoice-from-order! writes :receipt-invoice-
            billing for each :accepted receipt referenced by the
            invoice's lines"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-J1"
        :order order-eid
        :items [{:order-item item-eid :quantity-accepted 6M :unit-cost 25M}]
        :received-at #inst "2026-05-02"})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-J1"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-J2"
        :order order-eid
        :items [{:order-item item-eid :quantity-accepted 4M :unit-cost 25M}]
        :received-at #inst "2026-05-05"})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-J2"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      (inv/make-invoice-from-order!
       *conn* "PO-1"
       {:external-id "INV-J" :type :purchase})
      (let [db (d/db *conn*)
            inv-line-eid (d/q '[:find ?l .
                                :in $ ?inv-eid
                                :where [?l :kontor.invoice-line/invoice ?inv-eid]]
                              db (inv/by-external-id db "INV-J"))
            junctions (->> (d/q '[:find [?j ...]
                                  :in $ ?l
                                  :where [?j :kontor.receipt-invoice-billing/invoice-line ?l]]
                                db inv-line-eid)
                           (map #(d/pull db
                                         '[* {:kontor.receipt-invoice-billing/receipt
                                              [:kontor.receipt/external-id]}]
                                         %))
                           (sort-by #(get-in % [:kontor.receipt-invoice-billing/receipt
                                                :kontor.receipt/external-id])))]
        (testing "two junction rows — one per receipt"
          (is (= 2 (count junctions))))
        (testing "FIFO allocation: RCPT-J1 (older) gets 6, RCPT-J2 gets 4"
          (is (= "RCPT-J1"
                 (get-in (first junctions)
                         [:kontor.receipt-invoice-billing/receipt :kontor.receipt/external-id])))
          (is (= 0 (.compareTo (bigdec "6")
                               (:kontor.receipt-invoice-billing/quantity (first junctions)))))
          (is (= "RCPT-J2"
                 (get-in (second junctions)
                         [:kontor.receipt-invoice-billing/receipt :kontor.receipt/external-id])))
          (is (= 0 (.compareTo (bigdec "4")
                               (:kontor.receipt-invoice-billing/quantity (second junctions))))))))))

(deftest e2e-purchase-receipt-invoice-post
  (testing ": full PO → receipt → invoice → posted with GR-IR
            residual at zero"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      ;; 1. Receive goods
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-E2E"
        :order order-eid
        :items [{:order-item item-eid
                 :quantity-accepted 10M
                 :unit-cost 25M}]})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-E2E"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      ;; 2. Generate the supplier invoice
      (inv/make-invoice-from-order!
       *conn* "PO-1"
       {:external-id "INV-E2E" :type :purchase})
      ;; 3. Post the invoice to the GL
      (inv-post/post-to-ledger!
       *conn* "INV-E2E"
       {:journal-ref [:kontor.journal/code "PURCH"]})
      (let [db (d/db *conn*)]
        (testing "invoice status :sent (post completed)"
          (is (= :sent
                 (sm/current-status db
                                    (inv/by-external-id db "INV-E2E")
                                    :kontor.invoice/status))))
        (testing "GR-IR residual = 0 (Cr 250 at receipt + Dr 250 at invoice)"
          (is (= 0 (.compareTo (bigdec "0")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "2150"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "Inventory residual = +250 (Dr at receipt; not re-Dr'd)"
          ;; With the bridge fix, the purchase invoice for :direct
          ;; lines routes to :gr-ir-clearing (not :inventory again),
          ;; so inventory stays at +250 from the receipt.
          (is (= 0 (.compareTo (bigdec "250.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "1400"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "AP credited 250 (the supplier obligation)"
          (is (= 0 (.compareTo (bigdec "-250.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "2000"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "overall ledger balances (sum = 0)"
          (is (= 0 (.compareTo (bigdec "0")
                               (d/q '[:find (sum ?amt) .
                                      :with ?p :where [?p :kontor.posting/amount ?amt]]
                                    db)))))))))

;; ============================================================================
;;: :requires-three-way-match-pass approval rule
;; ============================================================================

(deftest match-pass-policy-blocks-posting-on-exception
  (testing ": policy :requires-three-way-match-pass blocks
            post-to-ledger! when match-status is :exception-qty"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      ;; Receive ONLY 7 of 10 → match will flag :exception-qty
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-EXC"
        :order order-eid
        :items [{:order-item item-eid
                 :quantity-accepted 7M
                 :unit-cost 25M}]})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-EXC"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      ;; Generate the supplier invoice for FULL 10
      (inv/make-invoice-from-order!
       *conn* "PO-1"
       {:external-id "INV-EXC" :type :purchase})
      ;; Compute match → should be :exception-qty
      (match/recompute-match-status!
       *conn* (inv/by-external-id (d/db *conn*) "INV-EXC"))
      ;; Seed the policy for :kontor.invoice/status :draft → :sent
      (d/transact *conn*
                  [{:kontor.approval-policy/entity-type :invoice
                    :kontor.approval-policy/facet :kontor.invoice/status
                    :kontor.approval-policy/transition-from :draft
                    :kontor.approval-policy/transition-to :sent
                    :kontor.approval-policy/rule :requires-three-way-match-pass
                    :kontor.approval-policy/active true}])
      ;; Posting should now throw
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Approval-policy violation"
           (inv-post/post-to-ledger!
            *conn* "INV-EXC"
            {:journal-ref [:kontor.journal/code "PURCH"]}))))))

(deftest match-pass-policy-allows-posting-on-auto-matched
  (testing ": policy allows posting once match-status is :auto-matched"
    (seed!)
    (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
      (receipt/make-receipt!
       *conn*
       {:external-id "RCPT-OK"
        :order order-eid
        :items [{:order-item item-eid :quantity-accepted 10M :unit-cost 25M}]})
      (receipt/post-receipt-with-inventory!
       *conn* "RCPT-OK"
       {:provider (costing/make-fifo-provider)
        :journal-ref [:kontor.journal/code "PURCH"]})
      (inv/make-invoice-from-order!
       *conn* "PO-1"
       {:external-id "INV-OK" :type :purchase})
      (match/recompute-match-status!
       *conn* (inv/by-external-id (d/db *conn*) "INV-OK"))
      (d/transact *conn*
                  [{:kontor.approval-policy/entity-type :invoice
                    :kontor.approval-policy/facet :kontor.invoice/status
                    :kontor.approval-policy/transition-from :draft
                    :kontor.approval-policy/transition-to :sent
                    :kontor.approval-policy/rule :requires-three-way-match-pass
                    :kontor.approval-policy/active true}])
      ;; Should NOT throw
      (inv-post/post-to-ledger!
       *conn* "INV-OK"
       {:journal-ref [:kontor.journal/code "PURCH"]})
      (is (= :sent
             (sm/current-status (d/db *conn*)
                                (inv/by-external-id (d/db *conn*) "INV-OK")
                                :kontor.invoice/status))))))

(deftest match-pass-policy-passthrough-for-sales-invoice
  (testing ": sales invoice has nil :kontor.invoice/match-status; the
            policy passes through (sales invoices have no match
            concept)"
    (seed!)
    ;; Create a simple sales order
    (d/transact *conn*
                [{:kontor.order/external-id "SO-1"
                  :kontor.order/type :sales
                  :kontor.order/status :order.status/created
                  :kontor.order/order-date #inst "2026-05-01"
                  :kontor.order/entry-date #inst "2026-05-01"
                  :kontor.order/currency [:kontor.commodity/symbol "EUR"]
                  :kontor.order/bill-from-partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order/bill-to-partner [:kontor.partner/external-id "CUSTOMER"]
                  :kontor.order/entity [:kontor.entity/code "ACME"]}
                 {:kontor.sales.order-item/order [:kontor.order/external-id "SO-1"]
                  :kontor.sales.order-item/seq-id "00001"
                  :kontor.sales.order-item/type :product
                  :kontor.sales.order-item/product-id "WIDGET-X"
                  :kontor.sales.order-item/quantity 1M
                  :kontor.sales.order-item/unit-price 100M
                  :kontor.sales.order-item/cancel-quantity 0M
                  :kontor.sales.order-item/status :order-item.status/approved}])
    (inv/make-invoice-from-order!
     *conn* "SO-1"
     {:external-id "INV-SALES" :type :sales})
    (d/transact *conn*
                [{:kontor.approval-policy/entity-type :invoice
                  :kontor.approval-policy/facet :kontor.invoice/status
                  :kontor.approval-policy/transition-from :draft
                  :kontor.approval-policy/transition-to :sent
                  :kontor.approval-policy/rule :requires-three-way-match-pass
                  :kontor.approval-policy/active true}])
    ;; Sales invoice has no match-status — policy must passthrough
    (inv-post/post-to-ledger!
     *conn* "INV-SALES"
     {:journal-ref [:kontor.journal/code "SALES"]})
    (is (= :sent
           (sm/current-status (d/db *conn*)
                              (inv/by-external-id (d/db *conn*) "INV-SALES")
                              :kontor.invoice/status)))))

;; ============================================================================
;;: credit-memo / debit-memo GL polarity
;; ============================================================================

(deftest credit-memo-posting-polarity-reverses-sale
  (testing ": posting a :credit-memo Dr revenue / Cr AR (reverses
            the original sale Cr revenue / Dr AR)"
    (seed!)
    (d/transact *conn*
                [{:kontor.order/external-id "SO-RET"
                  :kontor.order/type :sales
                  :kontor.order/status :order.status/created
                  :kontor.order/order-date #inst "2026-05-01"
                  :kontor.order/entry-date #inst "2026-05-01"
                  :kontor.order/currency [:kontor.commodity/symbol "EUR"]
                  :kontor.order/bill-from-partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order/bill-to-partner [:kontor.partner/external-id "CUSTOMER"]
                  :kontor.order/entity [:kontor.entity/code "ACME"]}
                 {:kontor.sales.order-item/order [:kontor.order/external-id "SO-RET"]
                  :kontor.sales.order-item/seq-id "00001"
                  :kontor.sales.order-item/type :product
                  :kontor.sales.order-item/product-id "WIDGET-A"
                  :kontor.sales.order-item/quantity 3M
                  :kontor.sales.order-item/unit-price 25M
                  :kontor.sales.order-item/cancel-quantity 0M
                  :kontor.sales.order-item/status :order-item.status/approved}])
    (let [db (d/db *conn*)
          order-eid (d/q '[:find ?e . :where [?e :kontor.order/external-id "SO-RET"]] db)
          item-eid (d/q '[:find ?i . :in $ ?o
                          :where [?i :kontor.sales.order-item/order ?o]] db order-eid)
          customer (d/q '[:find ?p . :where [?p :kontor.partner/external-id "CUSTOMER"]] db)
          acme (d/q '[:find ?p . :where [?p :kontor.partner/external-id "BUYER"]] db)]
      (returns/make-return! *conn*
                            {:external-id "RMA-POL-1"
                             :type :customer
                             :from-party customer
                             :to-party acme
                             :order order-eid
                             :items [{:order-item item-eid
                                      :product-id "WIDGET-A"
                                      :return-quantity 3M
                                      :return-price 25M}]})
      (returns/accept-return! *conn* "RMA-POL-1")
      (returns/receive-return! *conn* "RMA-POL-1")
      (returns/make-credit-memo-from-return! *conn* "RMA-POL-1"
                                              {:external-id "CM-POL-1"})
      (inv-post/post-to-ledger!
       *conn* "CM-POL-1"
       {:journal-ref [:kontor.journal/code "SALES"]})
      (let [db (d/db *conn*)]
        (testing "Dr Sales Revenue +75 (reverses the original +75 credit)"
          (is (= 0 (.compareTo (bigdec "75.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "4000"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "Cr AR -75 (reverses the original +75 debit)"
          (is (= 0 (.compareTo (bigdec "-75.00")
                               (d/q '[:find (sum ?amt) . :with ?p
                                      :where
                                      [?a :kontor.account/path "1200"]
                                      [?p :kontor.posting/account ?a]
                                      [?p :kontor.posting/amount ?amt]]
                                    db)))))
        (testing "ledger balances"
          (is (= 0 (.compareTo (bigdec "0")
                               (d/q '[:find (sum ?amt) .
                                      :with ?p :where [?p :kontor.posting/amount ?amt]]
                                    db)))))))))
