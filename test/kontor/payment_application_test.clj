(ns kontor.payment-application-test
  "Tests for ADR-043 :payment-application kernel primitive."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.payment-application :as papp]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    ;; The :invoice/status transitions seeded by the companion schema
    ;; install. Kernel itself doesn't ship :invoice/status state
    ;; machine seeds; ADR-036/043 land them in modules/invoice/.
    (inv-schema/install! *conn*)
    (d/transact *conn*
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.partner/external-id "ACME"
                  :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer}
                 {:kontor.partner/external-id "OWN"
                  :kontor.partner/name "Self GmbH"
                  :kontor.partner/kind :company}
                 ;; A fake :kontor.audit/create-uid for actor refs.
                 {:db/id "actor-1"
                  :kontor.audit/create-uid "alice@example"}])
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- make-invoice!
  "Create a minimal :sent invoice with one line totaling `gross` EUR.
   Returns the invoice eid."
  [external-id gross]
  (let [db (d/db *conn*)
        eur (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] db)
        seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "OWN"]] db)
        buyer  (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]] db)
        invoice-tempid "inv-1"
        line-tempid "line-1"]
    (d/transact *conn*
                [{:db/id invoice-tempid
                  :invoice/external-id external-id
                  :invoice/type :sales
                  :invoice/status :sent
                  :invoice/issue-date #inst "2026-04-01"
                  :invoice/seller seller
                  :invoice/buyer  buyer
                  :invoice/currency "EUR"
                  :invoice/total-gross gross
                  :invoice/lines [line-tempid]}
                 {:db/id line-tempid
                  :invoice-line/invoice invoice-tempid
                  :invoice-line/sequence 1
                  :invoice-line/name "Widget"
                  :invoice-line/quantity 1M
                  :invoice-line/unit-price gross
                  :invoice-line/amount gross}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :invoice/external-id ?xid]]
         (d/db *conn*) external-id)))

(defn- make-payment!
  "Create a posted :transaction with external-id that represents a
   cash receipt. Returns the tx eid. Per-test makes unique external-id
   so multiple receipts can coexist."
  [external-id]
  (let [db (d/db *conn*)
        buyer (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]] db)]
    (d/transact *conn*
                [{:kontor.transaction/external-id external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/effective-date #inst "2026-05-01"
                  :kontor.transaction/posted-at #inst "2026-05-01"
                  :kontor.transaction/partner buyer}])
    (d/q '[:find ?e . :in $ ?xid
           :where [?e :kontor.transaction/external-id ?xid]]
         (d/db *conn*) external-id)))

(defn- actor [] "actor-1")
(defn- eur [] (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db *conn*)))

;; ============================================================================
;; Full payment
;; ============================================================================

(deftest full-payment-flips-sent-to-paid
  (testing "Single application equal to invoice gross transitions
            :sent → :paid + writes status-history"
    (let [inv (make-invoice! "INV-A" 100M)
          pay (make-payment! "PAY-A")]
      (papp/apply-payment! *conn*
                           {:payment pay
                            :invoice inv
                            :amount 100M
                            :commodity (eur)
                            :applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (testing ":invoice/status → :paid"
          (is (= :paid (sm/current-status db inv :invoice/status))))
        (testing "open-amount = 0"
          (is (= 0 (.compareTo 0M (papp/open-amount-of-invoice db inv)))))
        (testing "applications-of returns the row"
          (is (= 1 (count (papp/applications-of db inv)))))))))

;; ============================================================================
;; Partial payment
;; ============================================================================

(deftest first-partial-application-flips-sent-to-partially-paid
  (testing "Application < gross moves to :partially-paid; subsequent
            applications stay :partially-paid; final closes to :paid"
    (let [inv (make-invoice! "INV-B" 1000M)
          pay (make-payment! "PAY-B")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 400M
                            :commodity (eur) :applied-by-uid (actor)
                            :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (testing "first partial → :partially-paid"
          (is (= :partially-paid (sm/current-status db inv :invoice/status))))
        (testing "open = 600"
          (is (= 0 (.compareTo 600M (papp/open-amount-of-invoice db inv))))))
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 300M
                            :commodity (eur) :applied-by-uid (actor)
                            :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (testing "second partial → still :partially-paid"
          (is (= :partially-paid (sm/current-status db inv :invoice/status))))
        (testing "open = 300"
          (is (= 0 (.compareTo 300M (papp/open-amount-of-invoice db inv))))))
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 300M
                            :commodity (eur) :applied-by-uid (actor)
                            :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (testing "final closes to :paid"
          (is (= :paid (sm/current-status db inv :invoice/status))))
        (testing "open = 0"
          (is (= 0 (.compareTo 0M (papp/open-amount-of-invoice db inv)))))))))

