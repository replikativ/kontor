(ns kontor.procurement.schema-test
  "Schema + seed presence tests for kontor-procurement — ADR-042
   implementation commit #1."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
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
;; Schema attrs present
;; ============================================================================

(deftest all-procurement-attrs-installed
  (let [db (d/db *conn*)
        idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
    (testing "extensions to :order-item"
      (doseq [a [:kontor.procurement.order-item/requires-receipt? :kontor.procurement.order-item/category]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "extensions to :invoice"
      (is (contains? idents :kontor.invoice/match-status)))
    (testing "requirement attrs"
      (doseq [a [:kontor.requirement/external-id :kontor.requirement/type :kontor.requirement/status
                 :kontor.requirement/product-id :kontor.requirement/quantity :kontor.requirement/uom
                 :kontor.requirement/facility-id :kontor.requirement/facility-to-id
                 :kontor.requirement/required-by-date :kontor.requirement/start-date
                 :kontor.requirement/estimated-budget :kontor.requirement/budget-commodity
                 :kontor.requirement/entity :kontor.requirement/cost-center
                 :kontor.requirement/justification :kontor.requirement/description
                 :kontor.requirement/created-at :kontor.requirement/created-by-uid]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "requirement-commitment junction"
      (doseq [a [:kontor.requirement-commitment/requirement
                 :kontor.requirement-commitment/order-item
                 :kontor.requirement-commitment/quantity
                 :kontor.requirement-commitment/committed-at
                 :kontor.requirement-commitment/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt attrs"
      (doseq [a [:kontor.receipt/external-id :kontor.receipt/order :kontor.receipt/ship-group
                 :kontor.receipt/status :kontor.receipt/received-at :kontor.receipt/received-by-uid
                 :kontor.receipt/packing-slip-ref :kontor.receipt/facility-id
                 :kontor.receipt/carrier-partner :kontor.receipt/tracking-number]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt-item attrs"
      (doseq [a [:kontor.receipt-item/receipt :kontor.receipt-item/order-item
                 :kontor.receipt-item/quantity-accepted :kontor.receipt-item/quantity-rejected
                 :kontor.receipt-item/rejection-reason :kontor.receipt-item/lot
                 :kontor.receipt-item/unit-cost :kontor.receipt-item/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt-invoice-billing junction"
      (doseq [a [:kontor.receipt-invoice-billing/receipt
                 :kontor.receipt-invoice-billing/invoice-line
                 :kontor.receipt-invoice-billing/quantity
                 :kontor.receipt-invoice-billing/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "service-acceptance attrs"
      (doseq [a [:kontor.service-acceptance/external-id :kontor.service-acceptance/order
                 :kontor.service-acceptance/order-item
                 :kontor.service-acceptance/quantity-accepted
                 :kontor.service-acceptance/accepted-at
                 :kontor.service-acceptance/accepted-by-uid
                 :kontor.service-acceptance/acceptance-evidence
                 :kontor.service-acceptance/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "order-item-assoc attrs"
      (doseq [a [:kontor.procurement.order-item-assoc/from-order-item
                 :kontor.procurement.order-item-assoc/to-order-item
                 :kontor.procurement.order-item-assoc/type
                 :kontor.procurement.order-item-assoc/quantity
                 :kontor.procurement.order-item-assoc/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "match-tolerance attrs"
      (doseq [a [:kontor.match-tolerance/entity :kontor.match-tolerance/supplier
                 :kontor.match-tolerance/product-id :kontor.match-tolerance/qty-pct-over
                 :kontor.match-tolerance/qty-abs-over :kontor.match-tolerance/price-pct-over
                 :kontor.match-tolerance/price-abs-over :kontor.match-tolerance/active
                 :kontor.match-tolerance/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return attrs"
      (doseq [a [:kontor.return/external-id :kontor.return/type :kontor.return/status
                 :kontor.return/from-party :kontor.return/to-party :kontor.return/order
                 :kontor.return/entity :kontor.return/destination-facility-id
                 :kontor.return/supplier-rma :kontor.return/entry-date :kontor.return/supporting-doc]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return-item attrs"
      (doseq [a [:kontor.return-item/return :kontor.return-item/order-item
                 :kontor.return-item/seq-id :kontor.return-item/return-quantity
                 :kontor.return-item/received-quantity :kontor.return-item/return-price
                 :kontor.return-item/reason :kontor.return-item/return-type
                 :kontor.return-item/expected-disposition :kontor.return-item/status
                 :kontor.return-item/response :kontor.return-item/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return-response + return-item-billing"
      (doseq [a [:kontor.return-response/return-item :kontor.return-response/type
                 :kontor.return-response/replacement-order :kontor.return-response/credit-memo
                 :kontor.return-response/amount :kontor.return-response/created-at
                 :kontor.return-item-billing/return-item
                 :kontor.return-item-billing/invoice-line
                 :kontor.return-item-billing/quantity :kontor.return-item-billing/identity]]
        (is (contains? idents a) (str "missing: " a))))))

;; ============================================================================
;; State machine seeds
;; ============================================================================

(deftest requirement-status-transitions-seeded
  (let [db (d/db *conn*)]
    (testing "happy path"
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :nil :proposed)))
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :proposed :approved)))
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :approved :ordered)))
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :ordered :received))))
    (testing "rejection paths"
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :proposed :rejected)))
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :proposed :cancelled)))
      (is (true? (sm/legal-transition? db :requirement :kontor.requirement/status
                                       :approved :cancelled))))
    (testing "non-seeded transitions are illegal"
      (is (false? (sm/legal-transition? db :requirement :kontor.requirement/status
                                        :nil :received))
          "cannot skip to received")
      (is (false? (sm/legal-transition? db :requirement :kontor.requirement/status
                                        :received :proposed))
          "received is terminal-forward"))))

(deftest receipt-status-transitions-seeded
  (let [db (d/db *conn*)]
    (is (true? (sm/legal-transition? db :receipt :kontor.receipt/status
                                     :nil :pending)))
    (is (true? (sm/legal-transition? db :receipt :kontor.receipt/status
                                     :pending :accepted)))
    (is (true? (sm/legal-transition? db :receipt :kontor.receipt/status
                                     :pending :rejected)))
    (is (true? (sm/legal-transition? db :receipt :kontor.receipt/status
                                     :accepted :rejected))
        "post-inspection reject is legal (quality issue found later)")))

(deftest match-status-transitions-seeded
  (let [db (d/db *conn*)]
    (testing "exception flagging"
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :nil :auto-matched)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :nil :exception-price)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :nil :exception-qty)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :nil :exception-missing-receipt))))
    (testing "override paths"
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :exception-price :manual-approved)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :exception-qty :manual-approved))))
    (testing "dispute paths"
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :exception-price :disputed)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :disputed :manual-approved))))
    (testing "clearing"
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :auto-matched :cleared)))
      (is (true? (sm/legal-transition? db :invoice :kontor.invoice/match-status
                                       :manual-approved :cleared))))))

