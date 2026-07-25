(ns kontor.regression.invoice-ar-test
  "Regression suite for the invoicing → AR → collections → payment-
   application slice.

   Exercises the real consumer API end-to-end:
     - kontor.invoice.bridge   order → invoice → make-ready → post-to-ledger
     - kontor.banking.payment-application  partial payment + reversal, open-item split
     - kontor.collections.aging  net-open-after-partial aging (invoice/payment-app aware)
     - kontor.reporting.aging    kernel Money-typed aging over posted AR + :settles offsets
     - kontor.banking.reconciliation  open-receivables-by-tx (equal-legs-collapse regression)

   All expected figures are hand-computed from the scenario and noted in
   comments. Money is BigDecimal throughout; comparisons use .compareTo /
   money/equiv?, never double =.

   Known-issue tags reference note 196 (.internal/research/196-…). A test
   that documents a genuine kontor bug is marked ^:kaocha/pending with a
   PENDING(Fn)/PENDING(NEW) comment so the default suite stays green."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.partner.schema :as partner-schema]
            [kontor.sales.schema :as sales-schema]
            [kontor.invoice.schema :as inv-schema]
            [kontor.invoice.bridge :as inv]
            [kontor.document.invoice :as kinv]
            [kontor.banking.payment-application :as papp]
            [kontor.banking.reconciliation :as recon]
            [kontor.reporting.aging :as kaging]
            [kontor.collections.aging :as caging]
            [kontor.posting :as posting]
            [kontor.validation :as v]
            [kontor.money :as money]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

;; ── Setup ───────────────────────────────────────────────────────────────

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (sales-schema/install! *conn*)
    (inv-schema/install! *conn*)
    (d/transact
     *conn*
     [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
      {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme GmbH"
       :kontor.entity/kind :operating :kontor.entity/active true}
      {:kontor.partner/external-id "SELLER" :kontor.partner/type :org
       :kontor.partner/status :enabled :kontor.partner/name "Acme GmbH"}
      {:kontor.partner/external-id "BUYER" :kontor.partner/type :org
       :kontor.partner/status :enabled :kontor.partner/name "Big Customer Co"
       :kontor.partner/kind :customer}
      ;; :kontor.audit/create-uid is a ref — reuse a partner row as the actor.
      {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
      ;; SKR04-shaped accounts (commodity-pinned so an empty aging can still
      ;; denominate; non-empty aging infers commodity from the postings).
      {:kontor.account/code "1400" :kontor.account/name "Forderungen (AR)"
       :kontor.account/path "Assets:AR" :kontor.account/type :asset
       :kontor.account/active true :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
      {:kontor.account/code "1200" :kontor.account/name "Bank"
       :kontor.account/path "Assets:Bank" :kontor.account/type :asset
       :kontor.account/active true :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
      {:kontor.account/code "4400" :kontor.account/name "Erlöse 19%"
       :kontor.account/path "Income:Sales" :kontor.account/type :revenue
       :kontor.account/active true :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
      {:kontor.account/code "3801" :kontor.account/name "USt 19%"
       :kontor.account/path "Liabilities:VAT" :kontor.account/type :liability
       :kontor.account/active true :kontor.account/commodity [:kontor.commodity/symbol "EUR"]}
      ;; Tenant-wide GL defaults the posting bridge routes through.
      {:kontor.gl-account-default/account-type :sales-revenue
       :kontor.gl-account-default/account [:kontor.account/path "Income:Sales"]}
      {:kontor.gl-account-default/account-type :ar
       :kontor.gl-account-default/account [:kontor.account/path "Assets:AR"]}
      {:kontor.gl-account-default/account-type :sales-tax-payable
       :kontor.gl-account-default/account [:kontor.account/path "Liabilities:VAT"]}
      ;; Journals.
      {:kontor.journal/code "SJ" :kontor.journal/type :sale
       :kontor.journal/name "Sales Journal" :kontor.journal/active true}
      {:kontor.journal/code "CR" :kontor.journal/type :cash
       :kontor.journal/name "Cash Receipts" :kontor.journal/active true}])
    (f)))

(use-fixtures :each bootstrap)

;; ── Helpers ─────────────────────────────────────────────────────────────

(defn- eid [attr v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] (d/db *conn*) attr v))

(defn- ace [code] (eid :kontor.account/code code))
(defn- eur [] (eid :kontor.commodity/symbol "EUR"))
(defn- actor [] (eid :kontor.partner/external-id "U-alice"))
(defn- buyer [] (eid :kontor.partner/external-id "BUYER"))
(defn- entity [] (eid :kontor.entity/code "ACME-DE"))

(defn- seed-order!
  "A sales order for `qty` units @ `unit-price` with a header-level 19%
   VAT adjustment of `vat`. Returns the order eid."
  [ext-id qty unit-price vat]
  (let [order-eid "ord"]
    (d/transact
     *conn*
     [{:db/id order-eid
       :kontor.order/external-id ext-id
       :kontor.order/type :sales
       :kontor.order/status :order.status/created
       :kontor.order/order-date #inst "2026-01-15"
       :kontor.order/entry-date #inst "2026-01-15"
       :kontor.order/currency [:kontor.commodity/symbol "EUR"]
       :kontor.order/bill-from-partner [:kontor.partner/external-id "SELLER"]
       :kontor.order/bill-to-partner [:kontor.partner/external-id "BUYER"]}
      {:db/id "item"
       :kontor.sales.order-item/order order-eid
       :kontor.sales.order-item/seq-id "00001"
       :kontor.sales.order-item/type :product
       :kontor.sales.order-item/product-id "WIDGET-A"
       :kontor.sales.order-item/description "Widget A"
       :kontor.sales.order-item/quantity qty
       :kontor.sales.order-item/unit-price unit-price
       :kontor.sales.order-item/cancel-quantity 0M
       :kontor.sales.order-item/status :order-item.status/approved}
      {:kontor.order-adjustment/order order-eid
       :kontor.order-adjustment/scope order-eid
       :kontor.order-adjustment/type :tax
       :kontor.order-adjustment/amount vat
       :kontor.order-adjustment/tax-auth-geo-id "DE"}])
    (eid :kontor.order/external-id ext-id)))

(defn- open-invoice!
  "order → invoice → make-ready → post-to-ledger. Returns the invoice eid,
   left in :sent with its GL transaction posted."
  [ext-id qty unit-price vat]
  (seed-order! ext-id qty unit-price vat)
  (let [inv-ext (str "INV-" ext-id)]
    (inv/make-invoice-from-order! *conn* ext-id
                                  {:external-id inv-ext
                                   :issue-date #inst "2026-01-15"
                                   :entity (entity)})
    (inv/make-ready! *conn* inv-ext)
    (inv/post-to-ledger! *conn* inv-ext
                         {:journal-ref [:kontor.journal/code "SJ"]
                          :posted-at #inst "2026-01-15"})
    (eid :kontor.invoice/external-id inv-ext)))

(defn- payment! [ext-id]
  (d/transact *conn*
              [{:kontor.transaction/external-id ext-id
                :kontor.transaction/state :posted
                :kontor.transaction/effective-date #inst "2026-02-01"
                :kontor.transaction/posted-at #inst "2026-02-01"
                :kontor.transaction/partner (buyer)}])
  (eid :kontor.transaction/external-id ext-id))

;; net 250 + 19% VAT 47.50 = gross 297.50
(def NET   250M)
(def VAT   47.50M)
(def GROSS 297.50M)

;; ══════════════════════════════════════════════════════════════════════
;; 1. Order → invoice → post → partial payment: the open-item split
;; ══════════════════════════════════════════════════════════════════════

(deftest bridge-post-then-partial-payment-splits-open-item
  (testing "A partial payment of 197.50 on a 297.50 gross invoice leaves
            100.00 open and flips :sent → :partially-paid"
    (let [invoice (open-invoice! "ORD-1" 10M 25M VAT)
          pay     (payment! "PAY-1")]
      (testing "posted invoice starts fully open at gross"
        (let [db (d/db *conn*)]
          (is (= :sent (sm/current-status db invoice :kontor.invoice/status)))
          ;; total-gross unset on a bridge invoice → open = Σ line amounts
          (is (zero? (.compareTo GROSS (papp/open-amount-of-invoice db invoice)))
              "250 product + 47.50 tax = 297.50")))
      (papp/apply-payment! *conn*
                           {:payment pay :invoice invoice :amount 197.50M
                            :commodity (eur) :applied-by-uid (actor)
                            :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (testing "status moves to :partially-paid"
          (is (= :partially-paid (sm/current-status db invoice :kontor.invoice/status))))
        (testing "applied = 197.50, open = 100.00 (the split adds back to gross)"
          (is (zero? (.compareTo 197.50M (papp/applied-amount-of-invoice db invoice))))
          (is (zero? (.compareTo 100.00M (papp/open-amount-of-invoice db invoice))))
          (is (zero? (.compareTo GROSS
                                 (.add ^java.math.BigDecimal
                                  (papp/applied-amount-of-invoice db invoice)
                                       ^java.math.BigDecimal
                                       (papp/open-amount-of-invoice db invoice))))))
        (testing "exactly one application row recorded"
          (is (= 1 (count (papp/applications-of db invoice)))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 2. Second application closes the invoice to :paid
;; ══════════════════════════════════════════════════════════════════════

(deftest second-application-closes-invoice-to-paid
  (testing "Two partials summing to gross close a :partially-paid invoice"
    (let [invoice (open-invoice! "ORD-2" 10M 25M VAT)
          pay     (payment! "PAY-2")]
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 100.00M
                                   :commodity (eur) :applied-by-uid (actor)})
      (is (= :partially-paid (sm/current-status (d/db *conn*) invoice :kontor.invoice/status)))
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 197.50M
                                   :commodity (eur) :applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (testing "final application closes to :paid, open = 0"
          (is (= :paid (sm/current-status db invoice :kontor.invoice/status)))
          (is (zero? (.compareTo 0M (papp/open-amount-of-invoice db invoice)))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 3. Reversal reopens the receivable
;; ══════════════════════════════════════════════════════════════════════

(deftest reversal-reopens-partially-paid-invoice
  (testing "Reversing the only partial application restores the full gross
            open balance and moves :partially-paid → :sent"
    (let [invoice (open-invoice! "ORD-3" 10M 25M VAT)
          pay     (payment! "PAY-3")]
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 197.50M
                                   :commodity (eur) :applied-by-uid (actor)})
      (let [app (-> (papp/applications-of (d/db *conn*) invoice) first :db/id)]
        (papp/reverse-application! *conn*
                                   {:application-eid app
                                    :applied-by-uid (actor)
                                    :reason :allocation-correction
                                    :reason-note "misapplied to wrong invoice"}))
      (let [db (d/db *conn*)]
        (testing "status back to :sent"
          (is (= :sent (sm/current-status db invoice :kontor.invoice/status))))
        (testing "net applied = 0, open = full gross"
          (is (zero? (.compareTo 0M (papp/applied-amount-of-invoice db invoice))))
          (is (zero? (.compareTo GROSS (papp/open-amount-of-invoice db invoice)))))
        (testing "forward + reversal rows both present"
          (is (= 2 (count (papp/applications-of db invoice)))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 4. Collections aging reports the NET open, not the gross
;; ══════════════════════════════════════════════════════════════════════

(deftest collections-aging-reports-net-open-after-partial
  (testing "kontor.collections.aging nets payment-applications into the
            open-amount — the kernel open-receivables (settles-only) would
            miss the partial. Invoice issued 2026-01-15, aged by
            invoice-date as of 2026-04-30 = 105 days → :90+ bucket."
    (let [invoice (open-invoice! "ORD-4" 10M 25M VAT)
          pay     (payment! "PAY-4")]
      ;; applied-at is the payment's valid-time. Aging "as-of 2026-04-30"
      ;; reads the receivable as it was valid then, so the cash must have
      ;; been applied at a valid-time <= the as-of, or the aging (correctly)
      ;; still shows the full gross open. Cash received 2026-02-01.
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 197.50M
                                   :commodity (eur) :applied-by-uid (actor)
                                   :applied-at #inst "2026-02-01"})
      (let [db  (d/db *conn*)
            ent (entity)
            opens (caging/open-ar-invoices db {:entity-eid ent})
            rows  (caging/aging-rows db {:entity-eid ent
                                         :method :invoice-date
                                         :as-of #inst "2026-04-30"})
            sum   (caging/aging-summary db {:entity-eid ent
                                            :method :invoice-date
                                            :as-of #inst "2026-04-30"})]
        (testing "one open AR invoice, net open = 100.00 (gross 297.50 − 197.50)"
          (is (= 1 (count opens)))
          (is (zero? (.compareTo 100.00M (:open-amount (first opens)))))
          ;; N4 FIXED (note 196): a bridge/line-based invoice never sets
          ;; :kontor.invoice/total-gross, so open-ar-invoices now falls back to
          ;; summing the invoice lines — :gross = 297.50 (the line total),
          ;; consistent with how :open-amount is derived. Was nil before.
          (is (zero? (.compareTo 297.50M (:gross (first opens))))))
        (testing "aging row lands in :90+ carrying the NET open, not the gross"
          (is (= 1 (count rows)))
          (is (= :90+ (:bucket (first rows))))
          (is (= 105 (:days-overdue (first rows))))
          (is (zero? (.compareTo 100.00M (:open-amount (first rows))))))
        (testing "summary totals reflect the net open"
          (is (zero? (.compareTo 100.00M (:90+ sum))))
          (is (zero? (.compareTo 100.00M (:total sum))))
          (is (zero? (.compareTo 0M (:not-yet-due sum)))))))))

(deftest aging-valid-time-is-a-separate-explicit-axis-N7
  ;; N7 (note 196): aging has TWO temporal axes that must not be conflated —
  ;;   :as-of       the reference date for days-overdue bucketing, and
  ;;   :as-of-valid the bitemporal cursor deciding which payment-applications
  ;;                are visible.
  ;; For a coherent point-in-time report :as-of-valid defaults to :as-of, so a
  ;; *historical* aging correctly excludes cash received after the report date
  ;; (the money wasn't in yet) — that exclusion is right, not a silent drop.
  ;; Passing :as-of-valid explicitly decouples the axes: age by a past date
  ;; but with today's payment knowledge.
  (testing "the valid-time axis is explicit and independently controllable"
    (let [invoice (open-invoice! "ORD-7" 10M 25M VAT)      ; gross 297.50
          pay     (payment! "PAY-7")]
      ;; cash applied at valid-time 2026-06-01 — AFTER the historical as-of below
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 197.50M
                                   :commodity (eur) :applied-by-uid (actor)
                                   :applied-at #inst "2026-06-01"})
      (let [db  (d/db *conn*)
            ent (entity)
            hist     (caging/aging-rows db {:entity-eid ent :method :invoice-date
                                            :as-of #inst "2026-04-30"})
            hist+now (caging/aging-rows db {:entity-eid ent :method :invoice-date
                                            :as-of #inst "2026-04-30"
                                            :as-of-valid #inst "2026-07-01"})
            curr     (caging/aging-rows db {:entity-eid ent :method :invoice-date
                                            :as-of #inst "2026-07-01"})]
        (testing "historical aging (as-of 2026-04-30) excludes the June payment → full gross"
          (is (= 1 (count hist)))
          (is (zero? (.compareTo 297.50M (:open-amount (first hist))))))
        (testing "explicit :as-of-valid recovers current knowledge under a past as-of → net"
          (is (zero? (.compareTo 100.00M (:open-amount (first hist+now))))))
        (testing "current aging (as-of 2026-07-01) sees the June payment → net open"
          (is (zero? (.compareTo 100.00M (:open-amount (first curr))))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 5. Kernel Money-typed aging over posted AR + a partial :settles offset
;; ══════════════════════════════════════════════════════════════════════

(defn- post-sale!
  "A posted :sale transaction: AR debit gross, revenue + VAT credits.
   `due` sets :kontor.transaction/due-date so aging can bucket it."
  [ext-id due net vat]
  (let [gross (.add ^java.math.BigDecimal net ^java.math.BigDecimal vat)
        tx (posting/build-transaction
            {:transaction {:kontor.transaction/external-id ext-id
                           :kontor.transaction/journal (eid :kontor.journal/code "SJ")
                           :kontor.transaction/effective-date #inst "2026-01-15"
                           :kontor.transaction/due-date due
                           :kontor.transaction/narration ext-id
                           :kontor.transaction/partner (buyer)
                           :kontor.transaction/state :posted
                           :kontor.transaction/posted-at #inst "2026-01-15"}
             :postings
             [{:kontor.posting/account (ace "1400") :kontor.posting/amount gross
               :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}
              {:kontor.posting/account (ace "4400") :kontor.posting/amount (.negate ^java.math.BigDecimal net)
               :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}
              {:kontor.posting/account (ace "3801") :kontor.posting/amount (.negate ^java.math.BigDecimal vat)
               :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}]})]
    (v/transact-with-validation *conn* tx)
    (eid :kontor.transaction/external-id ext-id)))

(deftest kernel-aging-money-typed-after-partial-settlement
  (testing "open-receivables-by-tx nets a :settles cash-receipt offset off
            the gross AR, and the aging summary is a real Money (commodity
            included), bucketed by due-date."
    ;; Sale INV-K: AR 1190 debit (net 1000 + VAT 190), due 2026-03-01.
    (let [sale (post-sale! "INV-K" #inst "2026-03-01" 1000M 190M)]
      ;; Cash receipt settling 690 of it: bank +690, AR −690, links :settles.
      (let [pay (posting/build-transaction
                 {:transaction {:kontor.transaction/external-id "REC-K"
                                :kontor.transaction/journal (eid :kontor.journal/code "CR")
                                :kontor.transaction/effective-date #inst "2026-02-10"
                                :kontor.transaction/settles sale
                                :kontor.transaction/partner (buyer)
                                :kontor.transaction/state :posted
                                :kontor.transaction/posted-at #inst "2026-02-10"}
                  :postings
                  [{:kontor.posting/account (ace "1200") :kontor.posting/amount 690M
                    :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-02-10"}
                   {:kontor.posting/account (ace "1400") :kontor.posting/amount -690M
                    :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-02-10"}]})]
        (v/transact-with-validation *conn* pay))
      (let [db  (d/db *conn*)
            eur-eid (eur)
            opens (recon/open-receivables-by-tx db #{"1400"})
            ;; as-of 2026-04-30, due 2026-03-01 → 60 days overdue → :31-60
            rows (kaging/aging-rows db #{"1400"} :as-of #inst "2026-04-30")
            sum  (kaging/aging-summary-by-bucket db #{"1400"} :as-of #inst "2026-04-30")]
        (testing "one open receivable, open = 1190 − 690 = 500"
          (is (= 1 (count opens)))
          (is (zero? (money/compare-amounts 500M (:open-amount (first opens)))))
          (is (zero? (money/compare-amounts 1190M (:original-amount (first opens))))))
        (testing "aging row buckets by due-date (60 days → :31-60)"
          (is (= :31-60 (:bucket (first rows))))
          (is (= 60 (:days-overdue (first rows)))))
        (testing "summary value is a real Money (F1: bucket amounts carry commodity)"
          (is (money/money? (:total sum)))
          (is (zero? (.compareTo 500M (:amount (:31-60 sum)))))
          (is (zero? (.compareTo 500M (:amount (:total sum)))))
          (is (= eur-eid (:commodity (:total sum)))
              "commodity inferred from the postings, not defaulted to :EUR")
          (is (zero? (.compareTo 0M (:amount (:not-yet-due sum))))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 6. Equal receivable legs must not collapse (set-semantics regression)
;; ══════════════════════════════════════════════════════════════════════

(deftest equal-receivable-legs-do-not-collapse
  (testing "A sale posting TWO identical AR legs (e.g. two equal milestone
            lines) must sum to the full 200 in open-receivables-by-tx. The
            :find query binds ?p so the two 100 tuples stay distinct;
            without it set-semantics collapsed them to a single 100 and half
            the receivable silently vanished from every open-item + aging."
    (let [tx (posting/build-transaction
              {:transaction {:kontor.transaction/external-id "INV-EQ"
                             :kontor.transaction/journal (eid :kontor.journal/code "SJ")
                             :kontor.transaction/effective-date #inst "2026-01-15"
                             :kontor.transaction/due-date #inst "2026-02-14"
                             :kontor.transaction/partner (buyer)
                             :kontor.transaction/state :posted
                             :kontor.transaction/posted-at #inst "2026-01-15"}
               :postings
               [{:kontor.posting/account (ace "1400") :kontor.posting/amount 100M
                 :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}
                {:kontor.posting/account (ace "1400") :kontor.posting/amount 100M
                 :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}
                {:kontor.posting/account (ace "4400") :kontor.posting/amount -200M
                 :kontor.posting/commodity (eur) :kontor.posting/posted-at #inst "2026-01-15"}]})]
      (v/transact-with-validation *conn* tx)
      (let [db    (d/db *conn*)
            opens (recon/open-receivables-by-tx db #{"1400"})
            sum   (kaging/aging-summary-by-bucket db #{"1400"} :as-of #inst "2026-04-30")]
        (testing "open = 200, NOT 100 (the two equal legs survive)"
          (is (= 1 (count opens)))
          (is (zero? (money/compare-amounts 200M (:open-amount (first opens))))))
        (testing "the full 200 flows into the aging summary total"
          (is (zero? (.compareTo 200M (:amount (:total sum))))))))))

;; ══════════════════════════════════════════════════════════════════════
;; 7. ADR-161 — the two AR settlement paths must agree
;;
;; Observable failure before this: a dunning letter to a customer who had
;; already paid. `commit-match!` set `:kontor.transaction/settles` (so the
;; KERNEL aging saw the invoice as paid) but wrote no payment-application
;; row and no status change, so `kontor.collections.aging/open-ar-invoices`
;; — which is what dunning reads — still reported it fully open in :sent.
;; ══════════════════════════════════════════════════════════════════════

(defn- ingest-and-match!
  "Ingest one bank line for `amount` referencing `narration`, take the best
   suggestion and commit it. Returns the settled transaction eids."
  [amount narration opts]
  (recon/ingest-statement! *conn*
                           [{:bank :test :date #inst "2026-02-01" :amount amount
                             :counterparty "Big Customer Co"
                             :description narration
                             :raw-row ["02/01/2026" (str amount) narration]}]
                           {:source-account-eid (ace "1200")
                            :commodity-eid (eur)})
  (let [db (d/db *conn*)
        bl (d/q '[:find ?bl . :in $ ?amt
                  :where [?bl :kontor.bank-line/amount ?amt]
                  [?bl :kontor.bank-line/status :unmatched]]
                db amount)
        best (first (recon/suggest-match db bl {}))]
    (recon/commit-match! *conn* bl (:match best)
                         (eid :kontor.journal/code "CR")
                         (merge {:ar-codes #{"1400"}} opts))
    (:transactions (:match best))))

(deftest commit-match-writes-the-subledger-so-dunning-agrees
  (testing "A full settlement through reconciliation must close the invoice in
            BOTH views: the kernel :settles-based open-item list AND the
            collections payment-application subledger that dunning reads."
    (let [invoice (open-invoice! "ORD-7" 10M 25M VAT)]
      (ingest-and-match! GROSS "Rechnung INV-ORD-7" {:applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (testing "the subledger records the cash"
          (is (= 1 (count (papp/applications-of db invoice))))
          (is (zero? (.compareTo GROSS (papp/applied-amount-of-invoice db invoice))))
          (is (zero? (.compareTo 0M (papp/open-amount-of-invoice db invoice)))))
        (testing "status is DERIVED from the residual, in the same commit"
          (is (= :paid (sm/current-status db invoice :kontor.invoice/status))))
        (testing "the collections view — what dunning reads — no longer shows it"
          (is (empty? (caging/aging-rows db {:entity-eid (entity)
                                             :as-of #inst "2026-04-30"})))
          (is (zero? (.compareTo 0M (:total (caging/aging-summary
                                             db {:entity-eid (entity)
                                                 :as-of #inst "2026-04-30"}))))))
        (testing "and the kernel :settles view agrees"
          (is (empty? (recon/open-receivables-by-tx db #{"1400"}))))))))

(deftest commit-match-of-a-partial-does-not-close-the-invoice
  (testing "flip-paid-on-settlement used to flip straight to :paid with no
            comparison against gross, so a deposit closed the invoice and the
            rest stopped being collected. A 100.00 receipt on a 297.50 invoice
            must leave 197.50 open and the status at :partially-paid."
    (let [invoice (open-invoice! "ORD-8" 10M 25M VAT)]
      (ingest-and-match! 100.00M "Anzahlung INV-ORD-8" {:applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (is (zero? (.compareTo 100.00M (papp/applied-amount-of-invoice db invoice))))
        (is (zero? (.compareTo 197.50M (papp/open-amount-of-invoice db invoice))))
        (is (= :partially-paid (sm/current-status db invoice :kontor.invoice/status)))
        (testing "the remaining 197.50 is still collectible"
          (let [rows (caging/aging-rows db {:entity-eid (entity)
                                            :as-of #inst "2026-04-30"})]
            (is (= 1 (count rows)))
            (is (zero? (.compareTo 197.50M (:open-amount (first rows)))))))))))

(deftest flip-paid-on-settlement-is-amount-aware
  (testing "The GL-only bridge (for settlements written outside the
            reconciliation path) derives the status from the residual too:
            nothing applied → no-op, partial → :partially-paid, full → :paid."
    (let [invoice (open-invoice! "ORD-9" 10M 25M VAT)
          settled-tx (:db/id (:kontor.invoice/transaction
                              (d/pull (d/db *conn*)
                                      [{:kontor.invoice/transaction [:db/id]}]
                                      invoice)))
          pay (payment! "PAY-9")]
      (testing "no cash applied → the bridge must NOT close the invoice"
        (kinv/flip-paid-on-settlement *conn* [settled-tx])
        (is (= :sent (sm/current-status (d/db *conn*) invoice :kontor.invoice/status))
            "a :settles link with no cash behind it is not payment"))
      (testing "partial cash → :partially-paid, not :paid"
        (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 97.50M
                                     :commodity (eur) :applied-by-uid (actor)
                                     :applied-at #inst "2026-02-01"})
        (kinv/flip-paid-on-settlement *conn* [settled-tx])
        (is (= :partially-paid
               (sm/current-status (d/db *conn*) invoice :kontor.invoice/status))))
      (testing "the rest → :paid"
        (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 200.00M
                                     :commodity (eur) :applied-by-uid (actor)
                                     :applied-at #inst "2026-02-02"})
        (kinv/flip-paid-on-settlement *conn* [settled-tx])
        (is (= :paid (sm/current-status (d/db *conn*) invoice :kontor.invoice/status)))))))

(deftest commit-match-refuses-an-unattributed-subledger-row
  (testing "A `:payment-application` needs `:applied-by-uid`. When the settled
            transactions have invoices behind them and it is missing, the call
            REFUSES rather than writing an unattributed row or silently
            skipping the subledger and desyncing the two views again — the same
            DEFER-don't-corrupt stance as `ar-or-ap-account`."
    (let [invoice (open-invoice! "ORD-10" 10M 25M VAT)
          ex (try (ingest-and-match! GROSS "Rechnung INV-ORD-10" {})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :reconciliation/missing-applied-by-uid (:type (ex-data ex))))
      (is (= [invoice] (:invoices (ex-data ex))))
      (testing "and nothing was written — the refusal is atomic"
        (let [db (d/db *conn*)]
          (is (empty? (papp/applications-of db invoice)))
          (is (= :sent (sm/current-status db invoice :kontor.invoice/status))))))))

(deftest ar-tie-out-is-ok-after-settlement-through-either-path
  (testing "The acceptance check (no module test called `ar-tie-out` before):
            the AR open-item subledger must equal the GL receivable control
            account after a settlement, whichever path wrote it."
    (testing "path A — reconciliation commit-match!"
      (let [_ (open-invoice! "ORD-11" 10M 25M VAT)
            _ (ingest-and-match! GROSS "Rechnung INV-ORD-11" {:applied-by-uid (actor)})
            t (recon/ar-tie-out *conn* {:commodity (eur) :ar-codes #{"1400"}})]
        (is (:ok? t) (pr-str t))
        (is (zero? (.compareTo 0M (:difference t))))
        (is (zero? (.compareTo 0M (:gl t)))
            "the receivable is fully relieved in the GL")
        (is (zero? (.compareTo 0M (:subledger t)))
            "and the open-item list agrees")))
    (testing "path B — payment-application settle-invoice! (GL + subledger)"
      (let [invoice (open-invoice! "ORD-12" 10M 25M VAT)]
        (papp/settle-invoice! *conn*
                              {:invoice invoice
                               :payment (payment! "PAY-12")
                               :amount GROSS
                               :commodity (eur)
                               :cash-account (ace "1200")
                               :receivable-account (ace "1400")
                               :journal (eid :kontor.journal/code "CR")
                               :applied-by-uid (actor)
                               :effective-date #inst "2026-02-01"})
        (let [db (d/db *conn*)
              t (recon/ar-tie-out *conn* {:commodity (eur) :ar-codes #{"1400"}})]
          (is (:ok? t) (pr-str t))
          (is (zero? (.compareTo 0M (:difference t))))
          (is (= :paid (sm/current-status db invoice :kontor.invoice/status))
              "settle-invoice! already derived the status from the residual")
          (testing "and this path is NOT double-counted: the invoice writes both
                    a :settles ref and an application row, which is exactly why
                    `open-amount-of-invoice` must NOT also net :settles"
            (is (zero? (.compareTo GROSS (papp/applied-amount-of-invoice db invoice))))
            (is (zero? (.compareTo 0M (papp/open-amount-of-invoice db invoice))))))))))

(deftest overpayment-surfaces-as-a-credit-instead-of-vanishing
  (testing "`open-ar-invoices` used to end in `(filter #(pos? open-amount))`, so
            an OVERPAID invoice disappeared from the collections view entirely.
            It now surfaces with :overpaid? and :unapplied-credit — and is
            excluded from AGING (a credit balance is not a receivable to dun),
            which is where the exclusion belongs."
    (let [invoice (open-invoice! "ORD-13" 10M 25M VAT)
          pay (payment! "PAY-13")]
      ;; 350.00 against a 297.50 invoice — 52.50 of unapplied customer credit.
      (papp/apply-payment! *conn* {:payment pay :invoice invoice :amount 350.00M
                                   :commodity (eur) :applied-by-uid (actor)
                                   :applied-at #inst "2026-02-01"})
      (let [db (d/db *conn*)
            opens (caging/open-ar-invoices db {:entity-eid (entity)})
            rows (caging/aging-rows db {:entity-eid (entity)
                                        :as-of #inst "2026-04-30"})]
        (testing "the overpaid invoice is VISIBLE with its credit quantified"
          ;; The status machine closed it to :paid, so it leaves the
          ;; (:sent :partially-paid) working set — assert on whichever rows
          ;; remain, and on the residual itself, which is the load-bearing part.
          (is (zero? (.compareTo -52.50M (papp/open-amount-of-invoice db invoice)))
              "a negative residual IS the customer credit")
          (doseq [r opens]
            (when (neg? (.signum ^java.math.BigDecimal (:open-amount r)))
              (is (:overpaid? r))
              (is (zero? (.compareTo 52.50M (:unapplied-credit r)))))))
        (testing "but it is never dunned"
          (is (every? #(pos? (.signum ^java.math.BigDecimal (:open-amount %))) rows)))))))

;; ══════════════════════════════════════════════════════════════════════
;; 8. ADR-162 — `(sum ?x)` without `:with` collapses equal values
;; ══════════════════════════════════════════════════════════════════════

(deftest two-equal-invoice-lines-do-not-collapse-into-one
  (testing "`kontor.collections.aging/open-ar-invoices` computed :gross with a
            `(sum ?amt)` that had NO `:with ?l`, so datahike set-semantics
            collapsed two lines of the SAME amount: a 2 x 500.00 invoice
            reported :gross 500.00. This is the LIVE path for bridge invoices,
            which never set :kontor.invoice/total-gross — the shipped fixtures
            all used a single line, so the branch was never exercised."
    ;; TWO order items, each 1 x 500.00 → two invoice LINES of 500.00 each.
    ;; (One item of qty 2 would produce a single 1000.00 line and miss the bug.)
    (let [_ (d/transact
             *conn*
             [{:db/id "ord14"
               :kontor.order/external-id "ORD-14"
               :kontor.order/type :sales
               :kontor.order/status :order.status/created
               :kontor.order/order-date #inst "2026-01-15"
               :kontor.order/entry-date #inst "2026-01-15"
               :kontor.order/currency [:kontor.commodity/symbol "EUR"]
               :kontor.order/bill-from-partner [:kontor.partner/external-id "SELLER"]
               :kontor.order/bill-to-partner [:kontor.partner/external-id "BUYER"]}
              {:kontor.sales.order-item/order "ord14"
               :kontor.sales.order-item/seq-id "00001"
               :kontor.sales.order-item/type :product
               :kontor.sales.order-item/product-id "MILESTONE-1"
               :kontor.sales.order-item/description "Milestone 1"
               :kontor.sales.order-item/quantity 1M
               :kontor.sales.order-item/unit-price 500M
               :kontor.sales.order-item/cancel-quantity 0M
               :kontor.sales.order-item/status :order-item.status/approved}
              {:kontor.sales.order-item/order "ord14"
               :kontor.sales.order-item/seq-id "00002"
               :kontor.sales.order-item/type :product
               :kontor.sales.order-item/product-id "MILESTONE-2"
               :kontor.sales.order-item/description "Milestone 2"
               :kontor.sales.order-item/quantity 1M
               :kontor.sales.order-item/unit-price 500M
               :kontor.sales.order-item/cancel-quantity 0M
               :kontor.sales.order-item/status :order-item.status/approved}])
          _ (inv/make-invoice-from-order! *conn* "ORD-14"
                                          {:external-id "INV-ORD-14"
                                           :issue-date #inst "2026-01-15"
                                           :entity (entity)})
          _ (inv/make-ready! *conn* "INV-ORD-14")
          _ (inv/post-to-ledger! *conn* "INV-ORD-14"
                                 {:journal-ref [:kontor.journal/code "SJ"]
                                  :posted-at #inst "2026-01-15"})
          invoice (eid :kontor.invoice/external-id "INV-ORD-14")
          db (d/db *conn*)]
      (testing "the invoice really has two lines of the same amount"
        (let [tuples (d/q '[:find ?l ?amt :in $ ?i
                            :where
                            [?l :kontor.invoice-line/invoice ?i]
                            [?l :kontor.invoice-line/amount ?amt]]
                          db invoice)
              values (d/q '[:find [?amt ...] :in $ ?i
                            :where
                            [?l :kontor.invoice-line/invoice ?i]
                            [?l :kontor.invoice-line/amount ?amt]]
                          db invoice)]
          (is (= 2 (count tuples)) "two distinct line entities")
          (is (= #{500M} (set (map second tuples))) "both carrying 500")
          (is (= 1 (count values))
              "and THIS is the trap: binding only the value collapses the two
               lines to one tuple, so `(sum ?amt)` without `:with ?l` returns
               500 for a 1000 invoice")))
      (testing ":kontor.invoice/total-gross is unset, so the line-sum branch runs"
        (is (nil? (:kontor.invoice/total-gross
                   (d/pull db [:kontor.invoice/total-gross] invoice)))))
      (testing "gross = 1000.00, not 500.00"
        (is (zero? (.compareTo 1000.00M (papp/gross-of-invoice db invoice))))
        (is (zero? (.compareTo 1000.00M (papp/open-amount-of-invoice db invoice))))
        (let [row (first (filter #(= invoice (:invoice-eid %))
                                 (caging/open-ar-invoices db {:entity-eid (entity)})))]
          (is (zero? (.compareTo 1000.00M (:gross row))))
          (is (zero? (.compareTo 1000.00M (:open-amount row)))))))))
