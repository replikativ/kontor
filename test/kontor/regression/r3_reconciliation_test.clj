(ns kontor.regression.r3-reconciliation-test
  "R3 regression — general line-to-line reconciliation + write-off / tolerance.

   Scope of the kontor reconciliation substrate today:

     * `:kontor.transaction/settles` (schema.cljc:2337) — a *transaction*-
       level, cardinality-many ref: a payment-receipt transaction points at
       the invoice transactions it pays. Invoice-scoped, not line-scoped.
     * `kontor.banking.payment-application` (ADR-043) — an invoice-scoped
       netting primitive: `apply-payment!` records a `:payment-application`
       row of `:amount` against ONE invoice; `open-amount-of-invoice` =
       gross − Σ applications. This is what the GREEN tests below exercise —
       it works.
     * `:kontor.account/reconcilable` (schema.cljc:289) — a boolean *flag* on
       an account (\"lines here are meant to be reconciled\"). There is no
       machinery behind the flag: no per-posting matching group, no residual.

   Odoo, by contrast, reconciles at the journal-**item** level:
     * account_move_line.py:242  amount_residual  — per-line open amount
     * account_move_line.py:255  full_reconcile_id — the matching-group ref
     * account_move_line.py:284  matching_number  — the human match code
     * account_partial_reconcile.py — (debit_move_id, credit_move_id, amount)
       links any two lines; account_full_reconcile.py groups them once the
       residuals net to zero, and can spawn an exchange/write-off move.

   Two gaps are pinned `^:kaocha/pending` (each documents a genuine substrate
   absence; each will start passing the day the substrate lands):

     (a) reconciling two ARBITRARY GL lines (a GR/IR suspense-clearing pair)
         into a matching group that nets to zero — no posting-level substrate.
     (b) settling an invoice with a 1-cent underpayment / cash-discount /
         tolerance write-off leg LINKED to the item — no payment-linked
         write-off leg (`apply-payment!` has no write-off option; the
         `:payment-application` entity has no write-off attrs).

   Everything is booked over a self-contained EUR chart on
   `kontor.core/create-test-db`, through the same write paths a consumer uses
   (`kontor.book/entry!`, `kontor.banking.payment-application/apply-payment!`)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.book :as book]
            [kontor.invoice.schema :as inv-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.reporting.balance :as balance]
            [kontor.workflow.status-machine :as sm]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh-conn
  "Schema-loaded conn with the invoice status-machine seeds installed and a
   minimal EUR chart: one commodity, seller/buyer partners, an audit actor,
   a :general journal (so `book/entry!` resolves it by type), and a chart of
   accounts including a GR/IR clearing account flagged `:reconcilable`."
  []
  (let [conn (core/create-test-db)]
    (inv-schema/install! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.partner/external-id "OWN"  :kontor.partner/name "Self GmbH"
                  :kontor.partner/kind :company}
                 {:kontor.partner/external-id "ACME" :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer}
                 ;; An entity to serve as the audit actor ref
                 ;; (:kontor.audit/create-uid is a :db.type/ref, so we need a
                 ;; real eid, resolved via a queryable attribute).
                 {:kontor.partner/external-id "ACTOR" :kontor.partner/name "Alice"
                  :kontor.partner/kind :company}
                 {:kontor.journal/code "GJ" :kontor.journal/type :general
                  :kontor.journal/name "General"}
                 ;; Chart. GR/IR clearing carries the reconcilable flag.
                 {:kontor.account/path "Assets:Inventory"  :kontor.account/code "1400"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:kontor.account/path "Assets:GR-IR-Clearing" :kontor.account/code "1600"
                  :kontor.account/type :asset :kontor.account/active true
                  :kontor.account/reconcilable true}
                 {:kontor.account/path "Liabilities:AP" :kontor.account/code "2100"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/path "Income:Sales-Discount" :kontor.account/code "4900"
                  :kontor.account/type :income :kontor.account/active true}])
    conn))

(def ^:private eur-ref [:kontor.commodity/symbol "EUR"])
(def ^:private clearing [:kontor.account/path "Assets:GR-IR-Clearing"])

