(ns kontor.valuation-test
  "Tests for ADR-027 (valuation-book entity) and ADR-028 (layer +
   consumption + adjustment views). These exercise the kernel's
   inventory data model independently of the costing engines and
   posting builder (which live in their own test files)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.valuation :as valuation]))

;; ============================================================================
;; ADR-027 — Valuation book
;; ============================================================================

(deftest primary-book-bootstrapped
  (let [conn (core/create-test-db)
        db   (d/db conn)
        eid  (valuation/primary db)]
    (is (some? eid)
        "Primary valuation book must be installed by install-schema!")
    (let [pulled (d/pull db '[*] eid)]
      (is (= "primary"                 (:valuation-book/code pulled)))
      (is (= "Primary valuation book" (:valuation-book/name pulled)))
      (is (= :legal                    (:valuation-book/framework pulled)))
      (is (= :fifo                     (:valuation-book/cost-method pulled)))
      (is (true?                       (:valuation-book/active pulled))))))

(deftest install-defaults-idempotent
  (let [conn (core/create-test-db)
        _ (valuation/install-defaults! conn)
        _ (valuation/install-defaults! conn)
        db (d/db conn)
        n (d/q '[:find (count ?e) .
                 :where [?e :valuation-book/code "primary"]]
               db)]
    (is (= 1 n)
        "Re-installing must not duplicate the primary book")))

(deftest secondary-book-coexists
  (testing "Consumers may register secondary books (IFRS, tax-DE, etc.)"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:valuation-book/code        "ifrs"
                          :valuation-book/name        "IFRS valuation book"
                          :valuation-book/framework   :ifrs
                          :valuation-book/cost-method :avg
                          :valuation-book/active      true}
                         {:valuation-book/code        "tax-de"
                          :valuation-book/name        "German tax book"
                          :valuation-book/framework   :tax-de
                          :valuation-book/cost-method :fifo
                          :valuation-book/active      true}])
          db (d/db conn)]
      (is (some? (valuation/by-code db "primary")))
      (is (some? (valuation/by-code db "ifrs")))
      (is (some? (valuation/by-code db "tax-de")))
      (is (nil?  (valuation/by-code db "nope"))))))

(deftest resolve-book-coerces-spec
  (let [conn (core/create-test-db)
        db   (d/db conn)
        prim (valuation/primary db)]
    (testing "nil → primary"
      (is (= prim (valuation/resolve-book db nil))))
    (testing "string → looked up by :valuation-book/code"
      (is (= prim (valuation/resolve-book db "primary"))))
    (testing "long eid → returned as-is"
      (is (= prim (valuation/resolve-book db prim))))))

;; ============================================================================
;; ADR-028 — Valuation layer + views
;; ============================================================================

(defn- setup-catalog!
  "Plant commodity + journal + a synthetic item (using :account/code
   as a generic ref target — the kernel doesn't model :item)."
  [conn]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:db/id -2 :account/path "Item:Widget" :account/name "Widget item"
                :account/type :asset :account/active true}
               {:db/id -3 :journal/code "STOCK" :journal/name "Stock movements"
                :journal/type :general :journal/active true}])
  (let [db (d/db conn)]
    {:commodity (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :item      (:db/id (d/entity db [:account/path "Item:Widget"]))
     :journal   (:db/id (d/entity db [:journal/code "STOCK"]))
     :book      (valuation/primary db)}))

(defn- transact-receipt!
  "Manually transact a receipt event: one :transaction + one
   :valuation-layer. Returns the new layer's eid."
  [conn {:keys [book item commodity qty unit-cost received-at journal]}]
  (let [_ (d/transact conn
                      [{:db/id -1
                        :transaction/journal        journal
                        :transaction/effective-date received-at
                        :transaction/narration      "Receipt"}
                       {:db/id                              -2
                        :valuation-layer/book               book
                        :valuation-layer/item               item
                        :valuation-layer/origin-transaction -1
                        :valuation-layer/qty-original       qty
                        :valuation-layer/unit-cost-original unit-cost
                        :valuation-layer/commodity          commodity
                        :valuation-layer/received-at        received-at}])
        db (d/db conn)]
    (d/q '[:find ?l .
           :in $ ?received
           :where
           [?l :valuation-layer/received-at ?received]]
         db received-at)))

(defn- transact-consumption!
  "Manually transact a consumption event against an existing layer."
  [conn layer qty unit-cost issued-at journal]
  (d/transact conn
              [{:db/id -1
                :transaction/journal        journal
                :transaction/effective-date issued-at
                :transaction/narration      "Issue"}
               {:layer-consumption/layer                    layer
                :layer-consumption/qty                      qty
                :layer-consumption/unit-cost-at-consumption unit-cost
                :layer-consumption/issue-transaction        -1
                :layer-consumption/issued-at                issued-at}]))

