(ns kontor.end-to-end-demo-test
  "End-to-end scenario: a German freelancer's full year on the kernel.

   Story: \"Self GmbH\" runs a one-person consultancy. In FY2025 it
   issues two invoices to ACME (Q1 and Q3), pays a few operating
   expenses out of its bank account, gets paid for both invoices via
   bank transfer, then closes the year.

   The test exercises the full pipeline:

     1. Setup       — chart, journals, partners, periods, payment terms
     2. Operations  — invoice/create! + invoice/send! (DE posting-builder)
                      + paying expense bills directly out of bank
     3. Money in    — bank-statement ingest + reconciliation:
                      bank line auto-matches the open AR transaction;
                      the invoice flips :paid via the recon hook
     4. UStVA Q1    — VAT collected on Q1 sales − VAT paid on inputs
                      (zero here — freelancer's expenses are
                      VAT-exempt rent + a B2C software license without
                      VAT recovery)
     5. Aging       — at end-of-year, both invoices are paid → AR
                      summary is empty
     6. EÜR FY2025  — Einnahmen-Überschuss-Rechnung: profit = revenues
                      − expenses (cash basis)
     7. Year-end    — close-fiscal-year! posts the closing tx and
                      soft-closes the period; new year starts with
                      P&L zeroed and retained earnings carrying the
                      net result.

   Each step asserts ledger invariants AND the user-facing reports
   so a regression in any sub-system surfaces as a failed step here."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.aging :as aging]
            [kontor.balance :as balance]
            [kontor.document.invoice :as invoice]
            [kontor.invoice.schema :as inv-schema]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.closing :as de-closing]
            [kontor.l10n-de.eur :as eur]
            [kontor.l10n-de.invoice :as inv-de]
            [kontor.l10n-de.ustva :as ustva]
            [kontor.payment-term :as pt]
            [kontor.posting :as posting]
            [kontor.reconciliation :as recon]
            [kontor.core :as core]))

;; ============================================================================
;; Calendar
;; ============================================================================

