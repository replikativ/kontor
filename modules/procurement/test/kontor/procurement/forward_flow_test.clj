(ns kontor.procurement.forward-flow-test
  "End-to-end + per-namespace tests for the forward procurement
   flow — ADR-042 commit 2/4. Covers: requirement lifecycle, receipt
   creation + status, service acceptance, 3-way match (with and
   without tolerance), polymorphic bridge dispatch on :order/type
   :purchase + :order-item/category."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.procurement.acceptance :as acc]
            [kontor.procurement.match :as match]
            [kontor.procurement.receipt :as receipt]
            [kontor.procurement.requirement :as req]
            [kontor.procurement.schema :as proc-schema]
            [kontor.sales.schema :as sales-schema]
            [kontor.status-machine :as sm]))

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
;; Setup helpers
;; ============================================================================

(defn- seed-base! []
  (d/transact *conn*
              [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:kontor.entity/code "ACME" :kontor.entity/name "Acme Inc"
                :kontor.entity/kind :operating :kontor.entity/active true}
               {:kontor.partner/external-id "BUYER" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Acme Inc"}
               {:kontor.partner/external-id "SUPPLIER" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Supplier Co"}]))

(defn- seed-accounts! []
  (d/transact *conn*
              [{:kontor.account/code "1400" :kontor.account/name "Inventory"
                :kontor.account/path "1400" :kontor.account/type :asset}
               {:kontor.account/code "5000" :kontor.account/name "Purchase Expense"
                :kontor.account/path "5000" :kontor.account/type :expense}
               {:kontor.account/code "2000" :kontor.account/name "Accounts Payable"
                :kontor.account/path "2000" :kontor.account/type :liability}
               {:kontor.account/code "2150" :kontor.account/name "GR/IR Clearing"
                :kontor.account/path "2150" :kontor.account/type :liability}
               {:kontor.account/code "1370" :kontor.account/name "VAT Recoverable"
                :kontor.account/path "1370" :kontor.account/type :asset}]))

(defn- seed-gl-defaults! []
  (d/transact *conn*
              [{:gl-account-default/account-type :inventory
                :gl-account-default/account [:kontor.account/path "1400"]}
               {:gl-account-default/account-type :purchase-expense
                :gl-account-default/account [:kontor.account/path "5000"]}
               {:gl-account-default/account-type :ap
                :gl-account-default/account [:kontor.account/path "2000"]}
               {:gl-account-default/account-type :gr-ir-clearing
                :gl-account-default/account [:kontor.account/path "2150"]}
               {:gl-account-default/account-type :purchase-tax-recoverable
                :gl-account-default/account [:kontor.account/path "1370"]}]))

(defn- seed-journal! []
  (d/transact *conn*
              [{:kontor.journal/code "PURCH" :kontor.journal/name "Purchase Journal"
                :kontor.journal/type :purchase}]))

(defn- create-purchase-order!
  "Seed a :purchase order with one item. Returns the order-eid +
   item-eid."
  [{:keys [external-id qty unit-price category requires-receipt?]
    :or {external-id "PO-1" qty 10M unit-price 25M
         category :direct requires-receipt? true}}]
  (d/transact *conn*
              [{:order/external-id external-id
                :order/type :purchase
                :order/status :order.status/created
                :order/order-date #inst "2026-05-01"
                :order/entry-date #inst "2026-05-01"
                :order/currency [:kontor.commodity/symbol "EUR"]
                :order/bill-from-partner [:kontor.partner/external-id "SUPPLIER"]
                :order/bill-to-partner [:kontor.partner/external-id "BUYER"]
                :order/entity [:kontor.entity/code "ACME"]}
               {:order-item/order [:order/external-id external-id]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id "WIDGET-A"
                :order-item/quantity qty
                :order-item/unit-price unit-price
                :order-item/cancel-quantity 0M
                :order-item/status :order-item.status/approved
                :order-item/category category
                :order-item/requires-receipt? requires-receipt?}])
  (let [db (d/db *conn*)
        order-eid (d/q '[:find ?e . :in $ ?xid
                         :where [?e :order/external-id ?xid]]
                       db external-id)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :order-item/order ?o]]
                      db order-eid)]
    {:order-eid order-eid :item-eid item-eid}))

;; ============================================================================
;; Requirement lifecycle
;; ============================================================================

