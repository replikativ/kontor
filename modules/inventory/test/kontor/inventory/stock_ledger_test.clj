(ns kontor.inventory.stock-ledger-test
  "ADR-057: kontor-inventory facilities + the physical stock ledger.

   Covers:
   - define-facility! / define-location! — the warehouse tree + bins.
   - find-or-create-inventory-item! — bucket resolution by query
     (nil location/lot/owner match attribute-absent; a second call
     for the same key returns the same eid).
   - record-detail! appends a signed-delta row; :atp-diff defaults to
     :qoh-diff.
   - place-opening-stock! creates the bucket + the :opening detail in
     one tx.
   - on-hand-qty derives QOH from the ledger, sums across buckets for
     a {:product :facility} scope, and honours the :as-of-valid
     bitemporal axis."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.inventory.core :as inv]
            [kontor.inventory.schema :as inv-schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (inv-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :commodity/symbol "EUR" :commodity/precision 2}
                 ;; Legal entity — the facility owner (ADR-031).
                 {:db/id "entity-de" :entity/code "ACME-DE" :entity/name "Acme GmbH"}
                 ;; Products — :inventory-item/product is a generic ref;
                 ;; reuse :partner entities as product stand-ins (the
                 ;; kernel test convention for caller-defined refs).
                 {:partner/external-id "P-widget" :partner/name "Widget"}
                 {:partner/external-id "P-gadget" :partner/name "Gadget"}
                 ;; Two lots of the widget.
                 {:db/id "lot-a" :lot/label "LOT-A" :lot/acquired-at #inst "2026-01-10"}
                 {:db/id "lot-b" :lot/label "LOT-B" :lot/acquired-at #inst "2026-02-10"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- product [db code] (ref-eid db :partner/external-id code))
(defn- entity  [db]      (ref-eid db :entity/code "ACME-DE"))
(defn- lot     [db label] (ref-eid db :lot/label label))

;; ============================================================================
;; Facilities + locations
;; ============================================================================

(deftest define-facility-tree-and-locations
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH-BERLIN" :name "Berlin Warehouse"
                                      :type :warehouse
                                      :owner-entity (entity (d/db conn))
                                      :default-days-to-ship 2})
        _ (inv/define-facility! conn {:code "WH-BERLIN-A" :name "Berlin Hall A"
                                      :type :warehouse :parent "WH-BERLIN"})
        _ (inv/define-location! conn {:facility "WH-BERLIN-A" :seq-id "A-01-01"
                                      :type :pickloc :area "A" :aisle "01" :bin "01"})
        _ (inv/define-location! conn {:facility "WH-BERLIN-A" :seq-id "BULK-1"
                                      :type :bulk})
        db (d/db conn)]
    (testing "the facility tree resolves by code and parent"
      (let [berlin (inv/facility-by-code db "WH-BERLIN")
            hall-a (inv/facility-by-code db "WH-BERLIN-A")]
        (is (some? berlin))
        (is (= berlin (:db/id (:facility/parent
                               (d/pull db [:facility/parent] hall-a)))))))
    (testing "locations resolve by (facility, seq-id) and carry their type"
      (let [pick (inv/location-by db "WH-BERLIN-A" "A-01-01")]
        (is (some? pick))
        (is (= :pickloc (:facility-location/type
                         (d/pull db [:facility-location/type] pick))))))
    (testing "the (facility, seq-id) identity tuple is unique"
      (is (= 2 (count (d/q '[:find [?e ...]
                             :where [?e :facility-location/seq-id _]] db)))))))

;; ============================================================================
;; Bucket resolution
;; ============================================================================

(deftest find-or-create-inventory-item-is-idempotent-by-key
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
        db (d/db conn)
        wh (inv/facility-by-code db "WH")
        widget (product db "P-widget")
        spec {:product widget :facility wh :lot (lot db "LOT-A")}
        first-eid (inv/find-or-create-inventory-item! conn spec)
        second-eid (inv/find-or-create-inventory-item! conn spec)]
    (testing "a second call for the same (product, facility, lot) key returns the same bucket"
      (is (= first-eid second-eid)))
    (testing "a different lot is a different bucket"
      (is (not= first-eid
                (inv/find-or-create-inventory-item!
                 conn (assoc spec :lot (lot (d/db conn) "LOT-B"))))))
    (testing "nil lot ≡ attribute-absent — an un-lotted bucket is distinct"
      (is (not= first-eid
                (inv/find-or-create-inventory-item!
                 conn {:product widget :facility wh}))))))

