(ns kontor.regression.r3-fx-settlement-test
  "Regression suite — round-3 gap analysis, AREA: realized FX gain/loss on
   cross-currency settlement (the #1 systemic gap vs Odoo/ERPNext/Tryton).

   The picture the substrate is missing:

     A EUR invoice is settled by a USD payment made at a rate that differs
     from the invoice-date rate. The cash the seller actually receives, once
     translated back to the invoice/functional currency, is NOT equal to the
     receivable's carrying amount. The difference is a REALIZED FX gain or
     loss and, per every reference system, must be booked to a dedicated
     exchange gain/loss account on settlement.

     Odoo does this automatically on reconcile:
       - account_move_line.py:2132  `_prepare_reconciliation_single_currency`
         picks the rate applied on reconcile.
       - account_partial_reconcile.py:23  `exchange_move_id` — the partial
         reconcile row carries a link to the generated exchange move.
       - account_move_line.py:3040  `_create_exchange_difference_moves`
         (+ :2951 `_prepare_exchange_difference_move_vals`, :2946
         `_get_exchange_account`) materialises the FX gain/loss journal entry.

   kontor today:
     - `kontor.banking.payment-application/apply-payment!` records a
       `:payment-application` row whose docstring (payment_application.clj:199)
       claims `:commodity` \"Must match invoice currency\" — but NO guard
       enforces it, and `open-amount-of-invoice` subtracts the applied number
       from the invoice gross REGARDLESS of commodity. A USD payment on a EUR
       invoice is silently netted number-against-number.
     - `kontor.banking.reconciliation` (reconciliation.cljc:43) explicitly
       DEFERS \"FX revaluation when bank commodity ≠ invoice commodity\" and
       `single-commodity` (reconciliation.cljc:136) refuses multi-commodity
       open items outright.
     - There is no `:fx-gain-loss-account` parameter anywhere on the
       settlement path, and settlement produces no GL posting to an exchange
       account.

   Green tests confirm the same-currency happy path AND that the Money-layer
   FX arithmetic building block is correct (so the gap is isolated to the
   settlement WIRING, not the math). ^:kaocha/pending tests pin the genuine
   substrate gaps with a PENDING(NEW) comment + Odoo file:line reference.

   Every asserted number is hand-derived from the stated rates and annotated."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.fx.fx :as fx]
            [kontor.fx.fx-rate-provider :as fxp]
            [kontor.money :as money]
            [kontor.workflow.status-machine :as sm]))

;; ---------------------------------------------------------------------------
;; Fixture — commodities (EUR + USD), partners, an actor, an FX-loss account,
;; and two-date EUR/USD rates.
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

