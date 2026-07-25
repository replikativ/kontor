(ns kontor.invoice-test
  "End-to-end kernel :invoice lifecycle test.

   - create! a draft, send! it (auto-creates accounting tx via
     l10n-de.invoice/posting-builder), verify the trial balance
     reflects it
   - simulate a bank receipt + reconciliation; verify the invoice
     flips to :paid via flip-paid-on-settlement
   - cancel! a sent invoice; verify the reversal tx exists and
     points back via :kontor.transaction/reverses"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.document.invoice :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.invoice :as inv-de]
            [kontor.banking.payment-term :as pt]
            [kontor.banking.reconciliation :as recon]
            [kontor.reporting.balance :as balance]
            [kontor.validation :as v]))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-31 #inst "2026-01-31T00:00:00Z")
(def feb-5  #inst "2026-02-05T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (inv-schema/install! conn)   ; modules/invoice schema + :kontor.invoice/status state-machine seeds (P0-4α)
    (chart/install! conn)
    (pt/install-standard-terms! conn)
    (d/transact conn
                [{:kontor.journal/code "INV"  :kontor.journal/name "Sales invoices"
                  :kontor.journal/type :sale  :kontor.journal/active true}
                 {:kontor.journal/code "BANK" :kontor.journal/name "Bank movements"
                  :kontor.journal/type :bank  :kontor.journal/active true}
                 {:kontor.partner/external-id "OWN"  :kontor.partner/name "Self GmbH"
                  :kontor.partner/kind :company :kontor.partner/country-code "DE"
                  :kontor.partner/tax-id "DE123456789"}
                 {:kontor.partner/external-id "ACME" :kontor.partner/name "ACME GmbH"
                  :kontor.partner/kind :customer :kontor.partner/country-code "DE"
                  :kontor.partner/tax-id "DE987654321"}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- make-draft! [conn]
  (let [db (d/db conn)
        own  (:db/id (d/entity db [:kontor.partner/external-id "OWN"]))
        acme (:db/id (d/entity db [:kontor.partner/external-id "ACME"]))
        net30 (:db/id (pt/by-code db "NET30"))]
    (inv/create! conn
                 {:kontor.invoice/external-id "INV-2026-0001"
                  :kontor.invoice/issue-date  jan-1
                  :kontor.invoice/seller      own
                  :kontor.invoice/buyer       acme
                  :kontor.invoice/payment-term net30
                  :kontor.invoice/currency    "EUR"
                  :kontor.invoice/buyer-reference "PO-12345"
                  :kontor.invoice/notes       ["Zahlbar 30 Tage netto."]
                  :kontor.invoice/lines
                  [{:kontor.invoice-line/sequence 1
                    :kontor.invoice-line/name "Strategieberatung"
                    :kontor.invoice-line/description "10 h Beratung Q1 2026"
                    :kontor.invoice-line/quantity 10M
                    :kontor.invoice-line/unit-code "HUR"
                    :kontor.invoice-line/unit-price 150M
                    :kontor.invoice-line/vat-rate 19.0M
                    :kontor.invoice-line/vat-category "S"}
                   {:kontor.invoice-line/sequence 2
                    :kontor.invoice-line/name "Reisekosten"
                    :kontor.invoice-line/description "Bahnticket Berlin-München"
                    :kontor.invoice-line/quantity 1M
                    :kontor.invoice-line/unit-code "EA"
                    :kontor.invoice-line/unit-price 89.50M
                    :kontor.invoice-line/vat-rate 19.0M
                    :kontor.invoice-line/vat-category "S"}]})))

;; ============================================================================
;; create!
;; ============================================================================

(deftest create-builds-draft-with-totals-and-due-date
  (let [conn (bootstrap)
        _ (make-draft! conn)
        db (d/db conn)
        inv (d/pull db '[*] [:kontor.invoice/external-id "INV-2026-0001"])]
    (is (= :draft (:kontor.invoice/status inv)))
    (is (= "EUR"  (:kontor.invoice/currency inv)))
    (is (= 1589.50M (:kontor.invoice/total-net inv))   "10×150 + 89.50")
    (is (= 302.00M  (:kontor.invoice/total-vat inv))   "1589.50 × 19% bankers-rounded")
    (is (= 1891.50M (:kontor.invoice/total-gross inv)))
    (is (= jan-31 (:kontor.invoice/due-date inv))      "Jan 1 + 30 days")
    (is (= 2 (count (:kontor.invoice/lines inv))))))

(deftest create-rejects-missing-required-fields
  (let [conn (bootstrap)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/create! conn {:kontor.invoice/issue-date jan-1
                                    :kontor.invoice/lines []})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/create! conn {:kontor.invoice/external-id "X"
                                    :kontor.invoice/issue-date jan-1
                                    :kontor.invoice/lines []})))))

;; ============================================================================
;; send!
;; ============================================================================

(deftest send-creates-accounting-transaction
  (let [conn (bootstrap)
        _ (make-draft! conn)
        db (d/db conn)
        inv-eid (d/q '[:find ?e .
                       :where [?e :kontor.invoice/external-id "INV-2026-0001"]]
                     db)
        builder (partial inv-de/posting-builder {})
        {:keys [transaction-eid]} (inv/send! conn inv-eid builder)
        db (d/db conn)
        ;; Verify invoice flipped to :sent + has :transaction backref
        inv (d/pull db '[*] inv-eid)
        ;; Verify the trial balance for INV-2026-0001
        rows (d/q '[:find ?code ?amt
                    :in $ ?tx
                    :where
                    [?p :kontor.posting/transaction ?tx]
                    [?p :kontor.posting/account ?a]
                    [?a :kontor.account/code ?code]
                    [?p :kontor.posting/amount ?amt]]
                  db transaction-eid)
        by-code (into {} (map (juxt first second) rows))]
    (is (= :sent (:kontor.invoice/status inv)))
    ;; Transition timestamp now lives in :status-history via kbt
    (is (= transaction-eid (-> inv :kontor.invoice/transaction :db/id)))
    ;; Receivable +1891.50, Revenue -1589.50, USt -302.00
    (is (= 1891.50M (get by-code "1400")))
    (is (= -1589.50M (get by-code "4400")))
    (is (= -302.00M (get by-code "3801")))
    ;; Sums to zero
    (is (zero? (.signum ^java.math.BigDecimal
                (reduce #(.add ^java.math.BigDecimal %1 %2)
                        0M (vals by-code)))))))

