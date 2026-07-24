(ns kontor.regression.r3-payment-entity-test
  "Round-3 regression suite — FIRST-CLASS PAYMENT ENTITY area.

   Odoo models a payment as its own object (`account.payment`) that carries
   a payment method, a transient *outstanding* (undeposited-funds / in-
   transit) account, and a two-step register→clear lifecycle whose second
   leg is a bank-statement reconciliation. It also ships batch payments
   (`account.batch_payment`) that group N vendor bills into one bank line,
   and a payment-registration wizard that allocates one payment across many
   bills. See:
     - odoo/addons/account/models/account_payment.py:50-52   is_reconciled / is_matched flags
     - odoo/addons/account/models/account_payment.py:123-128 outstanding_account_id field
     - odoo/addons/account/models/account_payment.py:478-492 is_matched flips on bank-line reconcile
     - odoo/addons/account/models/account_payment.py:393     _prepare_move_line_default_vals (Dr outstanding / Cr AR)

   kontor has NO first-class payment entity. A payment is a bare cash
   `:transaction` booked through the `kontor.book` verbs, and the only
   allocation primitive (`kontor.banking.payment-application`) is keyed to
   AR invoices (`:kontor.invoice/buyer`). This file:

     - CONFIRMS (green) the single-AR-payment application path that DOES work.
     - PINS (^:kaocha/pending) the substrate gaps:
         (a) register→clear via an outstanding/undeposited-funds account with
             a first-class in-transit / is-matched lifecycle,
         (b) an AP-side bulk allocation across N vendor bills (allocator is
             AR-only),
         (c) a batch payment run grouping N bills into one reconcilable bank
             line,
         (NEW) the FIFO allocator is currency-blind — it will allocate a
             single-commodity payment onto an invoice denominated in a
             different currency.

   Money is BigDecimal + a keyword/eid commodity; compared with .compareTo.
   Every asserted figure is hand-derived and cited in a comment."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.book :as book]
            [kontor.partner.schema :as partner-schema]
            [kontor.invoice.schema :as inv-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.reporting.balance :as balance]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

;; ── Setup ─────────────────────────────────────────────────────────────────

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (inv-schema/install! *conn*)
    (d/transact
     *conn*
     [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
      {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
      {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme GmbH"
       :kontor.entity/kind :operating :kontor.entity/active true}
      {:kontor.partner/external-id "SELLER" :kontor.partner/type :org
       :kontor.partner/status :enabled :kontor.partner/name "Acme GmbH"}
      {:kontor.partner/external-id "BUYER" :kontor.partner/type :org
       :kontor.partner/status :enabled :kontor.partner/name "Big Customer Co"
       :kontor.partner/kind :customer}
      {:kontor.partner/external-id "VENDOR" :kontor.partner/type :org
       :kontor.partner/status :enabled :kontor.partner/name "Supplier Ltd"
       :kontor.partner/kind :vendor}
      {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
      ;; A small SKR04-ish chart.
      {:kontor.account/code "1400" :kontor.account/name "Forderungen (AR)"
       :kontor.account/path "Assets:AR" :kontor.account/type :asset :kontor.account/active true}
      {:kontor.account/code "1200" :kontor.account/name "Bank"
       :kontor.account/path "Assets:Bank" :kontor.account/type :asset :kontor.account/active true}
      ;; The would-be Odoo "Outstanding Receipts" (undeposited funds / in-transit).
      {:kontor.account/code "1210" :kontor.account/name "Geldtransit (Outstanding Receipts)"
       :kontor.account/path "Assets:Outstanding-Receipts" :kontor.account/type :asset :kontor.account/active true}
      {:kontor.account/code "1600" :kontor.account/name "Verbindlichkeiten (AP)"
       :kontor.account/path "Liabilities:AP" :kontor.account/type :liability :kontor.account/active true}
      {:kontor.account/code "1810" :kontor.account/name "Outstanding Payments"
       :kontor.account/path "Liabilities:Outstanding-Payments" :kontor.account/type :liability :kontor.account/active true}
      {:kontor.account/code "4400" :kontor.account/name "Erlöse"
       :kontor.account/path "Income:Sales" :kontor.account/type :revenue :kontor.account/active true}
      {:kontor.account/code "6000" :kontor.account/name "Aufwand"
       :kontor.account/path "Expenses:Goods" :kontor.account/type :expense :kontor.account/active true}
      ;; Journals — one :sale, one :purchase, one :general, and the CR/CD cash pair.
      {:kontor.journal/code "SJ" :kontor.journal/type :sale     :kontor.journal/name "Sales"     :kontor.journal/active true}
      {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchases" :kontor.journal/active true}
      {:kontor.journal/code "GJ" :kontor.journal/type :general  :kontor.journal/name "General"   :kontor.journal/active true}
      {:kontor.journal/code "CR" :kontor.journal/type :cash     :kontor.journal/name "Cash Receipts"      :kontor.journal/active true}
      {:kontor.journal/code "CD" :kontor.journal/type :cash     :kontor.journal/name "Cash Disbursements" :kontor.journal/active true}])
    (f)))

(use-fixtures :each bootstrap)

;; ── Helpers ───────────────────────────────────────────────────────────────

(defn- eid [attr v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] (d/db *conn*) attr v))

(defn- eur []    (eid :kontor.commodity/symbol "EUR"))
(defn- usd []    (eid :kontor.commodity/symbol "USD"))
(defn- actor []  (eid :kontor.partner/external-id "U-alice"))
(defn- buyer []  (eid :kontor.partner/external-id "BUYER"))
(defn- vendor [] (eid :kontor.partner/external-id "VENDOR"))
(defn- ident-installed? [i]
  (some? (d/q '[:find ?e . :in $ ?i :where [?e :db/ident ?i]] (d/db *conn*) i)))

(defn- bal
  "Single-commodity BigDecimal balance on `account-path` (0M if none)."
  [account-path commodity-eid]
  (let [m (balance/account-balance *conn* [:kontor.account/path account-path] {})]
    (or (some-> (get m commodity-eid) :amount) 0M)))

(defn- mk-invoice!
  "Create an :kontor.invoice row directly in :sent with an explicit
   :total-gross, so `open-amount-of-invoice` reads the gross without needing
   the order→invoice bridge. `role` is :ar (partner is buyer) or :ap (partner
   is seller — a vendor bill). Returns the invoice eid."
  [ext-id role gross iso-code]
  (let [counterparty (case role :ar (buyer) :ap (vendor))]
    (d/transact
     *conn*
     [(cond-> {:kontor.invoice/external-id ext-id
               :kontor.invoice/status :sent
               :kontor.invoice/currency iso-code
               :kontor.invoice/total-gross gross
               :kontor.invoice/issue-date #inst "2026-01-15"}
        (= role :ar) (assoc :kontor.invoice/buyer counterparty
                            :kontor.invoice/seller (eid :kontor.partner/external-id "SELLER"))
        (= role :ap) (assoc :kontor.invoice/seller counterparty
                            :kontor.invoice/buyer (eid :kontor.partner/external-id "SELLER")))])
    (eid :kontor.invoice/external-id ext-id)))

(defn- mk-payment!
  "A bare cash-receipt/disbursement :transaction (kontor's only 'payment')."
  [ext-id]
  (d/transact *conn*
              [{:kontor.transaction/external-id ext-id
                :kontor.transaction/state :posted
                :kontor.transaction/effective-date #inst "2026-02-01"
                :kontor.transaction/posted-at #inst "2026-02-01"}])
  (eid :kontor.transaction/external-id ext-id))

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. GREEN — single AR payment application (the path that works)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest single-ar-payment-application-full-close
  ;; gross = net 1000 + 19% VAT 190 = 1190. A single full application closes
  ;; the invoice to :paid with open = 0.
  (testing "one apply-payment! for the full gross closes a :sent invoice"
    (let [inv (mk-invoice! "INV-AR-1" :ar 1190M "EUR")
          pay (mk-payment! "PAY-1")]
      (is (= :sent (sm/current-status (d/db *conn*) inv :kontor.invoice/status)))
      (is (zero? (.compareTo 1190M (papp/open-amount-of-invoice (d/db *conn*) inv))))
      (papp/apply-payment! *conn* {:payment pay :invoice inv :amount 1190M
                                   :commodity (eur) :applied-by-uid (actor)})
      (let [db (d/db *conn*)]
        (is (= :paid (sm/current-status db inv :kontor.invoice/status)))
        (is (zero? (.compareTo 1190M (papp/applied-amount-of-invoice db inv))))
        (is (zero? (.compareTo 0M (papp/open-amount-of-invoice db inv))))
        (is (= 1 (count (papp/applications-of db inv))))))))

(deftest single-ar-payment-application-partial-split
  ;; A partial 700 of 1190 leaves 490 open and flips :sent → :partially-paid.
  (testing "partial application splits the open item"
    (let [inv (mk-invoice! "INV-AR-2" :ar 1190M "EUR")
          pay (mk-payment! "PAY-2")]
      (papp/apply-payment! *conn* {:payment pay :invoice inv :amount 700M
                                   :commodity (eur) :applied-by-uid (actor)
                                   :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (is (= :partially-paid (sm/current-status db inv :kontor.invoice/status)))
        (is (zero? (.compareTo 700M (papp/applied-amount-of-invoice db inv))))
        ;; 1190 − 700 = 490
        (is (zero? (.compareTo 490M (papp/open-amount-of-invoice db inv))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. PENDING(a) — register→clear two-step via an outstanding account
;; ═══════════════════════════════════════════════════════════════════════════

(deftest ^:kaocha/pending register-then-clear-outstanding-account-two-step
  ;; PENDING(NEW): kontor has no first-class payment entity. Odoo's
  ;; account.payment posts against a transient outstanding_account_id
  ;; (odoo/addons/account/models/account_payment.py:123-128 / :393) at
  ;; *register* time, then flips `is_matched` true when that outstanding
  ;; line reconciles against the bank statement at *clear* time
  ;; (account_payment.py:478-492). kontor can express the two GL legs by
  ;; hand — proven green below — but there is NO :kontor.payment/* entity,
  ;; no payment-method → outstanding-account binding, and no is-matched /
  ;; in-transit lifecycle flag to tell a registered-but-uncleared payment
  ;; apart from a cleared one. So "which cash is still undeposited?" cannot
  ;; be answered as a first-class query.
  (testing "the two GL legs of register→clear are expressible by hand"
    (let [inv (mk-invoice! "INV-REG-1" :ar 1190M "EUR")
          pay (mk-payment! "PAY-REG-1")]
      ;; Register: cash received but not yet in the bank — Dr Outstanding, Cr AR.
      (book/receive-payment! *conn* {:debit-account [:kontor.account/path "Assets:Outstanding-Receipts"]
                                     :credit-account [:kontor.account/path "Assets:AR"]
                                     :amount 1190M :commodity (eur)
                                     :effective-date #inst "2026-02-01"})
      (papp/apply-payment! *conn* {:payment pay :invoice inv :amount 1190M
                                   :commodity (eur) :applied-by-uid (actor)})
      (testing "after register: AR cleared, cash sits in the outstanding account"
        (is (zero? (.compareTo 0M    (bal "Assets:AR" (eur)))))
        (is (zero? (.compareTo 1190M (bal "Assets:Outstanding-Receipts" (eur)))))
        (is (zero? (.compareTo 0M    (bal "Assets:Bank" (eur))))))
      ;; Clear: bank statement reconciliation — Dr Bank, Cr Outstanding.
      (book/transfer! *conn* {:debit-account [:kontor.account/path "Assets:Bank"]
                              :credit-account [:kontor.account/path "Assets:Outstanding-Receipts"]
                              :amount 1190M :commodity (eur)
                              :effective-date #inst "2026-02-03"})
      (testing "after clear: cash lands in the bank, outstanding drains to 0"
        (is (zero? (.compareTo 1190M (bal "Assets:Bank" (eur)))))
        (is (zero? (.compareTo 0M    (bal "Assets:Outstanding-Receipts" (eur)))))))
    ;; The gap: NO first-class payment entity / method / in-transit flag.
    (testing "a first-class payment entity with an outstanding-account lifecycle exists"
      (is (ident-installed? :kontor.payment/state)
          "expected an account.payment-like entity with a register→clear state")
      (is (ident-installed? :kontor.payment/outstanding-account)
          "expected a payment-method → outstanding-account binding (Odoo outstanding_account_id)")
      (is (ident-installed? :kontor.payment/is-matched)
          "expected an is-matched flag that flips on bank-line reconciliation"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3. PENDING(b) — AP-side bulk allocation across N vendor bills
;; ═══════════════════════════════════════════════════════════════════════════

(deftest ap-side-bulk-allocation-across-vendor-bills
  ;; FIXED (note 198 PAY-B): allocate-fifo! takes a `:side` — :ar (default,
  ;; partner is the invoice :buyer) or :ap (partner is the :seller, i.e. vendor
  ;; bills) — matching Odoo's symmetric inbound/outbound register wizard.
  ;; Original finding: `allocate-fifo!` (and its `open-invoices-for-partner`
  ;; helper, payment_application.clj:427-456) query ONLY
  ;; `[?i :kontor.invoice/buyer ?p]` — i.e. AR receivables. There is no
  ;; AP-side counterpart that walks vendor bills (`:kontor.invoice/seller`)
  ;; for a partner and allocates a single vendor payment across them oldest-
  ;; first. Odoo's register-payment wizard is symmetric across inbound and
  ;; outbound (account.payment payment_type ∈ {inbound, outbound}); kontor's
  ;; allocator is inbound-only. Two open vendor bills below (2000 + 3000)
  ;; should absorb a 4000 vendor payment (2000 full + 2000 partial), but the
  ;; AR-keyed allocator sees neither bill.
  (let [_b1 (mk-invoice! "BILL-1" :ap 2000M "EUR")
        _b2 (mk-invoice! "BILL-2" :ap 3000M "EUR")
        pay (mk-payment! "PAY-AP-1")
        ;; :side :ap — the partner is the invoice SELLER (a vendor bill). Explicit
        ;; rather than inferred, since one partner can be both buyer and seller.
        allocations (papp/allocate-fifo! *conn* {:payment pay :partner (vendor)
                                                 :side :ap
                                                 :total-amount 4000M :commodity (eur)
                                                 :applied-by-uid (actor)})]
    (testing "a 4000 vendor payment allocates FIFO across the two bills"
      ;; oldest-first: BILL-1 gets 2000 (full), BILL-2 gets 2000 (partial of 3000).
      (is (= 2 (count allocations))
          "allocator is AR-only — vendor bills (:kontor.invoice/seller) are invisible")
      (is (zero? (.compareTo 4000M
                             (reduce (fn [^java.math.BigDecimal acc a]
                                       (.add acc ^java.math.BigDecimal (:allocated a)))
                                     0M allocations)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 4. PENDING(c) — batch payment run grouping N bills into one bank line
;; ═══════════════════════════════════════════════════════════════════════════

(deftest ^:kaocha/pending batch-payment-run-groups-bills-into-one-bank-line
  ;; PENDING(NEW): Odoo's account.batch_payment groups N supplier payments
  ;; into a single object that reconciles against ONE bank statement line
  ;; (the aggregate debit that leaves the account). kontor has neither a
  ;; :kontor.batch-payment/* entity nor a batch builder; the closest
  ;; primitive is `:kontor.transaction/settles` (cardinality-many) which
  ;; can point one cash tx at several invoices, but that is a bare GL
  ;; construct with no batch lifecycle, no per-run status, and no
  ;; reconcile-the-batch-as-a-unit semantics.
  (mk-invoice! "BILL-B1" :ap 1000M "EUR")
  (mk-invoice! "BILL-B2" :ap 1500M "EUR")
  (mk-invoice! "BILL-B3" :ap 2500M "EUR")
  (testing "a first-class batch-payment entity groups the run"
    (is (ident-installed? :kontor.batch-payment/payments)
        "expected a batch-payment entity aggregating N payments into one bank line")
    (is (ident-installed? :kontor.batch-payment/state)
        "expected a batch-payment lifecycle (draft → sent → reconciled)")
    (is (ident-installed? :kontor.batch-payment/total-amount)
        "expected the batch total that reconciles against one bank statement line (1000+1500+2500 = 5000)")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 5. PENDING(NEW) — FIFO allocator is currency-blind
;; ═══════════════════════════════════════════════════════════════════════════

(deftest fifo-allocator-ignores-invoice-currency
  ;; FIXED (note 198 PAY-NEW): `open-invoices-for-partner` now filters candidates
  ;; by the payment's commodity, so a EUR payment can no longer be netted 1:1
  ;; onto a USD invoice. Cross-currency settlement goes through settle-invoice!,
  ;; which converts and books the realized FX. Original finding:
  ;; selects a partner's :sent/:partially-paid invoices with NO filter on
  ;; :kontor.invoice/currency, and `allocate-fifo!` compares open-amount vs
  ;; remaining purely by magnitude (payment_application.clj:497-512) and
  ;; writes the *payment's* :commodity onto every application row. So a
  ;; EUR payment is silently allocated onto a USD-denominated invoice,
  ;; mixing currencies 1:1 with no FX translation. A correct allocator must
  ;; skip invoices whose currency differs from the payment's commodity (or
  ;; translate via kontor.fx). Odoo scopes the register wizard to a single
  ;; currency and refuses cross-currency 1:1 netting.
  (let [eur-inv (mk-invoice! "INV-EUR" :ar 1000M "EUR")
        usd-inv (mk-invoice! "INV-USD" :ar 1000M "USD")
        pay     (mk-payment! "PAY-FX")
        ;; A 1500 EUR payment. Only the EUR invoice (1000) should absorb any
        ;; of it; the USD invoice must be skipped (wrong currency).
        allocations (papp/allocate-fifo! *conn* {:payment pay :partner (buyer)
                                                 :total-amount 1500M :commodity (eur)
                                                 :applied-by-uid (actor)})
        usd-open (papp/open-amount-of-invoice (d/db *conn*) usd-inv)]
    (testing "the EUR payment does not touch the USD invoice"
      ;; Correct: exactly one allocation (the EUR invoice, 1000); the USD
      ;; invoice stays fully open at 1000. Buggy substrate allocates the
      ;; leftover 500 EUR onto the USD invoice.
      (is (= 1 (count allocations))
          "currency-blind allocator wrongly spreads EUR cash onto the USD invoice")
      (is (zero? (.compareTo 1000M usd-open))
          "USD invoice must remain fully open — a EUR payment cannot settle it 1:1")
      (is (= eur-inv (:invoice-eid (first allocations)))))))