;; Invoice date 2026-04-01: 1 EUR = 1.20 USD  (⇒ a 1,000 EUR invoice is
;; contractually 1,200 USD).
;; Payment date 2026-05-01: 1 EUR = 1.25 USD  (EUR strengthened / USD weakened
;; ⇒ 1 USD = 0.80 EUR). The 1,200 USD the customer pays is now worth only
;; 1,200 × 0.80 = 960.00 EUR — a 40.00 EUR realized FX LOSS to the seller.
(def invoice-date #inst "2026-04-01T00:00:00Z")
(def payment-date #inst "2026-05-01T00:00:00Z")
;; A later instant for a second application on the same (payment, invoice) —
;; the :kontor.payment-application/identity is [payment invoice applied-at], so
;; two installments must carry distinct applied-at values or the second upserts
;; onto the first.
(def payment-date-2 #inst "2026-05-15T00:00:00Z")

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (inv-schema/install! *conn*)
    (d/transact *conn*
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
                 {:kontor.partner/external-id "ACME"
                  :kontor.partner/name "ACME Inc"
                  :kontor.partner/kind :customer}
                 {:kontor.partner/external-id "OWN"
                  :kontor.partner/name "Self GmbH"
                  :kontor.partner/kind :company}
                 {:db/id "actor-1" :kontor.audit/create-uid "alice@example"}
                 ;; A P&L account for the realized FX gain/loss (the account
                 ;; Odoo's _get_exchange_account would resolve).
                 {:kontor.account/path "Expenses:FX-GainLoss"
                  :kontor.account/code "6880"
                  :kontor.account/name "Realized FX gain/loss"
                  :kontor.account/type :expense
                  :kontor.account/commodity [:kontor.commodity/symbol "EUR"]
                  :kontor.account/active true}
                 ;; The EUR receivable the invoice carries, and a USD bank
                 ;; account — each PINNED to its own commodity, as every
                 ;; shipped l10n chart pins them (commodity-match invariant).
                 {:kontor.account/path "Assets:Receivable"
                  :kontor.account/code "1400"
                  :kontor.account/name "Accounts receivable"
                  :kontor.account/type :asset
                  :kontor.account/commodity [:kontor.commodity/symbol "EUR"]
                  :kontor.account/active true}
                 {:kontor.account/path "Assets:Bank-USD"
                  :kontor.account/code "1210"
                  :kontor.account/name "Bank (USD)"
                  :kontor.account/type :asset
                  :kontor.account/commodity [:kontor.commodity/symbol "USD"]
                  :kontor.account/active true}
                 ;; The currency-clearing bridge — deliberately POLYMORPHIC
                 ;; (no :kontor.account/commodity), Beancount Equity:Conversions
                 ;; / GnuCash Trading-Account shaped. A technical account,
                 ;; excluded from statement presentation by convention.
                 {:kontor.account/path "Assets:FX-Clearing"
                  :kontor.account/code "1490"
                  :kontor.account/name "Currency clearing (technical)"
                  :kontor.account/type :asset
                  :kontor.account/active true}
                 {:kontor.journal/code "GJ"
                  :kontor.journal/name "General Journal"
                  :kontor.journal/type :general
                  :kontor.journal/active true}])
    ;; EUR→USD spot at both dates; the USD→EUR direction is derived by the
    ;; StaticTableProvider's inverse machinery, but we also store the exact
    ;; payment-date USD→EUR = 0.80 so the settlement math is noise-free.
    (fxp/save-rates!
     *conn*
     [{:from "EUR" :to "USD" :at-date invoice-date :rate 1.20M :rate-type :spot :source :test}
      {:from "EUR" :to "USD" :at-date payment-date :rate 1.25M :rate-type :spot :source :test}
      {:from "USD" :to "EUR" :at-date payment-date :rate 0.80M :rate-type :spot :source :test}])
    (f)))

(use-fixtures :each bootstrap)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- comm [sym]
  (d/q '[:find ?c . :in $ ?s :where [?c :kontor.commodity/symbol ?s]] (d/db *conn*) sym))
(defn- acct [code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] (d/db *conn*) code))
(defn- jnl []
  (d/q '[:find ?j . :where [?j :kontor.journal/code "GJ"]] (d/db *conn*)))
(defn- actor [] "actor-1")

(defn- make-invoice!
  "A minimal :sent sales invoice for `gross` in `currency` (ISO string).
   Returns the invoice eid."
  [external-id gross currency]
  (let [db (d/db *conn*)
        seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "OWN"]] db)
        buyer  (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]] db)]
    (d/transact *conn*
                [{:db/id "inv"
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date invoice-date
                  :kontor.invoice/seller seller
                  :kontor.invoice/buyer  buyer
                  :kontor.invoice/currency currency
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
         (d/db *conn*) external-id)))

