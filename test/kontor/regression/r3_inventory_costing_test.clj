(ns kontor.regression.r3-inventory-costing-test
  "Round-3 regression pins for inventory-valuation edges — landed cost,
   NRV write-down / revaluation, AVCO↔GL drift, GR-IR bill-vs-receipt
   true-up.

   Substrate under test:
     - kontor.provider.valuation      valuation-book + layers + views
     - kontor.provider.costing-provider  FIFO / AVCO / Standard providers
     - kontor.posting/plan-stock-move + stock-move-roles (ADR-030)
     - kontor.inventory.ops           receive! / issue! / true-up-negative-fill!
     - kontor.reporting.balance       GL account balance (subledger↔GL check)

   GREEN pins (confirm correct behaviour, every number hand-derived):
     - FIFO COGS + remaining on-hand value (no drift: consumption cost =
       layer cost).
     - AVCO COGS at the running average.
     - A manually-applied :layer-adjustment flows into current-unit-cost
       and on-hand-value — the value primitive a landed-cost / write-down
       builder would ride, proving the substrate CAN hold the number.

   PENDING(NEW) pins (genuine substrate gaps this area was asked to make
   concrete; each starts passing the day the substrate lands):
     (4) AVCO on-hand *value* drifts from the GL inventory account —
         consumption is stamped at the running average but the layer view
         keeps each layer's original cost and drains the cheapest layer
         physically, over-valuing what remains.
     (5) no builder allocates a freight/duty voucher across layers by
         qty / value / weight (`plan-adjustment-move` was reserved, never
         written).
     (6) no verb books an IAS 2 lower-of-cost-and-NRV write-down (or a
         standard-cost revaluation of on-hand) — GL leg + layer adjustment
         atomically.
     (7) no GR-IR bill-vs-receipt price-difference true-up that SPLITS the
         variance by on-hand-vs-consumed (`true-up-negative-fill!` handles
         only the negative-fill case and books the full delta to one
         inventory/variance pair).

   Odoo references cite /home/christian-weilbach/Development/odoo/addons.

   Everything is booked over a self-contained EUR chart on
   kontor.core/create-test-db, through the same write paths a consumer
   uses (kontor.inventory.ops)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.inventory.core :as inv]
            [kontor.inventory.ops :as ops]
            [kontor.inventory.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.posting :as posting]
            [kontor.provider.valuation :as valuation]
            [kontor.reporting.balance :as balance]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap
  "Fresh conn with the inventory substrate, a EUR commodity, one product,
   a GL chart, a FIFO 'primary' book and an AVCO 'avco' book, and a
   general journal."
  []
  (let [conn (core/create-test-db)]
    (partner-schema/install! conn)
    (inv-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "P-widget" :kontor.partner/name "Widget"}
                 {:db/id "acct-inv" :kontor.account/code "1400" :kontor.account/name "Inventory"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-grir" :kontor.account/code "1410" :kontor.account/name "GR-IR Clearing"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "acct-cogs" :kontor.account/code "5000" :kontor.account/name "COGS"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-var" :kontor.account/code "5900" :kontor.account/name "Cost Variance"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-wd" :kontor.account/code "5910" :kontor.account/name "Inventory Write-down"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "book" :kontor.valuation-book/code "primary"
                  :kontor.valuation-book/name "Primary (FIFO)"
                  :kontor.valuation-book/cost-method :fifo :kontor.valuation-book/active true}
                 {:db/id "book-avco" :kontor.valuation-book/code "avco"
                  :kontor.valuation-book/name "AVCO"
                  :kontor.valuation-book/cost-method :avg :kontor.valuation-book/active true}
                 {:db/id "journal-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- p       [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct    [db code] (ref-eid db :kontor.account/code code))
(defn- book    [db code] (ref-eid db :kontor.valuation-book/code code))
(defn- journal [db]      (ref-eid db :kontor.journal/code "GEN"))
(defn- eur     [db]      (ref-eid db :kontor.commodity/symbol "EUR"))

(defn- account-fn [db]
  (let [m {:inventory      (acct db "1400")
           :gr-ir-clearing (acct db "1410")
           :cogs           (acct db "5000")
           :price-variance (acct db "5900")}]
    (fn [_move role] (get m role))))

(defn- warehouse! [conn]
  (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
  (inv/facility-by-code (d/db conn) "WH"))

(defn- bd= [a b]
  (zero? (.compareTo ^java.math.BigDecimal a ^java.math.BigDecimal b)))

(defn- posting-amount
  "The single :kontor.posting/amount booked against `acct-eid` (nil if none)."
  [db acct-eid]
  (d/q '[:find ?amt . :in $ ?a
         :where [?pp :kontor.posting/account ?a]
         [?pp :kontor.posting/amount ?amt]]
       db acct-eid))

(defn- gl-balance
  "Signed GL balance (BigDecimal, positive=debit) on account `code`."
  [conn code]
  (let [db (d/db conn)]
    (:amount (balance/account-balance-single-commodity conn (acct db code) (eur db)))))

;; ============================================================================
;; GREEN — FIFO COGS + remaining value (hand-derived), no drift
;; ============================================================================
;;
;; Receive 100 @ 12.00 (2026-03-01), then 50 @ 15.00 (2026-03-02).
;; Issue 120 (2026-03-03). FIFO draws 100@12 + 20@15.
;;   COGS      = 100·12 + 20·15 = 1200 + 300 = 1500.00
;;   GL 1400   = 1200 + 750 − 1500 = 450.00
;;   layer val = 30·15 = 450.00   (only layer-2 remains, 30 units)
;;   on-hand   = 30
;; FIFO stamps consumption at the drawn layer's cost, so the GL inventory
;; account and the perpetual layer valuation AGREE (no drift).

(deftest fifo-cogs-remaining-value-and-no-drift
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 50M :unit-cost 15.00M :commodity (eur (d/db conn))
                        :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                        :effective-date #inst "2026-03-02"})
    (ops/issue! conn {:product widget :facility wh :book bk
                      :qty 120M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-03"})
    (let [db2 (d/db conn)]
      (testing "COGS posting = FIFO 100·12 + 20·15 = 1500.00"
        (is (bd= 1500.00M (posting-amount db2 (acct db2 "5000")))))
      (testing "on-hand qty = 30 (150 received − 120 issued)"
        (is (bd= 30M (valuation/on-hand-qty db2 bk widget))))
      (testing "layer on-hand value = 30·15 = 450.00"
        (is (bd= 450.00M (valuation/on-hand-value db2 bk widget))))
      (testing "GL inventory (1400) = 1200 + 750 − 1500 = 450.00"
        (is (bd= 450.00M (gl-balance conn "1400"))))
      (testing "subledger and GL AGREE under FIFO — no valuation drift"
        (is (bd= (gl-balance conn "1400")
                 (valuation/on-hand-value db2 bk widget)))))))

;; ============================================================================
;; GREEN — AVCO COGS at the running average (hand-derived)
;; ============================================================================
;;
;; AVCO book. Receive 100 @ 10.00, then 100 @ 14.00. Running average =
;; (100·10 + 100·14) / 200 = 2400/200 = 12.0000. Issue 50 → COGS = 50·12 =
;; 600.00. On-hand qty = 150.

(deftest avco-cogs-at-running-average
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "avco")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 10.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 14.00M :commodity (eur (d/db conn))
                        :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                        :effective-date #inst "2026-03-02"})
    (ops/issue! conn {:product widget :facility wh :book bk
                      :qty 50M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-03"})
    (let [db2 (d/db conn)]
      (testing "COGS posting = 50 · avg(12) = 600.00"
        (is (bd= 600.00M (posting-amount db2 (acct db2 "5000")))))
      (testing "on-hand qty = 150"
        (is (bd= 150M (valuation/on-hand-qty db2 bk widget)))))))

;; ============================================================================
;; GREEN — a :layer-adjustment flows into unit cost + on-hand value
;; ============================================================================
;;
;; The value primitive a landed-cost / write-down builder would ride.
;; Receive 100 @ 12.00 = 1200. Post a +200.00 :layer-adjustment (freight)
;; onto the layer. current-unit-cost = (100·12 + 200)/100 = 14.0000;
;; on-hand-value = 100·14 = 1400.00. This proves the substrate CAN hold a
;; landed cost — what is missing (pins 5/6) is the builder that COMPUTES
;; and books the adjustment.

(deftest layer-adjustment-flows-into-valuation
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (let [layer (first (valuation/available-layers (d/db conn) bk widget))]
      (is (some? layer) "one layer was created by the receipt")
      (d/transact conn
                  [{:db/id "adj-tx"
                    :kontor.transaction/journal (journal (d/db conn))
                    :kontor.transaction/effective-date #inst "2026-03-05"
                    :kontor.transaction/state :posted
                    :kontor.transaction/posted-at #inst "2026-03-05"}
                   {:kontor.layer-adjustment/layer layer
                    :kontor.layer-adjustment/amount 200.00M
                    :kontor.layer-adjustment/reason :landed-cost
                    :kontor.layer-adjustment/origin-transaction "adj-tx"
                    :kontor.layer-adjustment/applied-at #inst "2026-03-05"
                    :kontor.layer-adjustment/note "freight"}])
      (let [db2 (d/db conn)]
        (testing "current-unit-cost = (1200 + 200)/100 = 14.0000"
          (is (bd= 14.0000M (valuation/current-unit-cost db2 layer))))
        (testing "on-hand value reflects the landed cost = 100·14 = 1400.00"
          (is (bd= 1400.00M (valuation/on-hand-value db2 bk widget))))))))

;; ============================================================================
;; PENDING(NEW) (4) — AVCO on-hand VALUE drifts from the GL inventory account
;; ============================================================================
;;
;; kontor.inventory's headline guarantee is that the physical and financial
;; views "cannot drift, because they are written together" (ops.clj:9-12).
;; That holds for QUANTITY and for FIFO value — but NOT for AVCO value.
;;
;; AVCO book: receive 100 @ 10.00 (value 1000), receive 100 @ 14.00
;; (value 1400), issue 50. The WeightedAverageProvider stamps the issue at
;; the running average 12 (COGS 600 → GL Cr inventory 600), but consumes
;; the cheapest physical layer FIFO-style (50 units off layer-1 @ 10):
;;   GL 1400 inventory = 1000 + 1400 − 600            = 1800.00
;;   layer on-hand-value = 50·10 (layer-1) + 100·14   = 1900.00
;; They diverge by (avg 12 − drawn 10)·50 = 100 — the average premium on
;; the issued units is never carried back into the layer valuation, so the
;; remaining stock is over-valued by 100.
;;
;; Odoo has no persistent per-layer cost under AVCO: _run_avco values
;; on-hand at qty × standard_price(avg) = 150 × 12 = 1800, matching the GL.
;; stock_account/models/stock_valuation_layer.py:651 (_run_avco);
;; product avg maintenance at :626-:646.

(deftest ^:kaocha/pending avco-onhand-value-drifts-from-gl
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "avco")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 10.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 14.00M :commodity (eur (d/db conn))
                        :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                        :effective-date #inst "2026-03-02"})
    (ops/issue! conn {:product widget :facility wh :book bk
                      :qty 50M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-03"})
    (let [db2       (d/db conn)
          gl-inv    (gl-balance conn "1400")
          layer-val (valuation/on-hand-value db2 bk widget)]
      (testing "GL inventory = 1000 + 1400 − 600 = 1800.00 (hand-derived)"
        (is (bd= 1800.00M gl-inv)))
      (testing "layer on-hand value = 50·10 + 100·14 = 1900.00 (hand-derived)"
        (is (bd= 1900.00M layer-val)))
      ;; PENDING(NEW): under AVCO the subledger valuation and the GL
      ;; inventory account MUST agree; here they drift by 100. No substrate
      ;; carries the average premium on issued units back into the layers.
      (testing "AVCO: subledger valuation should equal GL inventory (drifts by 100)"
        (is (bd= gl-inv layer-val))))))

;; ============================================================================
;; PENDING(NEW) (5) — no landed-cost allocation builder
;; ============================================================================
;;
;; Two receipts of the widget create two layers: 100 @ 12 (value 1200) and
;; 50 @ 12 (value 600). A freight voucher of 300.00 should be allocated
;; across the layers:
;;   by_quantity : 300 / 150u = 2/u → layer-A +200 (100u), layer-B +100 (50u)
;;   by_value    : 300 · 1200/1800 = +200 ; 300 · 600/1800 = +100
;; kontor exposes the ACCOUNT ROLE (:landed-cost-clearing) and the value
;; sink (:layer-adjustment, pin 3 above) but ships NO builder that computes
;; the split and books the vouchers — posting.clj:238 reserves the role for
;; "a future plan-adjustment-move" that was never written.
;;
;; Odoo: stock_landed_costs/models/stock_landed_cost.py:180 compute_landed_cost
;; with split_method by_quantity / by_weight / by_volume /
;; by_current_cost_price / equal (:207-:226; SPLIT_METHOD :11-:17).

(deftest ^:kaocha/pending landed-cost-allocation-builder-absent
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 50M :unit-cost 12.00M :commodity (eur (d/db conn))
                        :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                        :effective-date #inst "2026-03-02"})
    (testing "two layers exist to allocate freight across"
      (is (= 2 (count (valuation/available-layers (d/db conn) bk widget)))))
    ;; PENDING(NEW): no builder allocates a freight/duty voucher across
    ;; layers by qty/value/weight and books :landed-cost-clearing.
    (testing "an ops/apply-landed-cost! builder should exist"
      (is (some? (resolve 'kontor.inventory.ops/apply-landed-cost!))))
    (testing "a posting/plan-adjustment-move builder should exist"
      (is (some? (resolve 'kontor.posting/plan-adjustment-move))))))

;; ============================================================================
;; PENDING(NEW) (6) — no NRV write-down / standard-cost revaluation verb
;; ============================================================================
;;
;; IAS 2.9: inventory is carried at the lower of cost and net realisable
;; value. Receive 100 @ 12.00 (on-hand value 1200). NRV falls to 9.00 → a
;; write-down of 100 · (12 − 9) = 300.00 must post Dr write-down-expense
;; 300 / Cr inventory 300, driving on-hand value to 900.00. kontor reserves
;; the :write-down-expense / :revaluation-loss ROLES (posting.clj:242-251)
;; but ships NO verb that computes the write-down and books the GL leg +
;; the :layer-adjustment atomically. (kontor-asset has revalue!/impair!
;; for fixed assets; inventory has no equivalent.)
;;
;; Odoo: product write-down via _change_standard_price →
;; stock_valuation_layer.py:286-:323 (revaluation SVL when standard_price
;; changes).

(deftest ^:kaocha/pending nrv-write-down-verb-absent
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (testing "the account roles are reserved (intent is documented)"
      (is (contains? posting/stock-move-roles :write-down-expense))
      (is (contains? posting/stock-move-roles :revaluation-loss)))
    (testing "on-hand value is at cost before any write-down"
      (is (bd= 1200.00M (valuation/on-hand-value (d/db conn) bk widget))))
    ;; PENDING(NEW): no verb books an IAS 2 NRV write-down / standard-cost
    ;; revaluation of on-hand (GL leg + layer adjustment) atomically.
    (testing "an inventory NRV write-down verb should exist"
      (is (or (some? (resolve 'kontor.inventory.ops/write-down-to-nrv!))
              (some? (resolve 'kontor.inventory.ops/revalue!))
              (some? (resolve 'kontor.inventory.ops/revalue-inventory!)))))))

;; ============================================================================
;; PENDING(NEW) (7) — no GR-IR bill-vs-receipt true-up split by on-hand/consumed
;; ============================================================================
;;
;; Receive 100 @ 12.00 (GR-IR credited 1200). Issue 40 (COGS 480; 60 on
;; hand). The vendor bill then lands at 13.00/unit → total price difference
;; = 100 · (13 − 12) = 100.00. IAS 2 / perpetual costing require the
;; variance to be SPLIT by where the goods are now:
;;   60 still on hand → +60.00 revalues inventory (a :layer-adjustment)
;;   40 consumed      → +40.00 lands in COGS / price-variance
;; kontor ships only `true-up-negative-fill!`, which (a) applies solely to
;; negative-fill layers, and (b) books the FULL delta to one
;; inventory-account / variance-account pair with NO on-hand-vs-consumed
;; split (ops.clj:435-:487). There is no general GR-IR reconciliation verb.
;;
;; Odoo splits the price difference across still-on-hand vs already-out
;; quantities via the remaining_qty on FIFO valuation layers
;; (stock_account/models/stock_valuation_layer.py:527 _run_fifo /
;; account_move price-difference posting).

(deftest ^:kaocha/pending gr-ir-price-diff-trueup-split-absent
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (ops/issue! conn {:product widget :facility wh :book bk
                      :qty 40M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-02"})
    (let [db2 (d/db conn)]
      (testing "60 on hand, GR-IR credited 1200 (setup is real)"
        (is (bd= 60M (valuation/on-hand-qty db2 bk widget)))
        (is (bd= -1200.00M (gl-balance conn "1410"))))
      ;; PENDING(NEW): no builder trues up a vendor bill vs the receipt
      ;; price and splits the variance by on-hand (revalue inventory) vs
      ;; consumed (COGS). true-up-negative-fill! is negative-fill-only and
      ;; does not split.
      (testing "a GR-IR bill/receipt price-diff true-up verb should exist"
        (is (or (some? (resolve 'kontor.inventory.ops/true-up-gr-ir!))
                (some? (resolve 'kontor.inventory.ops/true-up-purchase-price!))))))))
