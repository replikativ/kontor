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
            [kontor.inventory.report :as inv-report]
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
                 {:db/id "acct-lcc" :kontor.account/code "1420" :kontor.account/name "Landed Cost Clearing"
                  :kontor.account/type :liability :kontor.account/active true}
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
  (let [m {:inventory            (acct db "1400")
           :gr-ir-clearing       (acct db "1410")
           :landed-cost-clearing (acct db "1420")
           :cogs                 (acct db "5000")
           :price-variance       (acct db "5900")
           :write-down-expense   (acct db "5910")}]
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
;; CLOSED (4) — AVCO on-hand VALUE drifted from the GL inventory account
;; ============================================================================
;;
;; kontor.inventory's headline guarantee is that the physical and financial
;; views "cannot drift, because they are written together" (ops.clj:9-12).
;; That held for QUANTITY and for FIFO value — but NOT for AVCO value.
;;
;; AVCO book: receive 100 @ 10.00 (value 1000), receive 100 @ 14.00
;; (value 1400), issue 50. The WeightedAverageProvider stamps the issue at
;; the running average 12 (COGS 600 → GL Cr inventory 600), but consumes
;; the cheapest physical layer FIFO-style (50 units off layer-1 @ 10):
;;   GL 1400 inventory = 1000 + 1400 − 600            = 1800.00
;;   layer on-hand-value = 50·10 (layer-1) + 100·14   = 1900.00
;; They diverged by (avg 12 − drawn 10)·50 = 100 — the average premium on
;; the issued units was never carried back into the layer valuation, so the
;; remaining stock was over-valued by 100.
;;
;; FIX (note 198 R3-INV-4): `on-hand-value` now nets what the GL actually
;; relieved — Σ (qty × :unit-cost-at-consumption), the number the costing
;; provider stamped — instead of re-deriving consumption at the layer's own
;; cost. The stamped cost was already recorded; the view just ignored it.
;; This makes subledger == GL hold for EVERY cost method, not only the ones
;; where the two happen to coincide.
;;
;; Odoo has no persistent per-layer cost under AVCO: _run_avco values
;; on-hand at qty × standard_price(avg) = 150 × 12 = 1800, matching the GL.
;; stock_account/models/stock_valuation_layer.py:651 (_run_avco);
;; product avg maintenance at :626-:646.

(deftest avco-onhand-value-no-longer-drifts-from-gl
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
      (testing "layer on-hand value = 1000 + 1400 − 50·12 = 1800.00 (hand-derived)"
        (is (bd= 1800.00M layer-val)))
      (testing "AVCO: subledger valuation equals GL inventory — no drift"
        (is (bd= gl-inv layer-val)))
      (testing "and the implied average is the running average, 1800/150 = 12"
        (is (bd= 12M (.divide ^java.math.BigDecimal layer-val
                              ^java.math.BigDecimal (valuation/on-hand-qty db2 bk widget)
                              4 java.math.RoundingMode/HALF_EVEN)))))))

(deftest avco-passes-the-modules-own-tie-out-report
  ;; `inv-report/valuation-tie-out` is kontor-inventory's own subledger↔GL
  ;; reconciliation — the "my balance-sheet inventory number is wrong"
  ;; detector. It was present the whole time the AVCO drift was live and
  ;; would have reported :difference 100; the detector was right and the
  ;; number it compared against was wrong.
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
    (let [db2 (d/db conn)
          out (inv-report/valuation-tie-out
               conn {:book bk
                     :inventory-account (acct db2 "1400")
                     :commodity (eur db2)})]
      (is (bd= 1800.00M (:subledger out)))
      (is (bd= 1800.00M (:gl out)))
      (is (bd= 0M (:difference out)))
      (is (true? (:ok? out))))))