(defn- make-payment!
  "A posted cash-receipt :transaction. Returns its eid."
  [external-id]
  (let [db (d/db *conn*)
        buyer (d/q '[:find ?p . :where [?p :kontor.partner/external-id "ACME"]] db)]
    (d/transact *conn*
                [{:kontor.transaction/external-id external-id
                  :kontor.transaction/state :posted
                  :kontor.transaction/effective-date payment-date
                  :kontor.transaction/posted-at payment-date
                  :kontor.transaction/partner buyer}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :kontor.transaction/external-id ?xid]]
         (d/db *conn*) external-id)))

(defn- amt= [expected actual]
  (and (some? actual) (zero? (.compareTo ^java.math.BigDecimal expected
                                         ^java.math.BigDecimal actual))))

;; ===========================================================================
;; GREEN 1 — same-currency full settlement closes the invoice cleanly.
;; The happy path the task asks to confirm green.
;; ===========================================================================

(deftest same-currency-full-settlement-closes-invoice
  (testing "A 1,000 EUR invoice settled by a 1,000 EUR payment goes to :paid,
            open-amount 0, exactly one application row — no FX involved."
    (let [inv (make-invoice! "INV-EUR-FULL" 1000M "EUR")
          pay (make-payment! "PAY-EUR-FULL")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 1000M
                            :commodity (comm "EUR") :applied-by-uid (actor)
                            :applied-at payment-date})
      (let [db (d/db *conn*)]
        (is (= :paid (sm/current-status db inv :kontor.invoice/status))
            "sent → paid on full settlement")
        (is (amt= 0M (papp/open-amount-of-invoice db inv))
            "1,000 − 1,000 = 0 EUR open")
        (is (= 1 (count (papp/applications-of db inv)))
            "one application row recorded")))))

;; ===========================================================================
;; GREEN 2 — same-currency partial then final settlement tracks correctly.
;; ===========================================================================

(deftest same-currency-partial-then-final-settlement
  (testing "400 then 600 EUR against a 1,000 EUR invoice: :partially-paid
            then :paid; open-amount tracks 600 then 0."
    (let [inv (make-invoice! "INV-EUR-PART" 1000M "EUR")
          pay (make-payment! "PAY-EUR-PART")]
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 400M
                            :commodity (comm "EUR") :applied-by-uid (actor)
                            :applied-at payment-date :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (is (= :partially-paid (sm/current-status db inv :kontor.invoice/status)))
        (is (amt= 600M (papp/open-amount-of-invoice db inv)) "1,000 − 400 = 600 EUR"))
      (papp/apply-payment! *conn*
                           {:payment pay :invoice inv :amount 600M
                            :commodity (comm "EUR") :applied-by-uid (actor)
                            :applied-at payment-date-2 :strategy :customer-instruction})
      (let [db (d/db *conn*)]
        (is (= :paid (sm/current-status db inv :kontor.invoice/status)))
        (is (amt= 0M (papp/open-amount-of-invoice db inv)) "1,000 − 400 − 600 = 0 EUR")))))

;; ===========================================================================
;; GREEN 3 — the Money-layer FX arithmetic building block IS correct.
;; This isolates the gap: kontor CAN compute the realized difference; it just
;; never calls this on the settlement path.
;; ===========================================================================

(deftest realized-fx-building-block-is-correct
  (testing "1,200 USD received @ payment-date USD→EUR 0.80 = 960.00 EUR;
            versus a 1,000.00 EUR receivable carrying amount ⇒ a 40.00 EUR
            realized FX LOSS. fx/convert gets this exactly right."
    (let [p (fxp/make-static-table-provider *conn*)
          received-usd (money/money 1200M "USD")
          received-in-eur (fx/convert received-usd p {:to "EUR" :at-date payment-date})
          carrying (money/money 1000M "EUR")
          realized-loss (money/sub carrying received-in-eur)]
      (is (= "EUR" (:commodity received-in-eur)))
      (is (amt= 960.00M (:amount received-in-eur))
          "1,200 USD × 0.80 = 960.00 EUR")
      (is (amt= 40.00M (:amount realized-loss))
          "1,000.00 − 960.00 = 40.00 EUR realized FX loss (Odoo would book this)")
      ;; and the inverse leg round-trips: 1,000 EUR was 1,200 USD @ invoice date.
      (is (amt= 1200.00M (:amount (fx/convert carrying p {:to "USD" :at-date invoice-date})))
          "1,000 EUR × 1.20 = 1,200.00 USD at invoice date"))))

