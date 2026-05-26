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
      (doseq [a [:order-item/requires-receipt? :order-item/category]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "extensions to :invoice"
      (is (contains? idents :kontor.invoice/match-status)))
    (testing "requirement attrs"
      (doseq [a [:requirement/external-id :requirement/type :requirement/status
                 :requirement/product-id :requirement/quantity :requirement/uom
                 :requirement/facility-id :requirement/facility-to-id
                 :requirement/required-by-date :requirement/start-date
                 :requirement/estimated-budget :requirement/budget-commodity
                 :requirement/entity :requirement/cost-center
                 :requirement/justification :requirement/description
                 :requirement/created-at :requirement/created-by-uid]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "requirement-commitment junction"
      (doseq [a [:requirement-commitment/requirement
                 :requirement-commitment/order-item
                 :requirement-commitment/quantity
                 :requirement-commitment/committed-at
                 :requirement-commitment/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt attrs"
      (doseq [a [:receipt/external-id :receipt/order :receipt/ship-group
                 :receipt/status :receipt/received-at :receipt/received-by-uid
                 :receipt/packing-slip-ref :receipt/facility-id
                 :receipt/carrier-partner :receipt/tracking-number]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt-item attrs"
      (doseq [a [:receipt-item/receipt :receipt-item/order-item
                 :receipt-item/quantity-accepted :receipt-item/quantity-rejected
                 :receipt-item/rejection-reason :receipt-item/lot
                 :receipt-item/unit-cost :receipt-item/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "receipt-invoice-billing junction"
      (doseq [a [:receipt-invoice-billing/receipt
                 :receipt-invoice-billing/invoice-line
                 :receipt-invoice-billing/quantity
                 :receipt-invoice-billing/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "service-acceptance attrs"
      (doseq [a [:service-acceptance/external-id :service-acceptance/order
                 :service-acceptance/order-item
                 :service-acceptance/quantity-accepted
                 :service-acceptance/accepted-at
                 :service-acceptance/accepted-by-uid
                 :service-acceptance/acceptance-evidence
                 :service-acceptance/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "order-item-assoc attrs"
      (doseq [a [:order-item-assoc/from-order-item
                 :order-item-assoc/to-order-item
                 :order-item-assoc/type
                 :order-item-assoc/quantity
                 :order-item-assoc/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "match-tolerance attrs"
      (doseq [a [:match-tolerance/entity :match-tolerance/supplier
                 :match-tolerance/product-id :match-tolerance/qty-pct-over
                 :match-tolerance/qty-abs-over :match-tolerance/price-pct-over
                 :match-tolerance/price-abs-over :match-tolerance/active
                 :match-tolerance/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return attrs"
      (doseq [a [:return/external-id :return/type :return/status
                 :return/from-party :return/to-party :return/order
                 :return/entity :return/destination-facility-id
                 :return/supplier-rma :return/entry-date :return/supporting-doc]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return-item attrs"
      (doseq [a [:return-item/return :return-item/order-item
                 :return-item/seq-id :return-item/return-quantity
                 :return-item/received-quantity :return-item/return-price
                 :return-item/reason :return-item/return-type
                 :return-item/expected-disposition :return-item/status
                 :return-item/response :return-item/identity]]
        (is (contains? idents a) (str "missing: " a))))
    (testing "return-response + return-item-billing"
      (doseq [a [:return-response/return-item :return-response/type
                 :return-response/replacement-order :return-response/credit-memo
                 :return-response/amount :return-response/created-at
                 :return-item-billing/return-item
                 :return-item-billing/invoice-line
                 :return-item-billing/quantity :return-item-billing/identity]]
        (is (contains? idents a) (str "missing: " a))))))

;; ============================================================================
;; State machine seeds
;; ============================================================================

(deftest requirement-status-transitions-seeded
  (let [db (d/db *conn*)]
    (testing "happy path"
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :nil :proposed)))
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :proposed :approved)))
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :approved :ordered)))
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :ordered :received))))
    (testing "rejection paths"
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :proposed :rejected)))
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :proposed :cancelled)))
      (is (true? (sm/legal-transition? db :requirement :requirement/status
                                       :approved :cancelled))))
    (testing "non-seeded transitions are illegal"
      (is (false? (sm/legal-transition? db :requirement :requirement/status
                                        :nil :received))
          "cannot skip to received")
      (is (false? (sm/legal-transition? db :requirement :requirement/status
                                        :received :proposed))
          "received is terminal-forward"))))

(deftest receipt-status-transitions-seeded
  (let [db (d/db *conn*)]
    (is (true? (sm/legal-transition? db :receipt :receipt/status
                                     :nil :pending)))
    (is (true? (sm/legal-transition? db :receipt :receipt/status
                                     :pending :accepted)))
    (is (true? (sm/legal-transition? db :receipt :receipt/status
                                     :pending :rejected)))
    (is (true? (sm/legal-transition? db :receipt :receipt/status
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
    (is (true? (sm/legal-transition? db :return :return/status
                                     :nil :requested)))
    (is (true? (sm/legal-transition? db :return :return/status
                                     :requested :accepted)))
    (is (true? (sm/legal-transition? db :return :return/status
                                     :requested :rejected)))
    (is (true? (sm/legal-transition? db :return :return/status
                                     :accepted :received)))
    (is (true? (sm/legal-transition? db :return :return/status
                                     :received :completed)))
    (is (false? (sm/legal-transition? db :return :return/status
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