(deftest send-rejects-non-draft-invoices
  (let [conn (bootstrap)
        _ (make-draft! conn)
        db (d/db conn)
        inv-eid (d/q '[:find ?e .
                       :where [?e :kontor.invoice/external-id "INV-2026-0001"]]
                     db)
        builder (partial inv-de/posting-builder {})]
    (inv/send! conn inv-eid builder)
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/send! conn inv-eid builder))
        "double-send should error")))

;; ============================================================================
;; mark-paid! + reconciliation hook
;; ============================================================================

(deftest reconciliation-hook-flips-invoice-to-paid
  (testing "After a bank-line reconciliation settles the invoice's
            transaction, flip-paid-on-settlement marks the invoice
            :paid with :paid-at."
    (let [conn (bootstrap)
          _ (make-draft! conn)
          db (d/db conn)
          inv-eid (d/q '[:find ?e .
                         :where [?e :kontor.invoice/external-id "INV-2026-0001"]]
                       db)
          builder (partial inv-de/posting-builder {})
          {:keys [transaction-eid]} (inv/send! conn inv-eid builder)
          db (d/db conn)
          bank-acct (ace db "1200")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          bank-jnl (:db/id (d/entity db [:kontor.journal/code "BANK"]))
          ;; Ingest a matching bank line
          _ (recon/ingest-statement! conn
                                     [{:bank :test :date feb-5 :amount 1891.50M
                                       :counterparty "ACME GmbH"
                                       :description "Rechnung INV-2026-0001"
                                       :raw-row ["02/05/2026" "+1891,50" "ACME" "INV-001"]}]
                                     {:source-account-eid bank-acct
                                      :commodity-eid eur})
          db (d/db conn)
          [bl] (d/q '[:find [?bl] :where [?bl :kontor.bank-line/amount 1891.50M]] db)
          best (first (recon/suggest-match db bl {}))
          ;; ADR-161: :applied-by-uid is REQUIRED when the settled
          ;; transactions have invoices behind them — commit-match! now writes
          ;; the :payment-application subledger row that dunning reads, and it
          ;; refuses to write it unattributed.
          actor (:db/id (d/entity db [:kontor.partner/external-id "OWN"]))
          _ (recon/commit-match! conn bl (:match best) bank-jnl
                                 {:applied-by-uid actor})
          settled-tx-eids (:transactions (:match best))
          ;; ADR-161: the status is already :paid — commit-match! derived it
          ;; from the residual in the same commit. flip-paid-on-settlement is
          ;; now the GL-only bridge for settlements written outside this path,
          ;; and is a no-op here (idempotent).
          mid-status (:kontor.invoice/status
                      (d/pull (d/db conn) [:kontor.invoice/status] inv-eid))
          _ (inv/flip-paid-on-settlement conn settled-tx-eids)
          db (d/db conn)
          inv (d/pull db [:kontor.invoice/status] inv-eid)
          bal (fn [code]
                (or (some-> (get (balance/account-balance conn (ace db code)) eur)
                            :amount)
                    0M))]
      (is (= :paid mid-status)
          "ADR-161: commit-match! itself derived :paid from the residual —
           the invoice does not need a second call to look settled")
      (is (= :paid (:kontor.invoice/status inv))
          "and the GL-only bridge is idempotent on top of it")
      ;; The status keyword used to be a PROXY: `flip-paid-on-settlement` set it
      ;; independently of whether the reconciliation posted anything, so a
      ;; commit that moved no money — or moved it to the wrong account — read
      ;; :paid all the same (note 198 audit). It is now derived from the
      ;; subledger residual, but still assert the MONEY.
      ;; INV-2026-0001 is 1891.50 gross (1589.50 net + 302.00 USt), so the
      ;; bank receipt of 1891.50 leaves: bank 1200 = +1891.50, receivable
      ;; 1400 = 1891.50 − 1891.50 = 0.00.
      (testing "and the ledger actually moved: bank up, receivable cleared"
        (is (= 0 (.compareTo 1891.50M (bal "1200")))
            "the bank account carries the receipt")
        (is (= 0 (.compareTo 0M (bal "1400")))
            "the receivable is fully relieved"))
      (testing "and the AR subledger ties to the receivable control account"
        (let [tie (recon/ar-tie-out conn {:ar-codes #{"1400"} :commodity eur})]
          (is (= 0 (.compareTo 0M (:subledger tie))))
          (is (= 0 (.compareTo 0M (:gl tie))))
          (is (:ok? tie) (str "AR tie-out broken: " tie)))))))

;; ============================================================================
;; cancel!
;; ============================================================================

(deftest cancel-draft-just-flips-status
  (let [conn (bootstrap)
        _ (make-draft! conn)
        db (d/db conn)
        inv-eid (d/q '[:find ?e .
                       :where [?e :kontor.invoice/external-id "INV-2026-0001"]]
                     db)]
    (inv/cancel! conn inv-eid)
    (let [inv (d/pull (d/db conn) [:kontor.invoice/status] inv-eid)]
      (is (= :cancelled (:kontor.invoice/status inv))))))

(deftest cancel-sent-creates-reversal-transaction
  (let [conn (bootstrap)
        _ (make-draft! conn)
        db (d/db conn)
        inv-eid (d/q '[:find ?e .
                       :where [?e :kontor.invoice/external-id "INV-2026-0001"]]
                     db)
        builder (partial inv-de/posting-builder {})
        {:keys [transaction-eid]} (inv/send! conn inv-eid builder)
        _ (inv/cancel! conn inv-eid)
        db (d/db conn)
        ;; Find the reversal — it has :kontor.transaction/reverses → original
        reversal-eid (d/q '[:find ?r .
                            :in $ ?orig
                            :where [?r :kontor.transaction/reverses ?orig]]
                          db transaction-eid)
        ;; Sum all postings on AR — should be net zero (orig +1891.50, reversal -1891.50)
        ar-eid (ace db "1400")
        ar-sum (reduce
                (fn [^java.math.BigDecimal a [_ amt]] (.add a amt))
                0M
                (d/q '[:find ?p ?amt
                       :in $ ?ar [?tx ...]
                       :where
                       [?p :kontor.posting/transaction ?tx]
                       [?p :kontor.posting/account ?ar]
                       [?p :kontor.posting/amount ?amt]]
                     db ar-eid [transaction-eid reversal-eid]))]
    (is (some? reversal-eid))
    (is (= 0M ar-sum) "reversal cancels out the original on AR")
    (let [inv (d/pull (d/db conn) [:kontor.invoice/status] inv-eid)]
      (is (= :cancelled (:kontor.invoice/status inv))))))