;; ===========================================================================
;; PENDING(NEW) A — cross-currency settlement is silently number-netted.
;;
;; A USD payment on a EUR invoice: apply-payment! neither refuses nor converts.
;; open-amount-of-invoice subtracts the applied AMOUNT from the EUR gross with
;; NO regard for commodity, so a 1,200 USD payment against a 1,000 EUR invoice
;; leaves open = 1,000 − 1,200 = −200, treating 1,200 USD as if it were
;; 1,200 EUR. The invoice reads as OVERPAID by 200 "EUR".
;;
;; Odoo: on reconcile the payment is translated to the receivable currency at
;; the reconcile rate and the residual becomes an exchange difference, NEVER a
;; number-against-number netting across currencies
;; (account_move_line.py:2132 _prepare_reconciliation_single_currency).
;;
;; DESIRED: the invoice is fully settled — open 0 EUR — because the customer
;; paid the agreed 1,200 USD; the 40 EUR shortfall is an FX loss, not an open
;; receivable. Currently open = −200 EUR (nonsense). Flip to green once
;; apply-payment! converts the payment commodity to the invoice currency.
;; ===========================================================================

;; FIXED (note 198 FX-A): `apply-payment!` stays TRACKING-only and now REFUSES a
;; commodity mismatch (FX-C, pinned below) instead of silently number-netting.
;; The cross-currency settlement is owned by `settle-invoice!`, which books the
;; GL entry AND the tracking row in one transaction, recording the application
;; in the INVOICE currency so the open item nets correctly.
(deftest cross-currency-settlement-should-close-not-overpay
  (testing "1,200 USD paid on a 1,000 EUR invoice fully closes it (open 0 EUR),
            rather than reading as a 200-EUR overpayment."
    (let [inv (make-invoice! "INV-XCUR-A" 1000M "EUR")
          pay (make-payment! "PAY-XCUR-A")]
      ;; The customer paid the contractual 1,200 USD.
      (papp/settle-invoice! *conn*
                            {:payment pay :invoice inv :amount 1200M
                             :commodity (comm "USD") :applied-by-uid (actor)
                             :effective-date payment-date
                             :cash-account (acct "1210")
                             :receivable-account (acct "1400")
                             :fx-clearing-account (acct "1490")
                             :fx-gain-loss-account (acct "6880")
                             :fx-provider (fxp/make-static-table-provider *conn*)
                             :journal (jnl)})
      (let [db (d/db *conn*)
            open (papp/open-amount-of-invoice db inv)]
        (is (amt= 0M open)
            "cross-currency full settlement leaves 0 EUR open")
        (is (= :paid (sm/current-status db inv :kontor.invoice/status))
            "the invoice is :paid after the customer paid in full (in USD)")))))

;; ===========================================================================
;; PENDING(NEW) B — settlement books no realized FX gain/loss posting.
;;
;; Even granting the customer paid exactly 1,200 USD = the invoice's contractual
;; amount, translating that cash to EUR at the payment-date rate yields only
;; 960 EUR against a 1,000 EUR receivable ⇒ a 40 EUR realized FX loss that MUST
;; hit a P&L exchange account. apply-payment! writes only a :payment-application
;; row (no GL posting at all), exposes no :fx-gain-loss-account parameter, and
;; therefore never touches account 6880. After settlement the FX account has
;; zero postings.
;;
;; Odoo: account_move_line.py:3040 _create_exchange_difference_moves
;; materialises exactly this entry (account resolved by :2946
;; _get_exchange_account; the partial reconcile carries exchange_move_id —
;; account_partial_reconcile.py:23).
;;
;; DESIRED: a posting of 40.00 EUR to Expenses:FX-GainLoss (6880) exists after
;; settlement. Currently: none. Flip to green once the settlement path books
;; the realized FX difference.
;; ===========================================================================