(deftest requirement-lifecycle-happy-path
  (seed-base!)
  (req/make-requirement! *conn*
                         {:external-id "REQ-1"
                          :product-id "WIDGET-A"
                          :quantity 50M
                          :facility-id "WAREHOUSE-DE-1"
                          :required-by-date #inst "2026-06-01"
                          :justification "stock replenishment"})
  (let [db (d/db *conn*)
        req-eid (req/by-external-id db "REQ-1")]
    (testing "requirement created in :proposed"
      (is (= :proposed (sm/current-status db req-eid :requirement/status))))
    (req/approve-requirement! *conn* "REQ-1" {:reason :approved})
    (testing "approved transitions to :approved"
      (is (= :approved (sm/current-status (d/db *conn*) req-eid :requirement/status))))))

(deftest requirement-rejection
  (seed-base!)
  (req/make-requirement! *conn*
                         {:external-id "REQ-REJ"
                          :product-id "WIDGET-B"
                          :quantity 10M
                          :facility-id "WAREHOUSE-DE-1"})
  (req/reject-requirement! *conn* "REQ-REJ" {:reason :rejected
                                              :reason-note "supplier unavailable"})
  (is (= :rejected (sm/current-status (d/db *conn*) (req/by-external-id (d/db *conn*) "REQ-REJ")
                                       :requirement/status))))

(deftest requirement-commit-to-po
  (seed-base!)
  (let [{:keys [item-eid]} (create-purchase-order! {})]
    (req/make-requirement! *conn*
                           {:external-id "REQ-COMMIT"
                            :product-id "WIDGET-A"
                            :quantity 10M
                            :facility-id "WAREHOUSE-DE-1"})
    (req/approve-requirement! *conn* "REQ-COMMIT" {:reason :approved})
    (req/commit-to-po! *conn*
                       {:requirement "REQ-COMMIT"
                        :order-item item-eid
                        :quantity 10M})
    (let [db (d/db *conn*)
          req-eid (req/by-external-id db "REQ-COMMIT")
          commitments (req/commitments-of db req-eid)]
      (testing "status advances to :ordered"
        (is (= :ordered (sm/current-status db req-eid :requirement/status))))
      (testing "commitment row created"
        (is (= 1 (count commitments)))
        (is (= 10M (-> commitments first :requirement-commitment/quantity)))))))

;; ============================================================================
;; Receipt lifecycle
;; ============================================================================

(deftest receipt-creation-and-inspection
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
    (receipt/make-receipt! *conn*
                           {:external-id "RCPT-1"
                            :order order-eid
                            :received-at #inst "2026-05-08"
                            :facility-id "WAREHOUSE-DE-1"
                            :items [{:order-item item-eid
                                     :product-id "WIDGET-A"
                                     :quantity-accepted 8M
                                     :quantity-rejected 2M
                                     :rejection-reason :damaged}]})
    (let [db (d/db *conn*)
          receipt-eid (receipt/by-external-id db "RCPT-1")]
      (testing "receipt created in :pending"
        (is (= :pending (sm/current-status db receipt-eid :receipt/status))))
      (testing "receipt items captured with split quantities"
        (let [items (receipt/items-of db receipt-eid)]
          (is (= 1 (count items)))
          (is (= 8M (-> items first :receipt-item/quantity-accepted)))
          (is (= 2M (-> items first :receipt-item/quantity-rejected)))
          (is (= :damaged (-> items first :receipt-item/rejection-reason)))))
      (receipt/accept-receipt! *conn* "RCPT-1" {:reason :approved})
      (testing "transition to :accepted"
        (is (= :accepted (sm/current-status (d/db *conn*) receipt-eid :receipt/status))))
      (testing "qty-received query rolls up across receipts"
        (is (= 8M (receipt/quantity-received-of-order-item (d/db *conn*) item-eid)))
        (is (= 2M (receipt/quantity-rejected-of-order-item (d/db *conn*) item-eid)))))))

;; ============================================================================
;; Service acceptance
;; ============================================================================