(defn- eur-eid [conn]
  (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db conn)))

(defn- actor [conn]
  (d/q '[:find ?a . :where [?a :kontor.partner/external-id "ACTOR"]] (d/db conn)))

(defn- schema-has-attr?
  "True iff `ident` is an installed schema attribute in `db`."
  [db ident]
  (some? (d/q '[:find ?e . :in $ ?i :where [?e :db/ident ?i]] db ident)))

(defn- acct-eur-amount
  "Single-commodity (EUR) balance amount on `account`, BigDecimal (0M if none)."
  [conn account]
  (let [m (first (vals (balance/account-balance conn account {})))]
    (if m (:amount m) 0M)))

(defn- make-invoice!
  "Create a minimal :sent sales invoice with one line totalling `gross` EUR.
   Returns the invoice eid."
  [conn external-id gross]
  (let [db (d/db conn)
        seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "OWN"]] db)
        buyer  (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]] db)]
    (d/transact conn
                [{:db/id "inv"
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/seller seller
                  :kontor.invoice/buyer buyer
                  :kontor.invoice/currency "EUR"
                  :kontor.invoice/total-gross gross
                  :kontor.invoice/lines ["line"]}
                 {:db/id "line"
                  :kontor.invoice-line/invoice "inv"
                  :kontor.invoice-line/sequence 1
                  :kontor.invoice-line/name "Widget"
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price gross
                  :kontor.invoice-line/amount gross}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :kontor.invoice/external-id ?xid]]
         (d/db conn) external-id)))

(defn- make-payment!
  "Create a posted cash-receipt :transaction. Returns its eid."
  [conn external-id]
  (let [buyer (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]]
                   (d/db conn))]
    (d/transact conn
                [{:kontor.transaction/external-id external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/effective-date #inst "2026-05-01"
                  :kontor.transaction/posted-at #inst "2026-05-01"
                  :kontor.transaction/partner buyer}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :kontor.transaction/external-id ?xid]]
         (d/db conn) external-id)))

;; ============================================================================
;; GREEN — invoice-scoped partial-payment netting (ADR-043) works
;; ============================================================================