;; ============================================================================
;; Reversal
;; ============================================================================

(deftest reverse-application-reopens-invoice
  (testing "Reversal of the only application moves :paid → :sent"
    (let [inv (make-invoice! "INV-C" 500M)
          pay (make-payment! "PAY-C")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 500M
                            :commodity (eur) :applied-by-uid (actor)})
      (let [db (d/db *conn*)
            app-eid (-> (papp/applications-of db inv) first :db/id)]
        (papp/reverse-application! *conn*
                                   {:application-eid app-eid
                                    :applied-by-uid (actor)
                                    :reason :allocation-correction
                                    :reason-note "wrong invoice"})
        (let [db2 (d/db *conn*)]
          (testing ":invoice/status → :sent"
            (is (= :sent (sm/current-status db2 inv :invoice/status))))
          (testing "two application rows (forward + reversal)"
            (is (= 2 (count (papp/applications-of db2 inv)))))
          (testing "net applied = 0"
            (is (= 0 (.compareTo 0M (papp/applied-amount-of-invoice db2 inv)))))
          (testing "open = 500 (back to full gross)"
            (is (= 0 (.compareTo 500M (papp/open-amount-of-invoice db2 inv))))))))))

(deftest reverse-only-partial-flips-partially-paid-to-sent
  (testing "P0-2 fix: reversal of the ONLY partial on a :partially-
            paid invoice flips status back to :sent"
    (let [inv (make-invoice! "INV-RP1" 600M)
          pay (make-payment! "PAY-RP1")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 200M
                            :commodity (eur) :applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (is (= :partially-paid (sm/current-status db inv :invoice/status))))
      (let [app-eid (-> (papp/applications-of (d/db *conn*) inv) first :db/id)]
        (papp/reverse-application! *conn*
                                   {:application-eid app-eid
                                    :applied-by-uid (actor)
                                    :reason :allocation-correction}))
      (let [db (d/db *conn*)]
        (testing ":partially-paid → :sent after last reversal"
          (is (= :sent (sm/current-status db inv :invoice/status))))
        (testing "net applied = 0"
          (is (= 0 (.compareTo 0M (papp/applied-amount-of-invoice db inv)))))
        (testing "open = 600 (back to full gross)"
          (is (= 0 (.compareTo 600M (papp/open-amount-of-invoice db inv)))))))))

(deftest reverse-partial-keeps-partially-paid-when-others-remain
  (testing "Reverse one of two partials → stays :partially-paid"
    (let [inv (make-invoice! "INV-D" 1000M)
          pay (make-payment! "PAY-B")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 400M
                            :commodity (eur) :applied-by-uid (actor)})
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 600M
                            :commodity (eur) :applied-by-uid (actor)})
      ;; Now :paid. Reverse the second (600).
      (let [apps (papp/applications-of (d/db *conn*) inv)
            second-app (-> apps last :db/id)]
        (papp/reverse-application! *conn*
                                   {:application-eid second-app
                                    :applied-by-uid (actor)})
        (let [db (d/db *conn*)]
          (testing ":paid → :partially-paid"
            (is (= :partially-paid (sm/current-status db inv :invoice/status))))
          (testing "net applied = 400; open = 600"
            (is (= 0 (.compareTo 400M (papp/applied-amount-of-invoice db inv))))
            (is (= 0 (.compareTo 600M (papp/open-amount-of-invoice db inv))))))))))

;; ============================================================================
;; Bitemporal
;; ============================================================================