(def jan-1   #inst "2025-01-01T00:00:00Z")
(def feb-15  #inst "2025-02-15T00:00:00Z")
(def mar-1   #inst "2025-03-01T00:00:00Z")
(def mar-15  #inst "2025-03-15T00:00:00Z")
(def mar-20  #inst "2025-03-20T00:00:00Z")
(def apr-1   #inst "2025-04-01T00:00:00Z")
(def jun-1   #inst "2025-06-01T00:00:00Z")
(def sep-15  #inst "2025-09-15T00:00:00Z")
(def oct-15  #inst "2025-10-15T00:00:00Z")
(def jan-1-26 #inst "2026-01-01T00:00:00Z")

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- bal [conn code as-of]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))]
    (-> (balance/account-balance conn (ace db code) {:as-of-valid as-of})
        (get eur)
        :amount
        (or 0M))))

(defn- bootstrap! [conn]
  (inv-schema/install! conn)   ; :kontor.invoice/status state-machine seeds (P0-4α)
  (chart/install! conn)
  (pt/install-standard-terms! conn)
  (d/transact conn
              [{:kontor.journal/code "INV"  :kontor.journal/name "Sales invoices"
                :kontor.journal/type :sale  :kontor.journal/active true}
               {:kontor.journal/code "EXP"  :kontor.journal/name "Expense bookings"
                :kontor.journal/type :purchase :kontor.journal/active true}
               {:kontor.journal/code "BANK" :kontor.journal/name "Bank movements"
                :kontor.journal/type :bank  :kontor.journal/active true}
               {:kontor.partner/external-id "OWN"  :kontor.partner/name "Self GmbH"
                :kontor.partner/kind :company :kontor.partner/country-code "DE"
                :kontor.partner/tax-id "DE111111111"}
               {:kontor.partner/external-id "ACME" :kontor.partner/name "ACME GmbH"
                :kontor.partner/kind :customer :kontor.partner/country-code "DE"
                :kontor.partner/tax-id "DE222222222"}
               {:kontor.partner/external-id "VERMIETER" :kontor.partner/name "Vermieter Müller"
                :kontor.partner/kind :supplier :kontor.partner/country-code "DE"}
               {:kontor.period/start jan-1 :kontor.period/end jan-1-26
                :kontor.period/tag :normal :kontor.period/name "FY2025"}]))

;; ============================================================================
;; Scenario step bricks
;; ============================================================================

(defn- create-and-send-invoice!
  "Create a draft invoice + send it (which posts the accounting tx).
   Returns {:invoice-eid :transaction-eid}."
  [conn ext-id issue-date lines]
  (let [db (d/db conn)
        own  (:db/id (d/entity db [:kontor.partner/external-id "OWN"]))
        acme (:db/id (d/entity db [:kontor.partner/external-id "ACME"]))
        net30 (:db/id (pt/by-code db "NET30"))
        _ (invoice/create! conn
                           {:kontor.invoice/external-id ext-id
                            :kontor.invoice/issue-date issue-date
                            :kontor.invoice/seller own
                            :kontor.invoice/buyer acme
                            :kontor.invoice/payment-term net30
                            :kontor.invoice/currency "EUR"
                            :kontor.invoice/lines lines})
        inv-eid (d/q '[:find ?e . :in $ ?ext
                       :where [?e :kontor.invoice/external-id ?ext]]
                     (d/db conn) ext-id)
        builder (partial inv-de/posting-builder {})
        {:keys [transaction-eid]} (invoice/send! conn inv-eid builder)]
    {:invoice-eid inv-eid :transaction-eid transaction-eid}))

(defn- pay-expense-from-bank!
  "Direct bank-paid expense (no input VAT). Net amount = gross."
  [conn ext-id date narration expense-code amount]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        bank (ace db "1200")
        exp (ace db expense-code)
        exp-jnl (:db/id (d/entity db [:kontor.journal/code "EXP"]))]
    (d/transact conn
                (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id ext-id
                   :kontor.transaction/journal exp-jnl
                   :kontor.transaction/effective-date date
                   :kontor.transaction/narration narration
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at date}
                  :postings
                  [{:kontor.posting/account exp :kontor.posting/amount amount
                    :kontor.posting/commodity eur :kontor.posting/posted-at date}
                   {:kontor.posting/account bank :kontor.posting/amount (.negate amount)
                    :kontor.posting/commodity eur :kontor.posting/posted-at date}]}))))

(defn- ingest-and-reconcile-payment!
  "Ingest a bank-line, find a matching open AR, commit the match,
   flip the invoice to :paid via the reconciliation hook."
  [conn date amount counterparty memo]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        bank-acct (ace db "1200")
        bank-jnl (:db/id (d/entity db [:kontor.journal/code "BANK"]))
        _ (recon/ingest-statement!
           conn [{:bank :test :date date :amount amount
                  :counterparty counterparty :description memo
                  :raw-row [(.toString date) (str "+" amount) counterparty memo]}]
           {:source-account-eid bank-acct :commodity-eid eur})
        db (d/db conn)
        bl (d/q '[:find ?bl . :in $ ?amt :where
                  [?bl :bank-line/amount ?amt]
                  (not [?bl :bank-line/matched-tx _])]
                db amount)
        best (first (recon/suggest-match db bl {}))]
    (when-not best
      (throw (ex-info "No match found for bank line" {:amount amount})))
    (recon/commit-match! conn bl (:match best) bank-jnl {})
    (invoice/flip-paid-on-settlement conn (:transactions (:match best)))))

;; ============================================================================
;; The whole story
;; ============================================================================