(deftest return-status-transitions-seeded
  (let [db (d/db *conn*)]
    (is (true? (sm/legal-transition? db :return :kontor.return/status
                                     :nil :requested)))
    (is (true? (sm/legal-transition? db :return :kontor.return/status
                                     :requested :accepted)))
    (is (true? (sm/legal-transition? db :return :kontor.return/status
                                     :requested :rejected)))
    (is (true? (sm/legal-transition? db :return :kontor.return/status
                                     :accepted :received)))
    (is (true? (sm/legal-transition? db :return :kontor.return/status
                                     :received :completed)))
    (is (false? (sm/legal-transition? db :return :kontor.return/status
                                      :completed :requested))
        "completed is terminal")))

;; ============================================================================
;; Account-type-direction seeds
;; ============================================================================

(deftest procurement-account-type-direction-seeded
  (let [db (d/db *conn*)
        lookup (fn [it at]
                 (d/q '[:find ?dir .
                        :in $ ?it ?at
                        :where
                        [?r :kontor.account-type-direction/invoice-type ?it]
                        [?r :kontor.account-type-direction/account-type ?at]
                        [?r :kontor.account-type-direction/direction ?dir]
                        [?r :kontor.account-type-direction/active true]]
                      db it at))]
    (testing "GR/IR clearing debits on a :purchase invoice line — the
              invoice clears the receipt's credit at the same amount,
              netting to zero on the GR/IR account per (PO-line,
              commodity)"
      (is (= :debit (lookup :purchase :gr-ir-clearing))))
    (testing "PPV / FX-variance / landed-cost / receive-reject post debit on purchase"
      (is (= :debit (lookup :purchase :price-variance)))
      (is (= :debit (lookup :purchase :exchange-variance)))
      (is (= :debit (lookup :purchase :landed-cost-variance)))
      (is (= :debit (lookup :purchase :receive-reject-loss))))
    (testing "Prepaid expense + asset acquisition post debit on purchase"
      (is (= :debit (lookup :purchase :prepaid-expense)))
      (is (= :debit (lookup :purchase :asset-acquisition))))))

;; ============================================================================
;; Seed counts (sanity)
;; ============================================================================

(deftest expected-seed-counts
  (let [db (d/db *conn*)]
    (is (= 8 (d/q '[:find (count ?t) .
                    :where [?t :kontor.status-transition/entity-type :requirement]]
                  db))
        "requirement seeds: nil→proposed, proposed→approved, proposed→rejected, proposed→cancelled, approved→ordered, approved→cancelled, approved→proposed, ordered→received")
    (is (= 4 (d/q '[:find (count ?t) .
                    :where [?t :kontor.status-transition/entity-type :receipt]]
                  db))
        "receipt seeds: nil→pending, pending→accepted, pending→rejected, accepted→rejected")
    (is (= 7 (d/q '[:find (count ?t) .
                    :where [?t :kontor.status-transition/entity-type :return]]
                  db))
        "return seeds: nil→requested, requested→accepted, requested→rejected, requested→cancelled, accepted→received, accepted→cancelled, received→completed")))

;; NOTE: install! is NOT currently idempotent for :status-transition
;; seeds because composite-tuple identity with nil-in-tuple
;; (:applies-to-org absent) doesn't upsert as expected in datahike.
;; Same issue exists in modules/sales/ and modules/invoice/ installs
;; — none are tested for re-install. Real-world usage is one install
;; per DB. Tracked as a Stage K-followup item.
