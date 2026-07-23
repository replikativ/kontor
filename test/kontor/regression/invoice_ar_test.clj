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
          ;; NEW(P3): open-ar-invoices reports :gross straight from
          ;; :kontor.invoice/total-gross with no fallback, so a bridge/line-based
          ;; invoice (which never sets total-gross) reports :gross nil even
          ;; though :open-amount correctly falls back to the line sum. The two
          ;; fields disagree on how to derive the invoice value.
          (is (nil? (:gross (first opens)))))
        (testing "aging row lands in :90+ carrying the NET open, not the gross"
          (is (= 1 (count rows)))
          (is (= :90+ (:bucket (first rows))))
          (is (= 105 (:days-overdue (first rows))))
          (is (zero? (.compareTo 100.00M (:open-amount (first rows))))))
        (testing "summary totals reflect the net open"
          (is (zero? (.compareTo 100.00M (:90+ sum))))
          (is (zero? (.compareTo 100.00M (:total sum))))
          (is (zero? (.compareTo 0M (:not-yet-due sum)))))))))

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