(deftest avco-full-drain-of-a-layer-keeps-the-books-tied
  ;; The harder AVCO case: draw MORE than the first layer holds, so a layer is
  ;; fully drained while still carrying residual value. Layers with zero
  ;; remaining QUANTITY must still be counted for VALUE, or the drift comes
  ;; straight back.
  ;;   receive 100 @ 10 (1000), receive 100 @ 14 (1400), issue 120 @ avg 12
  ;;   GL 1400 = 1000 + 1400 − 1440 = 960.00
  ;;   layer-1 = 1000 − 100·12 = −200 ; layer-2 = 1400 − 20·12 = 1160
  ;;   total   = 960.00  ✓ (and 80 units on hand × 12 = 960)
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
                      :qty 120M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-03"})
    (let [db2 (d/db conn)]
      (is (bd= 80M (valuation/on-hand-qty db2 bk widget)))
      (is (bd= 960.00M (gl-balance conn "1400")))
      (is (bd= 960.00M (valuation/on-hand-value db2 bk widget))
          "the fully-drained layer's residual value is still counted")
      (is (bd= (gl-balance conn "1400") (valuation/on-hand-value db2 bk widget))))))

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

(deftest landed-cost-allocation-splits-and-capitalises
  (doseq [split [:by-quantity :by-value]]
    (testing (str "split method " split)
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
        (is (= 2 (count (valuation/available-layers (d/db conn) bk widget)))
            "two layers exist to allocate freight across")
        (let [db1 (d/db conn)]
          (ops/apply-landed-cost! conn {:product widget :book bk :amount 300.00M
                                        :split-method split
                                        :commodity (eur db1) :journal (journal db1)
                                        :account-fn (account-fn db1)
                                        :effective-date #inst "2026-03-03"}))
        (let [db2    (d/db conn)
              layers (valuation/all-layers db2 bk widget)]
          (testing "the voucher lands on the layers 200 / 100 (100u vs 50u; 1200 vs 600)"
            (is (bd= 200.00M (valuation/adjustment-total db2 (first layers))))
            (is (bd= 100.00M (valuation/adjustment-total db2 (second layers)))))
          (testing "landed cost is capitalised, not expensed — IAS 2.11"
            (is (bd= 2100.00M (valuation/on-hand-value db2 bk widget))
                "1800 goods + 300 freight")
            (is (bd= 2100.00M (gl-balance conn "1400")))
            (is (bd= (gl-balance conn "1400") (valuation/on-hand-value db2 bk widget))))
          (testing "the contra sits in the landed-cost clearing account"
            (is (bd= -300.00M (gl-balance conn "1420")))))))))

(deftest landed-cost-residue-lands-on-the-last-layer
  ;; 100.00 over three equal layers is 33.333…; a cent stranded in the
  ;; clearing account never clears, so the parts must sum to the voucher.
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (doseq [d [#inst "2026-03-01" #inst "2026-03-02" #inst "2026-03-03"]]
      (ops/receive! conn {:product widget :facility wh :book bk
                          :qty 10M :unit-cost 1.00M :commodity (eur (d/db conn))
                          :journal (journal (d/db conn))
                          :account-fn (account-fn (d/db conn))
                          :effective-date d}))
    (let [db1 (d/db conn)]
      (ops/apply-landed-cost! conn {:product widget :book bk :amount 100.00M
                                    :split-method :equal
                                    :commodity (eur db1) :journal (journal db1)
                                    :account-fn (account-fn db1)
                                    :effective-date #inst "2026-03-04"}))
    (let [db2  (d/db conn)
          adjs (mapv #(valuation/adjustment-total db2 %)
                     (valuation/all-layers db2 bk widget))]
      (is (= [33.33M 33.33M 33.34M] adjs))
      (is (bd= 100.00M (reduce + 0M adjs)) "the voucher is fully allocated")
      (is (bd= -100.00M (gl-balance conn "1420"))
          "the clearing credit equals the allocated total — no stranded cent"))))

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

(deftest nrv-write-down-books-gl-and-layer-together
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
    (let [db1 (d/db conn)]
      (ops/write-down-to-nrv! conn {:product widget :book bk :nrv-unit-cost 9.00M
                                    :commodity (eur db1) :journal (journal db1)
                                    :account-fn (account-fn db1)
                                    :effective-date #inst "2026-03-05"}))
    (let [db2 (d/db conn)]
      (testing "carried at the lower of cost (12) and NRV (9) — IAS 2.9"
        (is (bd= 900.00M (valuation/on-hand-value db2 bk widget))
            "100 × 9.00"))
      (testing "the GL moves with it, in the same transaction"
        (is (bd= 900.00M (gl-balance conn "1400")))
        (is (bd= 300.00M (gl-balance conn "5910")) "100 × (12 − 9)")
        (is (bd= (gl-balance conn "1400") (valuation/on-hand-value db2 bk widget))))
      (testing "quantity is untouched — this is a value event, not a movement"
        (is (bd= 100M (valuation/on-hand-qty db2 bk widget)))))))

(deftest nrv-write-up-above-cost-is-refused
  ;; IAS 2.9 is lower-of-cost-and-NRV; a recovery above carrying cost is not a
  ;; write-up. IAS 2.33 permits REVERSING a prior write-down up to original
  ;; cost, which needs write-down history this verb does not read — refusing
  ;; beats silently booking an unsupported gain.
  (let [conn   (bootstrap)
        wh     (warehouse! conn)
        db     (d/db conn)
        widget (p db "P-widget")
        bk     (book db "primary")]
    (ops/receive! conn {:product widget :facility wh :book bk
                        :qty 100M :unit-cost 12.00M :commodity (eur db)
                        :journal (journal db) :account-fn (account-fn db)
                        :effective-date #inst "2026-03-01"})
    (let [db1 (d/db conn)
          ex  (try (ops/write-down-to-nrv!
                    conn {:product widget :book bk :nrv-unit-cost 15.00M
                          :commodity (eur db1) :journal (journal db1)
                          :account-fn (account-fn db1)
                          :effective-date #inst "2026-03-05"})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "NRV above cost must be refused")
      (is (= :inventory/nrv-above-cost (:type (ex-data ex))))
      (is (bd= 1200.00M (valuation/on-hand-value (d/db conn) bk widget))
          "and nothing was written"))))

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

(deftest gr-ir-price-diff-trueup-splits-by-on-hand-vs-consumed
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
      ;; The vendor bill lands at 13.00/unit → 100 × (13 − 12) = 100.00 total
      ;; variance, split by where the goods are NOW:
      ;;   60 still on hand → +60.00 revalues inventory (a :layer-adjustment)
      ;;   40 consumed      → +40.00 is a period cost in COGS
      (let [layer (first (valuation/all-layers db2 bk widget))]
        (ops/true-up-gr-ir! conn {:layer layer :billed-unit-cost 13.00M
                                  :commodity (eur db2) :journal (journal db2)
                                  :account-fn (account-fn db2)
                                  :effective-date #inst "2026-03-03"}))
      (let [db3 (d/db conn)]
        (testing "the still-on-hand share revalues the stock"
          (is (bd= 60.00M (valuation/adjustment-total db3
                                                      (first (valuation/all-layers db3 bk widget)))))
          (is (bd= 780.00M (valuation/on-hand-value db3 bk widget))
              "720 at cost + 60 price difference"))
        (testing "the already-consumed share is a period cost, not capitalised"
          (is (bd= 520.00M (gl-balance conn "5000")) "480 at 12.00 + 40 true-up"))
        (testing "GR-IR now carries the billed value, ready for the AP invoice"
          (is (bd= -1300.00M (gl-balance conn "1410")) "100 × 13.00"))
        (testing "and subledger still ties to the GL"
          (is (bd= 780.00M (gl-balance conn "1400")))
          (is (bd= (gl-balance conn "1400") (valuation/on-hand-value db3 bk widget))))))))

(deftest gr-ir-trueup-on-fully-consumed-stock-is-all-cogs
  ;; The degenerate end of the split: nothing is left to revalue, so the whole
  ;; variance is a period cost. Capitalising any of it onto stock that no
  ;; longer exists would overstate inventory indefinitely.
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
                      :qty 100M :commodity (eur (d/db conn))
                      :journal (journal (d/db conn)) :account-fn (account-fn (d/db conn))
                      :effective-date #inst "2026-03-02"})
    (let [db2   (d/db conn)
          layer (first (valuation/all-layers db2 bk widget))]
      (ops/true-up-gr-ir! conn {:layer layer :billed-unit-cost 13.00M
                                :commodity (eur db2) :journal (journal db2)
                                :account-fn (account-fn db2)
                                :effective-date #inst "2026-03-03"})
      (let [db3 (d/db conn)]
        (is (bd= 0M (valuation/adjustment-total db3 layer)) "nothing capitalised")
        (is (bd= 1300.00M (gl-balance conn "5000")) "1200 + the full 100 variance")
        (is (bd= 0M (gl-balance conn "1400")))
        (is (bd= 0M (valuation/on-hand-value db3 bk widget)))))))