(defn- transact-adjustment!
  "Manually transact a landed-cost-style adjustment to an existing layer."
  [conn layer amount reason applied-at journal]
  (d/transact conn
              [{:db/id -1
                :transaction/journal        journal
                :transaction/effective-date applied-at
                :transaction/narration      (str "Adjustment " (name reason))}
               {:layer-adjustment/layer              layer
                :layer-adjustment/amount             amount
                :layer-adjustment/reason             reason
                :layer-adjustment/origin-transaction -1
                :layer-adjustment/applied-at         applied-at}]))

(deftest qty-remaining-after-no-consumption
  (let [conn (core/create-test-db)
        cat (setup-catalog! conn)
        layer (transact-receipt! conn
                                 (assoc cat :qty 100M :unit-cost 5.00M
                                        :received-at #inst "2026-05-11"))
        db (d/db conn)]
    (is (= 100M (valuation/qty-remaining db layer)))
    (is (= 0M   (valuation/qty-consumed db layer)))))

(deftest qty-remaining-after-partial-consumption
  (let [conn  (core/create-test-db)
        cat   (setup-catalog! conn)
        layer (transact-receipt! conn
                                 (assoc cat :qty 100M :unit-cost 5.00M
                                        :received-at #inst "2026-05-11"))
        _ (transact-consumption! conn layer 30M 5.00M
                                 #inst "2026-05-12" (:journal cat))
        _ (transact-consumption! conn layer 10M 5.00M
                                 #inst "2026-05-13" (:journal cat))
        db (d/db conn)]
    (is (= 40M (valuation/qty-consumed  db layer)))
    (is (= 60M (valuation/qty-remaining db layer)))))

(deftest current-unit-cost-with-no-adjustments
  (let [conn  (core/create-test-db)
        cat   (setup-catalog! conn)
        layer (transact-receipt! conn
                                 (assoc cat :qty 100M :unit-cost 5.00M
                                        :received-at #inst "2026-05-11"))
        db (d/db conn)]
    (is (= 0  (.compareTo (bigdec "5.0000")
                          (valuation/current-unit-cost db layer)))
        "No adjustments → current cost equals original cost")))

(deftest current-unit-cost-with-landed-cost-adjustment
  (testing "Landed cost of +50 on a 100×5 layer → new unit cost
            (100×5 + 50) / 100 = 5.50"
    (let [conn  (core/create-test-db)
          cat   (setup-catalog! conn)
          layer (transact-receipt! conn
                                   (assoc cat :qty 100M :unit-cost 5.00M
                                          :received-at #inst "2026-05-11"))
          _ (transact-adjustment! conn layer 50.00M :landed-cost
                                  #inst "2026-05-15" (:journal cat))
          db (d/db conn)]
      (is (= 0 (.compareTo (bigdec "5.5000")
                           (valuation/current-unit-cost db layer)))))))

(deftest layer-adjustments-are-purely-additive
  (testing "Three landed-cost lines on one layer add up to the right
            total; each adjustment is a new fact, not a mutation"
    (let [conn  (core/create-test-db)
          cat   (setup-catalog! conn)
          layer (transact-receipt! conn
                                   (assoc cat :qty 100M :unit-cost 5.00M
                                          :received-at #inst "2026-05-11"))
          _ (transact-adjustment! conn layer 30.00M :landed-cost
                                  #inst "2026-05-15" (:journal cat))
          _ (transact-adjustment! conn layer 20.00M :landed-cost
                                  #inst "2026-05-16" (:journal cat))
          _ (transact-adjustment! conn layer  10.00M :revaluation
                                  #inst "2026-05-17" (:journal cat))
          db (d/db conn)
          n-adjustments (d/q '[:find (count ?a) .
                               :in $ ?l
                               :where [?a :layer-adjustment/layer ?l]]
                             db layer)]
      (is (= 3 n-adjustments)
          "All three adjustments are stored as separate facts")
      (is (= 0 (.compareTo (bigdec "60.00")
                           (valuation/adjustment-total db layer))))
      (is (= 0 (.compareTo (bigdec "5.6000")
                           (valuation/current-unit-cost db layer)))
          "(500 + 60) / 100 = 5.60"))))

(deftest available-layers-fifo-order
  (testing "available-layers returns layers ordered by received-at
            ascending (oldest first — FIFO order)"
    (let [conn (core/create-test-db)
          cat  (setup-catalog! conn)
          l1 (transact-receipt! conn
                                (assoc cat :qty 100M :unit-cost 5.00M
                                       :received-at #inst "2026-05-11"))
          l2 (transact-receipt! conn
                                (assoc cat :qty 50M :unit-cost 6.00M
                                       :received-at #inst "2026-05-15"))
          l3 (transact-receipt! conn
                                (assoc cat :qty 25M :unit-cost 7.00M
                                       :received-at #inst "2026-05-20"))
          db (d/db conn)
          ordered (valuation/available-layers db (:book cat) (:item cat))]
      (is (= [l1 l2 l3] ordered)
          "Oldest layer first; FIFO providers pop from the head"))))

(deftest available-layers-skips-fully-consumed
  (let [conn (core/create-test-db)
        cat  (setup-catalog! conn)
        l1 (transact-receipt! conn
                              (assoc cat :qty 100M :unit-cost 5.00M
                                     :received-at #inst "2026-05-11"))
        l2 (transact-receipt! conn
                              (assoc cat :qty 50M :unit-cost 6.00M
                                     :received-at #inst "2026-05-15"))
        ;; Fully consume l1
        _ (transact-consumption! conn l1 100M 5.00M
                                 #inst "2026-05-12" (:journal cat))
        db (d/db conn)
        ordered (valuation/available-layers db (:book cat) (:item cat))]
    (is (= [l2] ordered)
        "Fully-consumed layers are excluded from the FIFO stack")))

(deftest on-hand-qty-sums-across-layers
  (let [conn (core/create-test-db)
        cat  (setup-catalog! conn)
        _ (transact-receipt! conn
                             (assoc cat :qty 100M :unit-cost 5.00M
                                    :received-at #inst "2026-05-11"))
        _ (transact-receipt! conn
                             (assoc cat :qty 50M :unit-cost 6.00M
                                    :received-at #inst "2026-05-15"))
        db (d/db conn)]
    (is (= 150M (valuation/on-hand-qty db (:book cat) (:item cat))))))

(deftest on-hand-value-folds-adjustments
  (testing "100 × 5 + 50 × 6 + 50 (landed cost on layer 1)
            = 500 + 50 + 300 = 850"
    (let [conn (core/create-test-db)
          cat  (setup-catalog! conn)
          l1 (transact-receipt! conn
                                (assoc cat :qty 100M :unit-cost 5.00M
                                       :received-at #inst "2026-05-11"))
          _ (transact-receipt! conn
                               (assoc cat :qty 50M :unit-cost 6.00M
                                      :received-at #inst "2026-05-15"))
          _ (transact-adjustment! conn l1 50.00M :landed-cost
                                  #inst "2026-05-20" (:journal cat))
          db (d/db conn)]
      (is (= 0 (.compareTo (bigdec "850.0000")
                           (valuation/on-hand-value db (:book cat) (:item cat))))))))

;; ============================================================================
;; Bitemporal :as-of-valid filtering (#1 from review follow-up)
;; ============================================================================

(deftest available-layers-filters-by-as-of-valid
  (testing "A layer received AFTER the as-of-valid cursor is excluded
            from the FIFO stack — bitemporal correctness per ADR-008"
    (let [conn (core/create-test-db)
          cat  (setup-catalog! conn)
          l1 (transact-receipt! conn
                                (assoc cat :qty 100M :unit-cost 5.00M
                                       :received-at #inst "2026-05-01"))
          l2 (transact-receipt! conn
                                (assoc cat :qty 50M :unit-cost 6.00M
                                       :received-at #inst "2026-05-15"))
          db (d/db conn)]
      (testing "No cursor: both layers visible"
        (is (= [l1 l2] (valuation/available-layers db (:book cat) (:item cat)))))
      (testing "Cursor 2026-05-10: only the first receipt"
        (is (= [l1]
               (valuation/available-layers db (:book cat) (:item cat) nil
                                           {:as-of-valid #inst "2026-05-10"}))))
      (testing "Cursor 2026-04-01: nothing yet received"
        (is (= []
               (valuation/available-layers db (:book cat) (:item cat) nil
                                           {:as-of-valid #inst "2026-04-01"})))))))

(deftest qty-remaining-respects-as-of-valid-on-consumption
  (testing "Backdated cursor sees pre-cursor consumption only"
    (let [conn (core/create-test-db)
          cat (setup-catalog! conn)
          layer (transact-receipt! conn
                                   (assoc cat :qty 100M :unit-cost 5.00M
                                          :received-at #inst "2026-05-01"))
          _ (transact-consumption! conn layer 30M 5.00M
                                   #inst "2026-05-10" (:journal cat))
          _ (transact-consumption! conn layer 20M 5.00M
                                   #inst "2026-05-20" (:journal cat))
          db (d/db conn)]
      (is (= 100M (valuation/qty-remaining db layer
                                           {:as-of-valid #inst "2026-05-05"}))
          "Before any consumption — full qty visible")
      (is (= 70M (valuation/qty-remaining db layer
                                          {:as-of-valid #inst "2026-05-15"}))
          "After first consumption only")
      (is (= 50M (valuation/qty-remaining db layer
                                          {:as-of-valid #inst "2026-05-25"}))
          "After both consumptions")
      (is (= 50M (valuation/qty-remaining db layer))
          "Default opts (no cursor) = current state"))))

(deftest current-unit-cost-respects-as-of-valid-on-adjustments
  (testing "Adjustment applied after the cursor is invisible"
    (let [conn (core/create-test-db)
          cat (setup-catalog! conn)
          layer (transact-receipt! conn
                                   (assoc cat :qty 100M :unit-cost 5.00M
                                          :received-at #inst "2026-05-01"))
          _ (transact-adjustment! conn layer 50.00M :landed-cost
                                  #inst "2026-05-20" (:journal cat))
          db (d/db conn)]
      (is (= 0 (.compareTo (bigdec "5.0000")
                           (valuation/current-unit-cost db layer
                                                        {:as-of-valid #inst "2026-05-10"})))
          "Pre-adjustment cursor → original cost only")
      (is (= 0 (.compareTo (bigdec "5.5000")
                           (valuation/current-unit-cost db layer
                                                        {:as-of-valid #inst "2026-05-25"})))
          "Post-adjustment cursor → adjusted cost"))))

;; ============================================================================
;; Cancelled-transaction filter (#2 from review follow-up)
;; ============================================================================

(deftest cancelled-receipt-excluded-from-available-layers
  (testing "A receipt whose origin transaction is :cancelled does
            NOT appear in available-layers"
    (let [conn (core/create-test-db)
          cat  (setup-catalog! conn)
          ;; Receipt 1 — kept active
          l-keep (transact-receipt! conn
                                    (assoc cat :qty 100M :unit-cost 5.00M
                                           :received-at #inst "2026-05-01"))
          ;; Receipt 2 — about to be cancelled
          l-cancel (transact-receipt! conn
                                      (assoc cat :qty 50M :unit-cost 6.00M
                                             :received-at #inst "2026-05-10"))
          db (d/db conn)
          ;; Mark the second receipt's transaction as cancelled
          cancel-tx (d/q '[:find ?tx . :in $ ?l :where
                           [?l :valuation-layer/origin-transaction ?tx]]
                         db l-cancel)
          _ (d/transact conn [{:db/id cancel-tx
                               :transaction/state :cancelled}])
          db2 (d/db conn)]
      (is (= [l-keep] (valuation/available-layers db2 (:book cat) (:item cat)))
          "Only the non-cancelled receipt is visible by default")
      (is (= 100M (valuation/on-hand-qty db2 (:book cat) (:item cat)))
          "On-hand qty excludes cancelled receipt")
      (testing "Caller can opt back IN to cancelled receipts via :include-states"
        (let [layers (valuation/available-layers
                      db2 (:book cat) (:item cat) nil
                      {:include-states #{:posted :draft :pending-attestation :cancelled}})]
          (is (= 2 (count layers))
              "Both layers visible when :cancelled is in include-states"))))))

(deftest cancelled-consumption-event-excluded
  (testing "A consumption event whose issue transaction is :cancelled
            does not deplete the layer's remaining qty"
    (let [conn (core/create-test-db)
          cat (setup-catalog! conn)
          layer (transact-receipt! conn
                                   (assoc cat :qty 100M :unit-cost 5.00M
                                          :received-at #inst "2026-05-01"))
          _ (transact-consumption! conn layer 30M 5.00M
                                   #inst "2026-05-10" (:journal cat))
          db (d/db conn)
          ;; Cancel the issue transaction
          issue-tx (d/q '[:find ?tx . :in $ ?l :where
                          [?c :layer-consumption/layer ?l]
                          [?c :layer-consumption/issue-transaction ?tx]]
                        db layer)
          _ (d/transact conn [{:db/id issue-tx :transaction/state :cancelled}])
          db2 (d/db conn)]
      (is (= 100M (valuation/qty-remaining db2 layer))
          "Cancelled issue does not deplete the layer")
      (is (= 0M (valuation/qty-consumed db2 layer))
          "Cancelled consumption excluded from consumed sum"))))