(deftest service-acceptance-for-non-physical-line
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order!
                                       {:external-id "PO-SVC"
                                        :category :services
                                        :requires-receipt? false
                                        :qty 40M
                                        :unit-price 150M})]
    (acc/make-acceptance! *conn*
                          {:external-id "ACC-1"
                           :order order-eid
                           :order-item item-eid
                           :quantity-accepted 40M
                           :accepted-at #inst "2026-05-15"
                           :notes "consulting hours delivered"})
    (testing "acceptance recorded"
      (let [accs (acc/acceptances-of-order (d/db *conn*) order-eid)]
        (is (= 1 (count accs)))
        (is (= 40M (-> accs first :service-acceptance/quantity-accepted)))))
    (testing "qty-accepted query for service line"
      (is (= 40M (acc/quantity-accepted-of-order-item (d/db *conn*) item-eid))))))

;; ============================================================================
;; Bridge polymorphism on :order/type :purchase
;; ============================================================================

(deftest bridge-routes-purchase-direct-to-gr-ir-clearing
  (seed-base!)
  (seed-accounts!)
  (seed-gl-defaults!)
  (seed-journal!)
  (create-purchase-order! {})    ; :category :direct
  (inv/make-invoice-from-order! *conn* "PO-1"
                                {:external-id "INV-PURCH-DIRECT"})
  (let [db (d/db *conn*)
        inv (inv/pull-invoice db "INV-PURCH-DIRECT")
        lines (inv/lines-of db "INV-PURCH-DIRECT")]
    (testing ":invoice/type defaults to :purchase when order is :purchase"
      (is (= :purchase (:invoice/type inv))))
    (testing "direct purchase line clears GR-IR (receipt Dr'd inventory)"
      ;; Per ADR-042 receipt-first flow: inventory was debited at
      ;; receipt time via post-receipt-with-inventory!; the invoice
      ;; clears the GR-IR credit.
      (is (= :gr-ir-clearing (-> lines first :invoice-line/gl-account-type))))))

(deftest bridge-routes-purchase-indirect-to-expense
  (seed-base!)
  (seed-accounts!)
  (seed-gl-defaults!)
  (seed-journal!)
  (create-purchase-order! {:external-id "PO-INDIRECT"
                            :category :indirect})
  (inv/make-invoice-from-order! *conn* "PO-INDIRECT"
                                {:external-id "INV-PURCH-INDIRECT"})
  (let [lines (inv/lines-of (d/db *conn*) "INV-PURCH-INDIRECT")]
    (is (= :purchase-expense (-> lines first :invoice-line/gl-account-type)))))

(deftest bridge-routes-purchase-asset-to-asset-acquisition
  (seed-base!)
  (seed-accounts!)
  (seed-gl-defaults!)
  (seed-journal!)
  (create-purchase-order! {:external-id "PO-ASSET"
                            :category :asset})
  (inv/make-invoice-from-order! *conn* "PO-ASSET"
                                {:external-id "INV-PURCH-ASSET"})
  (let [lines (inv/lines-of (d/db *conn*) "INV-PURCH-ASSET")]
    (is (= :asset-acquisition (-> lines first :invoice-line/gl-account-type)))))

(deftest bridge-still-routes-sales-to-revenue
  ;; Regression: existing :sales orders must still route to :sales-revenue
  (seed-base!)
  (seed-accounts!)
  (d/transact *conn*
              [{:order/external-id "SO-REGR"
                :order/type :sales
                :order/status :order.status/created
                :order/order-date #inst "2026-05-01"
                :order/entry-date #inst "2026-05-01"
                :order/currency [:kontor.commodity/symbol "EUR"]
                :order/bill-from-partner [:kontor.partner/external-id "BUYER"]
                :order/bill-to-partner [:kontor.partner/external-id "SUPPLIER"]}
               {:order-item/order [:order/external-id "SO-REGR"]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id "WIDGET-B"
                :order-item/quantity 5M
                :order-item/unit-price 100M
                :order-item/cancel-quantity 0M
                :order-item/status :order-item.status/approved}])
  (inv/make-invoice-from-order! *conn* "SO-REGR"
                                {:external-id "INV-SALES-REGR"})
  (let [lines (inv/lines-of (d/db *conn*) "INV-SALES-REGR")]
    (testing "sales order still routes to :sales-revenue (no regression)"
      (is (= :sales-revenue (-> lines first :invoice-line/gl-account-type))))))

;; ============================================================================
;; 3-way match
;; ============================================================================