(defn- fx-account-eid []
  (d/q '[:find ?a . :where [?a :kontor.account/code "6880"]] (d/db *conn*)))

(defn- postings-on-fx-account []
  (d/q '[:find [?p ...]
         :in $ ?acct
         :where [?p :kontor.posting/account ?acct]]
       (d/db *conn*) (fx-account-eid)))

;; FIXED (note 198 FX-B): settle-invoice! books the five-leg bridge —
;;   Dr Bank:USD 1200 USD / Cr FX-Clearing 1200 USD      (USD nets 0)
;;   Dr FX-Clearing 960 EUR + Dr FX-Loss 40 EUR / Cr AR 1000 EUR  (EUR nets 0)
;; The polymorphic clearing account is what lets a single transaction span two
;; commodities under kontor's per-(ledger,commodity) sum-to-zero rule.
(deftest cross-currency-settlement-should-book-realized-fx-loss
  (testing "settling the 1,000 EUR invoice with 1,200 USD (= 960 EUR @ payment
            date) books a 40.00 EUR realized FX loss to account 6880."
    (let [inv (make-invoice! "INV-XCUR-B" 1000M "EUR")
          pay (make-payment! "PAY-XCUR-B")]
      (papp/settle-invoice! *conn*
                            {:payment pay :invoice inv :amount 1200M
                             :commodity (comm "USD") :applied-by-uid (actor)
                             :effective-date payment-date
                             :cash-account (acct "1210")
                             :receivable-account (acct "1400")
                             :fx-clearing-account (acct "1490")
                             :fx-gain-loss-account (acct "6880")
                             :fx-provider (fxp/make-static-table-provider *conn*)
                             :journal (jnl)})
      (let [fx-postings (postings-on-fx-account)]
        (is (= 1 (count fx-postings))
            "exactly one realized-FX posting is booked on settlement")
        (let [amt (:kontor.posting/amount
                   (d/pull (d/db *conn*) [:kontor.posting/amount] (first fx-postings)))]
          (is (amt= 40.00M amt)
              "the realized FX loss is 40.00 EUR (1,000 − 1,200×0.80)")))
      ;; the currency-clearing account holds the open position, by construction
      (testing "the technical clearing account carries the currency position"
        (let [db (d/db *conn*)
              legs (d/q '[:find ?amt ?sym
                          :in $ ?acct
                          :where
                          [?p :kontor.posting/account ?acct]
                          [?p :kontor.posting/amount ?amt]
                          [?p :kontor.posting/commodity ?c]
                          [?c :kontor.commodity/symbol ?sym]]
                        db (acct "1490"))]
          (is (= #{[-1200M "USD"] [960.00M "EUR"]} (set legs))
              "clearing holds −1,200 USD / +960 EUR — the open currency position"))))))

;; ===========================================================================
;; PENDING(NEW) C — apply-payment! ignores its own commodity contract.
;;
;; payment_application.clj:199 documents `:commodity` as "Must match invoice
;; currency", but apply-payment-tx-data never compares the two. A payment whose
;; commodity differs from :kontor.invoice/currency is accepted silently. Until
;; the substrate can CONVERT + book the FX difference (gaps A/B above), the
;; least-surprising contract — and the one reconciliation.cljc:43 implies by
;; DEFERRING cross-currency settlement rather than doing it wrong — is to REFUSE
;; the mismatch loudly rather than corrupt the open-item figure.
;;
;; DESIRED: apply-payment! throws when :commodity ≠ the invoice currency (or,
;; once implemented, converts). Currently it silently succeeds. Flip to green
;; once either a guard or a conversion path lands.
;; ===========================================================================

(deftest apply-payment-should-not-silently-accept-commodity-mismatch
  (testing "a USD payment on a EUR invoice must not be silently number-netted;
            apply-payment! should reject the commodity mismatch (or convert)."
    (let [inv (make-invoice! "INV-XCUR-C" 1000M "EUR")
          pay (make-payment! "PAY-XCUR-C")
          threw? (try
                   (papp/apply-payment! *conn*
                                        {:payment pay :invoice inv :amount 1200M
                                         :commodity (comm "USD") :applied-by-uid (actor)
                                         :applied-at payment-date})
                   false
                   (catch clojure.lang.ExceptionInfo _ true))]
      ;; PENDING(NEW): actual is false — the mismatched-commodity application is
      ;; recorded without complaint, in contradiction to the docstring contract.
      (is threw?
          "apply-payment! should refuse (or convert) a payment commodity that
           differs from the invoice currency, per its own docstring contract"))))