(deftest bitemporal-applications-honor-as-of-valid
  (testing "applications-of with :as-of-valid earlier than the
            application's :applied-at excludes it"
    (let [inv (make-invoice! "INV-E" 200M)
          pay (make-payment! "PAY-D")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 200M
                            :commodity (eur) :applied-by-uid (actor)
                            :applied-at #inst "2026-05-10"})
      (let [db (d/db *conn*)]
        (testing "as-of-valid 2026-05-09: no applications visible"
          (is (= 0 (count (papp/applications-of
                           db inv {:as-of-valid #inst "2026-05-09"})))))
        (testing "as-of-valid 2026-05-11: one application visible"
          (is (= 1 (count (papp/applications-of
                           db inv {:as-of-valid #inst "2026-05-11"})))))
        (testing "open-amount with :as-of-valid 2026-05-09 = 200 (gross)"
          (is (= 0 (.compareTo
                    200M (papp/open-amount-of-invoice
                          db inv {:as-of-valid #inst "2026-05-09"})))))
        (testing "open-amount with :as-of-valid 2026-05-11 = 0"
          (is (= 0 (.compareTo
                    0M (papp/open-amount-of-invoice
                        db inv {:as-of-valid #inst "2026-05-11"})))))))))

;; ============================================================================
;; FIFO allocation
;; ============================================================================

(deftest allocate-fifo-distributes-across-oldest-first
  (testing "allocate-fifo! covers the oldest invoices first; partial
            on the next when cash runs out"
    (let [inv-old (make-invoice! "INV-OLD" 300M)
          _ (d/transact *conn*
                        [{:kontor.transaction/external-id "T-OLD"
                          :kontor.transaction/state :posted
                          :kontor.transaction/effective-date #inst "2026-04-01"
                          :kontor.transaction/due-date #inst "2026-04-30"}])
          _ (d/transact *conn*
                        [{:db/id inv-old
                          :invoice/transaction
                          [:kontor.transaction/external-id "T-OLD"]}])
          inv-new (make-invoice! "INV-NEW" 500M)
          _ (d/transact *conn*
                        [{:kontor.transaction/external-id "T-NEW"
                          :kontor.transaction/state :posted
                          :kontor.transaction/effective-date #inst "2026-04-15"
                          :kontor.transaction/due-date #inst "2026-05-15"}])
          _ (d/transact *conn*
                        [{:db/id inv-new
                          :invoice/transaction
                          [:kontor.transaction/external-id "T-NEW"]}])
          pay     (make-payment! "PAY-E")
          buyer   (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]]
                       (d/db *conn*))]
      (let [allocs (papp/allocate-fifo! *conn*
                                        {:payment pay
                                         :partner buyer
                                         :total-amount 600M
                                         :commodity (eur)
                                         :applied-by-uid (actor)})]
        (testing "two allocations"
          (is (= 2 (count allocs))))
        (let [db (d/db *conn*)]
          (testing "INV-OLD fully paid (got 300)"
            (is (= :paid (sm/current-status db inv-old :invoice/status)))
            (is (= 0 (.compareTo 0M (papp/open-amount-of-invoice db inv-old)))))
          (testing "INV-NEW partially paid (got 300 of 500; open = 200)"
            (is (= :partially-paid (sm/current-status db inv-new :invoice/status)))
            (is (= 0 (.compareTo 200M (papp/open-amount-of-invoice db inv-new))))))))))

;; NOTE: unapplied-cash-balance query is deferred to the Stage L
;; companion (kontor-collections). The kernel does not derive cash-
;; received from postings without coupling to chart-of-accounts.

;; ============================================================================
;; Bitemporal interop (kontor.bitemporal)
;; ============================================================================

(deftest invoice-status-at-vt-tracks-backdated-applications
  (testing "Backdated apply-payment! stamps :db.valid/from on the
            status-history; invoice-status-at resolves correctly"
    ;; :db.valid/{from,to} are pre-installed by datahike's
    ;; feature/bitemporal-v1 — no schema install needed.
    (let [inv (make-invoice! "INV-VT" 1000M)
          pay (make-payment! "PAY-VT")]
      ;; Apply 1000 backdated to 2026-03-15 — invoice should be :paid as of that vt
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 1000M
                            :commodity (eur) :applied-by-uid (actor)
                            :applied-at #inst "2026-03-15"})
      (let [db (d/db *conn*)]
        (testing "value-at before applied-at — nil (no assertion's vt covers Mar 14;
                  invoice's :sent was real-time so vt-from = tx-time = today)"
          (is (nil? (papp/invoice-status-at db inv #inst "2026-03-14"))))
        (testing "value-at on / after applied-at — :paid (the backdated correction)"
          (is (= :paid (papp/invoice-status-at db inv #inst "2026-03-15")))
          (is (= :paid (papp/invoice-status-at db inv #inst "2026-04-01"))))
        (testing "value-at far future — :paid (latest tx-time wins)"
          (is (= :paid (papp/invoice-status-at db inv #inst "2030-01-01"))))))))