(deftest three-way-match-clean
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
    (receipt/make-receipt! *conn*
                           {:external-id "RCPT-MATCH"
                            :order order-eid
                            :items [{:order-item item-eid
                                     :product-id "WIDGET-A"
                                     :quantity-accepted 10M}]})
    (receipt/accept-receipt! *conn* "RCPT-MATCH")
    (inv/make-invoice-from-order! *conn* "PO-1"
                                  {:external-id "INV-MATCH"})
    (let [db (d/db *conn*)
          report (match/three-way-report db (inv/by-external-id db "INV-MATCH"))]
      (testing "clean match"
        (is (= 1 (count report)))
        (is (= :match (-> report first :verdict)))))))

(deftest three-way-match-exception-qty
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
    ;; Only 7 received but invoice for 10 → qty exception
    (receipt/make-receipt! *conn*
                           {:external-id "RCPT-PARTIAL"
                            :order order-eid
                            :items [{:order-item item-eid
                                     :quantity-accepted 7M}]})
    (receipt/accept-receipt! *conn* "RCPT-PARTIAL")
    (inv/make-invoice-from-order! *conn* "PO-1"
                                  {:external-id "INV-EXCEPT-QTY"})
    (let [report (match/three-way-report (d/db *conn*)
                                          (inv/by-external-id (d/db *conn*) "INV-EXCEPT-QTY"))]
      (is (= :exception-qty (-> report first :verdict))))))

(deftest three-way-match-with-tolerance-within
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})
        db (d/db *conn*)
        entity-eid (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME"]] db)
        supplier-eid (d/q '[:find ?p . :where [?p :kontor.partner/external-id "SUPPLIER"]] db)]
    ;; Seed a 10% over-receipt tolerance
    (d/transact *conn*
                [{:match-tolerance/entity entity-eid
                  :match-tolerance/supplier supplier-eid
                  :match-tolerance/qty-pct-over 0.10M
                  :match-tolerance/qty-abs-over 0M
                  :match-tolerance/price-pct-over 0M
                  :match-tolerance/price-abs-over 0M
                  :match-tolerance/active true}])
    ;; 11 received vs 10 ordered → 10% over → within tolerance
    (receipt/make-receipt! *conn*
                           {:external-id "RCPT-OVER"
                            :order order-eid
                            :items [{:order-item item-eid
                                     :quantity-accepted 11M}]})
    (receipt/accept-receipt! *conn* "RCPT-OVER")
    (inv/make-invoice-from-order! *conn* "PO-1"
                                  {:external-id "INV-TOL"})
    ;; The invoice is for 10 (ordered qty); received is 11 → delta is -1
    ;; relative to received-as-base; within 10% tolerance.
    (let [report (match/three-way-report (d/db *conn*)
                                          (inv/by-external-id (d/db *conn*) "INV-TOL"))
          verdict (-> report first :verdict)]
      (testing "verdict is :match or :within-tolerance (not exception)"
        (is (contains? #{:match :within-tolerance} verdict))))))

(deftest three-way-match-missing-receipt
  (seed-base!)
  ;; No receipt at all; PO is approved; invoice arrives
  (create-purchase-order! {})
  (inv/make-invoice-from-order! *conn* "PO-1"
                                {:external-id "INV-NO-RCPT"})
  (let [report (match/three-way-report (d/db *conn*)
                                        (inv/by-external-id (d/db *conn*) "INV-NO-RCPT"))]
    (is (= :exception-missing-receipt (-> report first :verdict)))))

(deftest recompute-match-status-writes-facet
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})]
    (receipt/make-receipt! *conn*
                           {:external-id "RCPT-RECOMP"
                            :order order-eid
                            :items [{:order-item item-eid
                                     :quantity-accepted 10M}]})
    (receipt/accept-receipt! *conn* "RCPT-RECOMP")
    (inv/make-invoice-from-order! *conn* "PO-1"
                                  {:external-id "INV-RECOMP"})
    (let [verdict (match/recompute-match-status! *conn*
                                                  (inv/by-external-id (d/db *conn*) "INV-RECOMP"))]
      (testing "recompute writes the verdict to :invoice/match-status"
        (is (= :auto-matched verdict))
        (is (= :auto-matched
               (match/match-status-of-invoice (d/db *conn*)
                                               (inv/by-external-id (d/db *conn*) "INV-RECOMP"))))))))