;; ============================================================================
;; record-detail! + on-hand-qty
;; ============================================================================

(deftest record-detail-and-derived-on-hand-qty
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
        db (d/db conn)
        wh (inv/facility-by-code db "WH")
        widget (product db "P-widget")
        item (inv/find-or-create-inventory-item! conn {:product widget :facility wh})]
    (testing "with no details, on-hand-qty is 0"
      (is (= 0M (inv/on-hand-qty (d/db conn) item))))
    (testing "appending signed deltas accumulates"
      (inv/record-detail! conn {:inventory-item item :qoh-diff 100M
                                :effective-date #inst "2026-03-01"
                                :source-kind :opening})
      (inv/record-detail! conn {:inventory-item item :qoh-diff -30M
                                :effective-date #inst "2026-03-10"
                                :source-kind :issuance})
      (inv/record-detail! conn {:inventory-item item :qoh-diff 50M
                                :effective-date #inst "2026-03-20"
                                :source-kind :receipt})
      (is (= 120M (inv/on-hand-qty (d/db conn) item))))
    (testing ":atp-diff defaults to :qoh-diff for a pure physical move"
      (is (= 120M (d/q '[:find (sum ?atp) .
                         :with ?d
                         :in $ ?item
                         :where
                         [?d :inventory-detail/inventory-item ?item]
                         [?d :inventory-detail/atp-diff ?atp]]
                       (d/db conn) item))))
    (testing ":as-of-valid filters by :effective-date"
      (is (= 100M (inv/on-hand-qty (d/db conn) item
                                   {:as-of-valid #inst "2026-03-05"})))
      (is (= 70M (inv/on-hand-qty (d/db conn) item
                                  {:as-of-valid #inst "2026-03-15"}))))))

;; ============================================================================
;; place-opening-stock!
;; ============================================================================

(deftest place-opening-stock-creates-bucket-and-detail
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
        db (d/db conn)
        wh (inv/facility-by-code db "WH")
        widget (product db "P-widget")
        {:keys [inventory-item]}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :lot (lot db "LOT-A")
                                        :qty 250M
                                        :effective-date #inst "2026-01-15"})]
    (testing "the bucket is created and carries the opening quantity"
      (is (some? inventory-item))
      (is (= 250M (inv/on-hand-qty (d/db conn) inventory-item))))
    (testing "a second opening into the same bucket reuses it"
      (let [{again :inventory-item}
            (inv/place-opening-stock! conn {:product widget :facility wh
                                            :lot (lot (d/db conn) "LOT-A")
                                            :qty 10M
                                            :effective-date #inst "2026-01-20"})]
        (is (= inventory-item again))
        (is (= 260M (inv/on-hand-qty (d/db conn) inventory-item)))))
    (testing "the detail is tagged :source-kind :opening"
      (is (every? #(= :opening (:inventory-detail/source-kind %))
                  (inv/details-of (d/db conn) inventory-item))))))

;; ============================================================================
;; on-hand-qty across buckets — {:product :facility} scope
;; ============================================================================

(deftest on-hand-qty-sums-across-buckets
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH-1" :name "WH 1" :type :warehouse})
        _ (inv/define-facility! conn {:code "WH-2" :name "WH 2" :type :warehouse})
        db (d/db conn)
        wh1 (inv/facility-by-code db "WH-1")
        wh2 (inv/facility-by-code db "WH-2")
        widget (product db "P-widget")]
    ;; Widget: 100 of LOT-A + 40 of LOT-B at WH-1; 25 at WH-2.
    (inv/place-opening-stock! conn {:product widget :facility wh1
                                    :lot (lot db "LOT-A") :qty 100M})
    (inv/place-opening-stock! conn {:product widget :facility wh1
                                    :lot (lot db "LOT-B") :qty 40M})
    (inv/place-opening-stock! conn {:product widget :facility wh2 :qty 25M})
    (testing "facility-scoped sum"
      (is (= 140M (inv/on-hand-qty (d/db conn) {:product widget :facility wh1})))
      (is (= 25M (inv/on-hand-qty (d/db conn) {:product widget :facility wh2}))))
    (testing "product-wide sum across all facilities"
      (is (= 165M (inv/on-hand-qty (d/db conn) {:product widget}))))))
