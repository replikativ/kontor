(ns kontor.inventory.count-report-test
  "ADR-060: cycle counts + reconciliation reports + FEFO.

   Covers:
   - start-count! / record-count-line! snapshots the perpetual
     on-hand-qty as :expected-qty and computes :qoh-var.
   - post-count! routes a negative variance through plan-stock-move
     :out (shrinkage) and a positive one through :in (found stock),
     appends the physical :variance detail, sets the count :posted;
     a :recount-of line supersedes the original.
   - inventory-roll-forward: opening + Σ movements = closing,
     bucketed by :source-kind.
   - valuation-tie-out: the subledger ties to the GL inventory account.
   - FEFO: FefoCostingProvider draws nearest-expiry layers first;
     reserve! :fifo-exp picks the nearest-expiry bucket."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.provider.costing-provider :as costing]
            [kontor.inventory.core :as inv]
            [kontor.inventory.fefo-costing-provider :as inv-costing]
            [kontor.inventory.count :as count]
            [kontor.inventory.ops :as ops]
            [kontor.inventory.report :as report]
            [kontor.inventory.reservation :as res]
            [kontor.inventory.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.sales.schema :as sales-schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (partner-schema/install! conn)
    (sales-schema/install! conn)
    (inv-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "P-widget" :kontor.partner/name "Widget"}
                 {:kontor.partner/external-id "U-counter" :kontor.partner/name "Counter"}
                 {:kontor.partner/external-id "O-1"  :kontor.partner/name "Order 1"}
                 {:kontor.partner/external-id "OI-1" :kontor.partner/name "Order line 1"}
                 {:kontor.partner/external-id "SG-1" :kontor.partner/name "Ship group 1"}
                 {:db/id "acct-inv" :kontor.account/code "1400" :kontor.account/name "Inventory"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-grir" :kontor.account/code "1410" :kontor.account/name "GR-IR"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "acct-cogs" :kontor.account/code "5000" :kontor.account/name "COGS"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-shrink" :kontor.account/code "5100" :kontor.account/name "Shrinkage"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-found" :kontor.account/code "4800" :kontor.account/name "Found Stock Gain"
                  :kontor.account/type :income :kontor.account/active true}
                 {:db/id "book" :kontor.valuation-book/code "primary"
                  :kontor.valuation-book/name "Primary" :kontor.valuation-book/cost-method :fifo
                  :kontor.valuation-book/active true}
                 {:db/id "journal-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 ;; Two lots — LOT-B expires sooner than LOT-A.
                 {:db/id "lot-a" :kontor.lot/label "LOT-A" :kontor.lot/expires-at #inst "2026-09-01"}
                 {:db/id "lot-b" :kontor.lot/label "LOT-B" :kontor.lot/expires-at #inst "2026-04-01"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- p       [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct    [db code] (ref-eid db :kontor.account/code code))
(defn- book    [db] (ref-eid db :kontor.valuation-book/code "primary"))
(defn- journal [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- eur     [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- lot     [db label] (ref-eid db :kontor.lot/label label))

;; account-fn for the standard receive/issue roles + count routing:
;; :cogs → shrinkage on a count :out; :gr-ir-clearing → found-gain on
;; a count :in.
(defn- count-account-fn [db]
  (let [m {:inventory      (acct db "1400")
           :gr-ir-clearing (acct db "4800")   ; found-stock gain
           :cogs           (acct db "5100")   ; shrinkage
           :price-variance (acct db "5000")}]
    (fn [_move role] (get m role))))

(defn- receive-account-fn [db]
  (let [m {:inventory      (acct db "1400")
           :gr-ir-clearing (acct db "1410")
           :cogs           (acct db "5000")
           :price-variance (acct db "5000")}]
    (fn [_move role] (get m role))))

(defn- warehouse! [conn]
  (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
  (inv/facility-by-code (d/db conn) "WH"))

;; ============================================================================
;; Cycle counts
;; ============================================================================

(deftest count-snapshots-expected-and-computes-variance
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        {item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh :qty 100M
                                        :effective-date #inst "2026-03-01"})
        {:keys [physical-inventory]}
        (count/start-count! conn {:facility wh :count-date #inst "2026-03-15"
                                  :counted-by (p (d/db conn) "U-counter")})
        line (count/record-count-line! conn {:physical-inventory physical-inventory
                                             :inventory-item item
                                             :counted-qty 95M
                                             :reason :shrinkage})]
    (testing "the line snapshots expected = 100 and computes var = −5"
      (is (= 100M (:expected-qty line)))
      (is (= 95M (:counted-qty line)))
      (is (= -5M (:qoh-var line))))))

(deftest post-count-routes-shrinkage-and-found-through-the-gl
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        ;; Receive 100 so there are real layers for a negative variance to consume.
        {item :inventory-item}
        (ops/receive! conn {:product widget :facility wh :book (book db)
                            :qty 100M :unit-cost 10.00M :commodity (eur db)
                            :journal (journal db) :account-fn (receive-account-fn db)
                            :effective-date #inst "2026-03-01"})
        {:keys [physical-inventory]}
        (count/start-count! conn {:facility wh :count-date #inst "2026-03-20"})
        _ (count/record-count-line! conn {:physical-inventory physical-inventory
                                          :inventory-item item :counted-qty 92M
                                          :reason :shrinkage})
        result (count/post-count! conn {:physical-inventory physical-inventory
                                        :book (book (d/db conn))
                                        :journal (journal (d/db conn))
                                        :account-fn (count-account-fn (d/db conn))
                                        :commodity (eur (d/db conn))})]
    (testing "the −8 variance is posted: QOH drops to 92, shrinkage hit the GL"
      (is (= 1 (:count result)))
      (is (= 92M (inv/on-hand-qty (d/db conn) item)))
      (is (= 1 (count (d/q '[:find [?pp ...] :in $ ?a
                             :where [?pp :kontor.posting/account ?a]]
                           (d/db conn) (acct (d/db conn) "5100")))))
      (is (= :posted (:kontor.physical-inventory/status
                      (d/pull (d/db conn) [:kontor.physical-inventory/status]
                              physical-inventory)))))
    (testing "the physical detail is tagged :variance and linked to the variance row"
      (let [det (last (inv/details-of (d/db conn) item))]
        (is (= :variance (:kontor.inventory-detail/source-kind det)))
        (is (some? (:kontor.inventory-detail/source det)))))))

(deftest post-count-is-idempotent-on-re-run
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        {item :inventory-item}
        (ops/receive! conn {:product widget :facility wh :book (book db)
                            :qty 100M :unit-cost 10.00M :commodity (eur db)
                            :journal (journal db) :account-fn (receive-account-fn db)
                            :effective-date #inst "2026-03-01"})
        {:keys [physical-inventory]}
        (count/start-count! conn {:facility wh :count-date #inst "2026-03-20"})
        _ (count/record-count-line! conn {:physical-inventory physical-inventory
                                          :inventory-item item :counted-qty 92M
                                          :reason :shrinkage})
        first-run (count/post-count! conn {:physical-inventory physical-inventory
                                           :book (book (d/db conn))
                                           :journal (journal (d/db conn))
                                           :account-fn (count-account-fn (d/db conn))
                                           :commodity (eur (d/db conn))})
        second-run (count/post-count! conn {:physical-inventory physical-inventory
                                            :book (book (d/db conn))
                                            :journal (journal (d/db conn))
                                            :account-fn (count-account-fn (d/db conn))
                                            :commodity (eur (d/db conn))})]
    (testing "the first run posts the variance; a re-run posts nothing (idempotent)"
      (is (= 1 (:count first-run)))
      (is (= 0 (:count second-run)))
      (is (= 92M (inv/on-hand-qty (d/db conn) item)) "QOH not double-adjusted"))))

(deftest post-count-recount-supersedes-the-original
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        {item :inventory-item}
        (ops/receive! conn {:product widget :facility wh :book (book db)
                            :qty 100M :unit-cost 10.00M :commodity (eur db)
                            :journal (journal db) :account-fn (receive-account-fn db)
                            :effective-date #inst "2026-03-01"})
        {:keys [physical-inventory]}
        (count/start-count! conn {:facility wh :count-date #inst "2026-03-20"})
        first-line (count/record-count-line! conn {:physical-inventory physical-inventory
                                                   :inventory-item item :counted-qty 80M})
        ;; A recount finds the real number is 97.
        _ (count/record-count-line! conn {:physical-inventory physical-inventory
                                          :inventory-item item :counted-qty 97M
                                          :reason :recount
                                          :recount-of (:variance first-line)})
        result (count/post-count! conn {:physical-inventory physical-inventory
                                        :book (book (d/db conn))
                                        :journal (journal (d/db conn))
                                        :account-fn (count-account-fn (d/db conn))
                                        :commodity (eur (d/db conn))})]
    (testing "only the recount line posts — QOH lands at 97, not 80"
      (is (= 1 (:count result)))
      (is (= 97M (inv/on-hand-qty (d/db conn) item))))))

;; ============================================================================
;; Reconciliation reports
;; ============================================================================

(deftest inventory-roll-forward-opening-movements-closing
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        ;; Pre-window opening of 60; in-window a receipt of 40 and an issue of 25.
        {item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh :qty 60M
                                        :effective-date #inst "2025-12-01"})
        _ (ops/receive! conn {:product widget :facility wh :book (book (d/db conn))
                              :qty 40M :unit-cost 10.00M :commodity (eur (d/db conn))
                              :journal (journal (d/db conn))
                              :account-fn (receive-account-fn (d/db conn))
                              :effective-date #inst "2026-02-10"})
        _ (ops/issue! conn {:product widget :facility wh :book (book (d/db conn))
                            :qty 25M :commodity (eur (d/db conn))
                            :journal (journal (d/db conn))
                            :account-fn (receive-account-fn (d/db conn))
                            :inventory-item item :effective-date #inst "2026-02-20"})
        rf (report/inventory-roll-forward
            (d/db conn) {:from #inst "2026-01-01" :to #inst "2026-03-01"
                         :scope {:product widget :facility wh}})]
    (testing "opening = pre-window 60; movements net +15; closing = 75"
      (is (= 60M (:opening rf)))
      (is (= 40M (get (:movements rf) :receipt)))
      (is (= -25M (get (:movements rf) :issuance)))
      (is (= 15M (:movements-total rf)))
      (is (= 75M (:closing rf)))
      (is (= 75M (inv/on-hand-qty (d/db conn) item))))))

(deftest valuation-tie-out-ties-subledger-to-gl
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        _ (ops/receive! conn {:product widget :facility wh :book (book db)
                              :qty 100M :unit-cost 12.00M :commodity (eur db)
                              :journal (journal db) :account-fn (receive-account-fn db)
                              :effective-date #inst "2026-03-01"})
        tie (report/valuation-tie-out conn {:book (book (d/db conn))
                                            :inventory-account (acct (d/db conn) "1400")
                                            :commodity (eur (d/db conn))})]
    (testing "subledger value (100 × 12) ties to the GL inventory account"
      (is (= 1200.00M (:subledger tie)))
      (is (= 1200.00M (:gl tie)))
      (is (= 0M (:difference tie)))
      (is (true? (:ok? tie))))))

;; ============================================================================
;; FEFO
;; ============================================================================

(deftest fefo-provider-draws-nearest-expiry-first
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        ;; LOT-A (expires 2026-09) received first, LOT-B (expires 2026-04) later.
        _ (ops/receive! conn {:product widget :facility wh :book (book db)
                              :qty 50M :unit-cost 10.00M :commodity (eur db)
                              :lot (lot db "LOT-A") :journal (journal db)
                              :account-fn (receive-account-fn db)
                              :effective-date #inst "2026-01-10"})
        _ (ops/receive! conn {:product widget :facility wh :book (book (d/db conn))
                              :qty 50M :unit-cost 11.00M :commodity (eur (d/db conn))
                              :lot (lot (d/db conn) "LOT-B") :journal (journal (d/db conn))
                              :account-fn (receive-account-fn (d/db conn))
                              :effective-date #inst "2026-01-20"})
        plan (costing/plan-consumption (inv-costing/make-fefo-provider)
                                       (d/db conn)
                                       {:book (book (d/db conn)) :item widget :qty 30M})]
    (testing "FEFO draws from LOT-B (nearest expiry) despite LOT-A being received first"
      (is (= 1 (count (:consumptions plan))))
      (is (= 30M (:qty (first (:consumptions plan)))))
      (is (= 11.00M (:unit-cost (first (:consumptions plan))))
          "LOT-B's unit cost — the nearest-expiry layer"))))

(deftest reserve-fifo-exp-picks-nearest-expiry-bucket
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        {late :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :lot (lot db "LOT-A") :qty 40M})
        {soon :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :lot (lot db "LOT-B") :qty 40M})
        _ (res/reserve! conn {:product widget :facility wh :quantity 30M
                              :reserve-order-enum :fifo-exp
                              :order (p (d/db conn) "O-1")
                              :order-item (p (d/db conn) "OI-1")
                              :ship-group (p (d/db conn) "SG-1")})]
    (testing ":fifo-exp draws the nearest-expiry (LOT-B) bucket first"
      (is (= 10M (res/atp-raw (d/db conn) soon)) "LOT-B drawn down to 10")
      (is (= 40M (res/atp-raw (d/db conn) late)) "LOT-A untouched"))))