(deftest invoice-partial-payment-netting-green
  (testing "apply-payment! nets applications against ONE invoice: a 600 then
            a 400 application on a 1000 invoice drives :sent → :partially-paid
            → :paid and open-amount 1000 → 400 → 0. This is the existing,
            correct invoice-scoped netting primitive."
    (let [conn (fresh-conn)
          inv  (make-invoice! conn "INV-NET" 1000M)
          pay  (make-payment! conn "PAY-NET")
          cur  (eur-eid conn)
          who  (actor conn)]
      (testing "before any application: full open, status :sent"
        (is (zero? (.compareTo 1000M (papp/open-amount-of-invoice (d/db conn) inv))))
        (is (= :sent (sm/current-status (d/db conn) inv :kontor.invoice/status))))
      ;; First partial: 600.
      (papp/apply-payment! conn {:payment pay :invoice inv :amount 600M
                                 :commodity cur :applied-by-uid who
                                 :applied-at #inst "2026-05-02"})
      (testing "after 600: open 400, status :partially-paid"
        (is (zero? (.compareTo 400M (papp/open-amount-of-invoice (d/db conn) inv))))
        (is (= :partially-paid
               (sm/current-status (d/db conn) inv :kontor.invoice/status))))
      ;; Second partial closes it: 400.
      (papp/apply-payment! conn {:payment pay :invoice inv :amount 400M
                                 :commodity cur :applied-by-uid who
                                 :applied-at #inst "2026-05-03"})
      (testing "after 400: open 0, status :paid"
        (is (zero? (.compareTo 0M (papp/open-amount-of-invoice (d/db conn) inv))))
        (is (= :paid (sm/current-status (d/db conn) inv :kontor.invoice/status))))
      (testing "two application rows recorded"
        (is (= 2 (count (papp/applications-of (d/db conn) inv))))))))

;; ============================================================================
;; GREEN — accounting for a GR/IR clearing pair nets the ACCOUNT balance to
;; zero. (The account balance is correct; only the per-LINE matching group is
;; missing — see the pending gap (a) below.)
;; ============================================================================

(deftest clearing-account-balance-nets-to-zero-green
  (testing ":kontor.account/reconcilable is an installed account flag, and a
            GR/IR clearing pair (goods-receipt credit + invoice-receipt debit,
            100 each) leaves the clearing ACCOUNT balance at exactly 0."
    (let [conn (fresh-conn)]
      (testing "the reconcilable flag exists and is set on GR/IR clearing"
        (is (schema-has-attr? (d/db conn) :kontor.account/reconcilable))
        (is (true? (:kontor.account/reconcilable
                    (d/pull (d/db conn) [:kontor.account/reconcilable] clearing)))))
      ;; Goods receipt: Dr Inventory 100 / Cr GR-IR clearing 100.
      (book/entry! conn {:journal-type :general
                         :debit-account  [:kontor.account/path "Assets:Inventory"]
                         :credit-account clearing
                         :amount 100 :commodity eur-ref
                         :effective-date #inst "2026-04-10"
                         :narration "GR: goods received"})
      (testing "after goods receipt only: clearing at -100 (credit)"
        (is (zero? (.compareTo -100M (acct-eur-amount conn clearing)))))
      ;; Invoice receipt: Dr GR-IR clearing 100 / Cr AP 100.
      (book/entry! conn {:journal-type :general
                         :debit-account  clearing
                         :credit-account [:kontor.account/path "Liabilities:AP"]
                         :amount 100 :commodity eur-ref
                         :effective-date #inst "2026-04-12"
                         :narration "IR: invoice received"})
      (testing "after both: clearing ACCOUNT balance nets to 0"
        (is (zero? (.compareTo 0M (acct-eur-amount conn clearing))))))))

;; ============================================================================
;; GAP (a) — line-to-line reconciliation of arbitrary GL postings
;; ============================================================================
;;
;; PENDING(NEW): kontor has no posting-level (account.move.line-level)
;; reconciliation. Even when a GR/IR clearing account NETS to zero across two
;; independent transactions, there is no way to (1) link the specific debit
;; line to the specific credit line into a matching group, (2) mark those two
;; lines "reconciled", or (3) compute a per-line residual so a PARTIAL match
;; (e.g. a 100 credit against a 60 + 40 debit) can be tracked. The only ref is
;; `:kontor.transaction/settles` — transaction-scoped, cardinality-many, and
;; documented as "payment-receipt → invoice", not a generic line matcher.
;;
;; Odoo models exactly this at the line level:
;;   account_move_line.py:255  full_reconcile_id  — the matching-group ref
;;   account_move_line.py:284  matching_number    — the human match code
;;   account_move_line.py:242  amount_residual    — per-line open amount
;;   account_partial_reconcile.py  (debit_move_id, credit_move_id, amount)
;;   account_full_reconcile.py     groups lines once residuals net to zero
;;
;; This test books the GR/IR pair, then asserts the substrate that a
;; line-matcher would require. All of the following are ABSENT today, so the
;; test fails and is pinned pending.
(deftest ^:kaocha/pending arbitrary-gl-line-reconciliation-no-substrate
  (testing "two arbitrary GL lines on a reconcilable clearing account cannot
            be matched into a group / marked reconciled / carry a residual"
    (let [conn (fresh-conn)]
      (book/entry! conn {:journal-type :general
                         :debit-account  [:kontor.account/path "Assets:Inventory"]
                         :credit-account clearing
                         :amount 100 :commodity eur-ref
                         :effective-date #inst "2026-04-10"
                         :narration "GR"})
      (book/entry! conn {:journal-type :general
                         :debit-account  clearing
                         :credit-account [:kontor.account/path "Liabilities:AP"]
                         :amount 100 :commodity eur-ref
                         :effective-date #inst "2026-04-12"
                         :narration "IR"})
      (let [db (d/db conn)
            clearing-lines
            (d/q '[:find [?p ...]
                   :in $ ?acct
                   :where [?p :kontor.posting/account ?acct]]
                 db (:db/id (d/entity db clearing)))]
        (testing "both clearing lines exist (the accounting is real)"
          (is (= 2 (count clearing-lines))))
        ;; The substrate a line-matcher needs — none of these attributes /
        ;; entity types exist in kontor.schema (cf. Odoo account_move_line.py
        ;; :255 / :284 / :242, account_partial_reconcile.py).
        (testing "posting-level matching-group ref (Odoo full_reconcile_id)"
          (is (schema-has-attr? db :kontor.posting/full-reconcile)))
        (testing "posting-level human match code (Odoo matching_number)"
          (is (schema-has-attr? db :kontor.posting/matching-number)))
        (testing "posting-level residual (Odoo amount_residual)"
          (is (schema-has-attr? db :kontor.posting/amount-residual)))
        (testing "a partial-reconcile entity linking two lines
                  (Odoo account.partial.reconcile)"
          (is (schema-has-attr? db :kontor.partial-reconcile/debit-line)))))))

;; ============================================================================
;; GAP (b) — settling an invoice with an underpayment / cash-discount /
;; tolerance write-off leg LINKED to the item
;; ============================================================================
;;
;; PENDING(NEW): `apply-payment!` records only a cash :amount against an
;; invoice; it has no write-off option, and the `:payment-application` entity
;; (schema.cljc:1661) has no write-off attributes. So a 99.99 payment on a
;; 100.00 invoice can never CLOSE the invoice — it leaves a 0.01 residual open
;; and the status stuck at :partially-paid. There is no way to say "the
;; remaining 0.01 is a cash discount / rounding tolerance / small bad-debt,
;; post it to a discount account, and mark the invoice paid" as part of the
;; settlement.
;;
;; NOTE: `kontor.collections.writeoff/write-off-case!` exists, but it is NOT
;; this: it (1) writes off the FULL remaining open amount, (2) is gated behind
;; a heavyweight `:collection-case` at `:legal` state + a supporting audit-doc,
;; (3) routes ONLY to `:bad-debt-expense` (no cash-discount / rounding-
;; tolerance routing), and (4) creates NO `:payment-application`, so
;; `open-amount-of-invoice` (= gross − Σ applications) STILL reports the
;; residual as open and the invoice status never reaches :paid. It is a
;; bad-debt collections event, not a payment-linked tolerance write-off.
;;
;; Odoo does this in one reconcile: the payment line + a write-off line both
;; reconcile against the invoice, the residual nets to zero, and
;; account_full_reconcile closes it (see the write-off wizard driving
;; account_move_line.py:242 amount_residual → 0 + full_reconcile_id).
(deftest ^:kaocha/pending underpayment-writeoff-leg-not-linked-to-payment
  (testing "a 1-cent underpayment cannot be written off as part of the
            settlement: no write-off leg links to the payment-application, so
            the invoice stays open at 0.01 / :partially-paid"
    (let [conn (fresh-conn)
          inv  (make-invoice! conn "INV-WO" 100.00M)
          pay  (make-payment! conn "PAY-WO")
          cur  (eur-eid conn)
          who  (actor conn)]
      ;; Customer pays 99.99 (short by 0.01 — a rounding / cash-discount case).
      (papp/apply-payment! conn {:payment pay :invoice inv :amount 99.99M
                                 :commodity cur :applied-by-uid who
                                 :applied-at #inst "2026-05-02"})
      (let [db (d/db conn)]
        ;; What actually happens today (documented, would pass on its own):
        ;; open = 0.01, status = :partially-paid. The assertions below encode
        ;; the DESIRED post-write-off state, which the substrate cannot reach.
        (testing "DESIRED: a 0.01 tolerance write-off closes the invoice"
          (is (zero? (.compareTo 0M (papp/open-amount-of-invoice db inv))))
          (is (= :paid (sm/current-status db inv :kontor.invoice/status))))
        ;; The substrate a payment-linked write-off leg would require — absent.
        (testing "payment-application carries a write-off account ref"
          (is (schema-has-attr? db :kontor.payment-application/write-off-account)))
        (testing "payment-application carries a write-off amount"
          (is (schema-has-attr? db :kontor.payment-application/write-off-amount)))))))