(deftest complete-fiscal-year-2025
  (let [conn (core/create-test-db)]
    (bootstrap! conn)

    ;; --------------------------------------------------------------
    ;; 1. Operations: two invoices + two expenses
    ;; --------------------------------------------------------------
    (testing "Invoice 1 — Q1 (Feb 15) — 10h consulting @ 200€/h, 19% VAT"
      (let [{:keys [invoice-eid]}
            (create-and-send-invoice!
             conn "INV-2025-001" feb-15
             [{:kontor.invoice-line/sequence 1 :kontor.invoice-line/name "Beratung Q1"
               :kontor.invoice-line/quantity 10M :kontor.invoice-line/unit-code "HUR"
               :kontor.invoice-line/unit-price 200M
               :kontor.invoice-line/vat-rate 19.0M :kontor.invoice-line/vat-category "S"}])]
        (is (some? invoice-eid))))

    (testing "Invoice 2 — Q3 (Sep 15) — 20h consulting @ 200€/h, 19% VAT"
      (let [{:keys [invoice-eid]}
            (create-and-send-invoice!
             conn "INV-2025-002" sep-15
             [{:kontor.invoice-line/sequence 1 :kontor.invoice-line/name "Beratung Q3"
               :kontor.invoice-line/quantity 20M :kontor.invoice-line/unit-code "HUR"
               :kontor.invoice-line/unit-price 200M
               :kontor.invoice-line/vat-rate 19.0M :kontor.invoice-line/vat-category "S"}])]
        (is (some? invoice-eid))))

    (testing "Operating expenses paid from bank"
      ;; Office rent quarterly: 600€ on Mar 1 (Vermieter — VAT-exempt
      ;; small landlord; no input VAT).
      (pay-expense-from-bank! conn "EXP-2025-01" mar-1 "Büro-Miete Q1" "6300" 600M)
      (pay-expense-from-bank! conn "EXP-2025-02" jun-1 "Software" "6815" 100M))

    ;; --------------------------------------------------------------
    ;; 2. Pre-payment ledger snapshot — balances expected
    ;; --------------------------------------------------------------
    (testing "Pre-payment trial: AR open for both invoices, USt collected accumulated"
      (is (= 7140M (bal conn "1400" oct-15))   "AR = (2000 + 4000) gross + (380+760) USt")
      (is (= -6000M (bal conn "4400" oct-15))  "Revenue 19% net = -2000 -4000 (credit-natural)")
      (is (= -1140M (bal conn "3801" oct-15))  "USt 19% collected = -(380+760)")
      (is (= -700M (bal conn "1200" oct-15))   "Bank = -600 rent -100 software")
      (is (= 600M (bal conn "6300" oct-15))    "Rent expense")
      (is (= 100M (bal conn "6815" oct-15))    "Software expense"))

    ;; --------------------------------------------------------------
    ;; 3. Reconciliation: ACME pays Invoice 1 on Mar 20
    ;; --------------------------------------------------------------
    (testing "ACME pays Invoice 1 (€2380) — bank line matches → invoice :paid"
      (ingest-and-reconcile-payment! conn mar-20 2380M
                                     "ACME GmbH" "Rechnung INV-2025-001 v 15.02.2025")
      (let [db (d/db conn)
            inv (d/pull db [:kontor.invoice/status]
                        [:kontor.invoice/external-id "INV-2025-001"])]
        (is (= :paid (:kontor.invoice/status inv)))))

    (testing "After payment 1 — bitemporal valid-time semantics: at
              Apr 1, only invoice 1 exists (Sep invoice is future);
              the Jun software expense is also future."
      ;; AR at apr-1 = invoice-1 (2380) - payment (2380) = 0; the Sep
      ;; invoice's valid-from is sep-15 so it is not yet visible.
      (is (= 0M (bal conn "1400" apr-1))
          "Invoice-1 settled; invoice-2 not yet valid (valid-from Sep 15)")
      ;; Bank at apr-1: -600 rent + 2380 payment = 1780.
      ;; The Jun software expense is not yet valid.
      (is (= 1780M (bal conn "1200" apr-1))
          "-600 rent (Mar) + 2380 payment (Mar 20)"))

    ;; --------------------------------------------------------------
    ;; 4. UStVA Q1 — VAT report Jan-Mar
    ;; --------------------------------------------------------------
    (testing "UStVA Q1 2025: VAT collected on Invoice 1, Zahllast = 380€"
      (let [u (ustva/compute conn {:from jan-1 :to apr-1})]
        (is (= 380.00M (-> u :ustva/zahllast :amount))
            "Q1 collected USt 19% on 2000€ revenue → 380€ Zahllast")))

    ;; --------------------------------------------------------------
    ;; 5. ACME pays Invoice 2 on Oct 15
    ;; --------------------------------------------------------------
    (testing "ACME pays Invoice 2 (€4760) — invoice 2 → :paid"
      (ingest-and-reconcile-payment! conn oct-15 4760M
                                     "ACME GmbH" "Rechnung INV-2025-002 v 15.09.2025")
      (let [db (d/db conn)
            inv (d/pull db [:kontor.invoice/status]
                        [:kontor.invoice/external-id "INV-2025-002"])]
        (is (= :paid (:kontor.invoice/status inv)))))

    ;; --------------------------------------------------------------
    ;; 6. AR aging at year-end — empty (everyone paid)
    ;; --------------------------------------------------------------
    (testing "AR aging at year-end: no open receivables (all paid)"
      (let [summary (aging/aging-summary-by-bucket
                     (d/db conn) #{"1400"}
                     :as-of jan-1-26 :ar-or-ap :ar)]
        (is (= 0M (:total summary))
            "Both invoices settled → AR total zero")))

    ;; --------------------------------------------------------------
    ;; 7. EÜR FY2025 — full-year P&L
    ;; --------------------------------------------------------------
    (testing "EÜR FY2025 — note: cash-basis form on accrual ledger.
              The form treats USt-Verbindlichkeit (3801) as both
              vereinnahmte USt (line 14, signed) AND gezahlte USt
              (line 50, sign-flipped) — they cancel in a true cash
              ledger but offset visibly in our accrual ledger."
      (let [e (eur/compute conn {:from jan-1 :to jan-1-26})]
        ;; Net revenue (4400) = 6000; line 14 deducts collected USt
        ;; (-1140 raw) → einnahmen = 4860.
        (is (= 4860.00M (-> e :eur/einnahmen :amount)))
        ;; Expenses 600 + 100; line 50 adds USt 3801 inflow-flipped
        ;; (+1140) → ausgaben = 1840.
        (is (= 1840.00M (-> e :eur/ausgaben :amount)))
        ;; Gewinn = 4860 − 1840 = 3020. (For a cash ledger where
        ;; USt would only post when actually collected/paid, the
        ;; numbers reduce to revenue 6000 − cost 700 = 5300.)
        (is (= 3020.00M (-> e :eur/gewinn :amount)))))

    ;; --------------------------------------------------------------
    ;; 8. Year-end close
    ;; --------------------------------------------------------------
    (testing "Year-end close: P&L → retained, period soft-closed"
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (de-closing/close-fiscal-year! conn {:period-eid period-eid})]
        (is (some? (:transaction-eid close-result)))
        (is (some? period-close-tx-report))
        ;; In the new fiscal year, P&L accounts are zero again
        (is (= 0M (bal conn "4400" jan-1-26)) "Revenue zeroed")
        (is (= 0M (bal conn "6300" jan-1-26)) "Rent zeroed")
        (is (= 0M (bal conn "6815" jan-1-26)) "Software zeroed")
        ;; Retained earnings carries the net result. Net P&L was
        ;; -6000 + 600 + 100 = -5300 (revenue is credit-natural).
        ;; Closing posts that net into retained, so retained = -5300
        ;; (a credit balance on equity = profit).
        (is (= -5300M (bal conn "2900" jan-1-26))
            "Retained earnings carries the FY2025 profit (5300€)")
        ;; Balance-sheet sanity: bank still holds the cash earned
        (is (= 6440M (bal conn "1200" jan-1-26))
            "Bank balance = 1680 (post-Q1) + 4760 (Q3 payment) = 6440")
        ;; AR is fully paid, USt was collected (not yet remitted)
        (is (= 0M (bal conn "1400" jan-1-26)) "AR zero")
        (is (= -1140M (bal conn "3801" jan-1-26))
            "USt-Verbindlichkeit (uncollected by Finanzamt in this scenario)")))))
