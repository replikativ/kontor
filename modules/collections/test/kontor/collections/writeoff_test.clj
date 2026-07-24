(ns kontor.collections.writeoff-test
  "Tests for the bad-debt write-off transactor — ADR-043 commit 5/5.

   Walks a case → :legal → :written-off and verifies:
     - Status-machine transitions are recorded with audit metadata.
     - A balanced kernel :transaction is posted Dr Bad-Debt-Expense
       / Cr AR.
     - The :kontor.collection-case/closed-at is set.
     - :supporting-doc is required (smoke-tests :missing-supporting
       throw)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.banking.payment-application :as papp]
            [kontor.banking.reconciliation :as recon]
            [kontor.compliance.audit-doc :as adoc]
            [kontor.collections.case :as kcase]
            [kontor.collections.schema :as coll-schema]
            [kontor.collections.writeoff :as kwo]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.posting :as posting]
            [kontor.validation :as validation]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (inv-schema/install! *conn*)
    (coll-schema/install! *conn*)
    (d/transact *conn*
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                 {:kontor.entity/code "ACME-DE" :kontor.entity/name "Acme GmbH"
                  :kontor.entity/kind :operating :kontor.entity/active true}
                 {:kontor.partner/external-id "CUST"
                  :kontor.partner/name "Customer Co"
                  :kontor.partner/kind :customer}
                 {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice"}
                 {:kontor.partner/external-id "U-bob" :kontor.partner/name "Bob"}
                 ;; Accounts
                 {:kontor.account/code "1200" :kontor.account/path "1200"
                  :kontor.account/name "Accounts Receivable"
                  :kontor.account/type :asset}
                 {:kontor.account/code "6900" :kontor.account/path "6900"
                  :kontor.account/name "Bad Debt Expense"
                  :kontor.account/type :expense}
                 ;; GL defaults (tenant-wide)
                 {:kontor.gl-account-default/account-type :ar
                  :kontor.gl-account-default/account [:kontor.account/path "1200"]}
                 {:kontor.gl-account-default/account-type :bad-debt-expense
                  :kontor.gl-account-default/account [:kontor.account/path "6900"]}
                 {:kontor.account/code "4000" :kontor.account/path "4000"
                  :kontor.account/name "Revenue"
                  :kontor.account/type :income}
                 ;; Journal
                 {:kontor.journal/code "SALES" :kontor.journal/name "Sales Journal"
                  :kontor.journal/type :sales}
                 ;; :sale (singular) is the type kernel open-item discovery
                 ;; keys on — `reconciliation/open-receivables-by-tx`.
                 {:kontor.journal/code "SALE" :kontor.journal/name "Sales (kernel type)"
                  :kontor.journal/type :sale}])
    (f)))

(use-fixtures :each bootstrap)

(defn- partner [xid]
  (d/q '[:find ?p . :in $ ?x :where [?p :kontor.partner/external-id ?x]]
       (d/db *conn*) xid))

(defn- entity [c]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.entity/code ?c]]
       (d/db *conn*) c))

(defn- actor [u] (partner (str "U-" u)))

(defn- make-invoice! [external-id gross]
  (let [inv-tempid "inv-1"
        line-tempid "line-1"]
    (d/transact *conn*
                [{:db/id inv-tempid
                  :kontor.invoice/external-id external-id
                  :kontor.invoice/type :sales
                  :kontor.invoice/status :sent
                  :kontor.invoice/issue-date #inst "2026-04-01"
                  :kontor.invoice/buyer (partner "CUST")
                  :kontor.invoice/entity (entity "ACME-DE")
                  :kontor.invoice/currency "EUR"
                  :kontor.invoice/total-gross gross
                  :kontor.invoice/lines [line-tempid]}
                 {:db/id line-tempid
                  :kontor.invoice-line/invoice inv-tempid
                  :kontor.invoice-line/sequence 1
                  :kontor.invoice-line/amount gross
                  :kontor.invoice-line/quantity 1M
                  :kontor.invoice-line/unit-price gross}])
    (d/q '[:find ?e . :in $ ?xid :where [?e :kontor.invoice/external-id ?xid]]
         (d/db *conn*) external-id)))

(defn- acct [code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] (d/db *conn*) code))

(defn- make-posted-invoice!
  "An invoice that actually HIT THE GL: Dr AR / Cr Revenue on a `:sale`
   journal, with `:kontor.invoice/transaction` wired back. Required for the
   kernel open-item + tie-out queries, which read postings, not the invoice
   entity."
  [external-id gross]
  (let [inv-eid (make-invoice! external-id gross)
        db  (d/db *conn*)
        eur (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] db)
        rpt (validation/transact-with-validation
             *conn*
             (posting/build-transaction
              {:transaction {:kontor.transaction/external-id external-id
                             :kontor.transaction/journal [:kontor.journal/code "SALE"]
                             :kontor.transaction/effective-date #inst "2026-04-01"
                             :kontor.transaction/partner (partner "CUST")
                             :kontor.transaction/state :posted
                             :kontor.transaction/posted-at #inst "2026-04-01"}
               :postings [{:kontor.posting/account (acct "1200")
                           :kontor.posting/amount gross
                           :kontor.posting/commodity eur
                           :kontor.posting/posted-at #inst "2026-04-01"}
                          {:kontor.posting/account (acct "4000")
                           :kontor.posting/amount (.negate ^java.math.BigDecimal gross)
                           :kontor.posting/commodity eur
                           :kontor.posting/posted-at #inst "2026-04-01"}]}))
        tx-eid (get (:tempids rpt) -1)]
    (d/transact *conn* [{:db/id inv-eid :kontor.invoice/transaction tx-eid}])
    {:invoice inv-eid :transaction tx-eid}))

(defn- ar-balance
  "GL balance of account 1200. `:with ?p` matters: `:find` has SET semantics,
   so two write-off legs of exactly −1000 collapse into one and the DOUBLE
   posting this test exists to catch would be invisible."
  []
  (or (d/q '[:find (sum ?amt) . :with ?p
             :where
             [?a :kontor.account/path "1200"]
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]]
           (d/db *conn*))
      0M))

(defn- bad-debt-balance []
  (or (d/q '[:find (sum ?amt) . :with ?p
             :where
             [?a :kontor.account/path "6900"]
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]]
           (d/db *conn*))
      0M))

(defn- drive-case-to-legal! [case-code]
  (kcase/advance-case-state! *conn*
                       {:case case-code :to :dunning-l1
                        :changed-by-uid (actor "alice")})
  (kcase/advance-case-state! *conn*
                       {:case case-code :to :dunning-l2
                        :changed-by-uid (actor "alice")})
  (kcase/advance-case-state! *conn*
                       {:case case-code :to :final-notice
                        :changed-by-uid (actor "alice")})
  (kcase/advance-case-state! *conn*
                       {:case case-code :to :legal
                        :changed-by-uid (actor "alice")
                        :reason :legal-escalation}))

(deftest write-off-posts-balanced-bad-debt-tx
  (testing "Write-off Dr Bad-Debt 1000 / Cr AR 1000; case :written-off"
    (let [_ (make-invoice! "INV-WO" 1000M)]
      (kcase/open-case! *conn*
                       {:code "CASE-WO"
                        :partner (partner "CUST")
                        :entity (entity "ACME-DE")
                        :opened-by-uid (actor "alice")})
      (drive-case-to-legal! "CASE-WO")
      ;; Create supporting-doc
      (adoc/create-doc!
       *conn* {:code "DOC-WO-1"
               :type :write-off-supporting
               :title "Write-off package"
               :uploaded-by-uid (actor "bob")
               :storage-uri "file:///doc/wo-1.pdf"
               :content-hash "sha256:placeholder"})
      (let [doc-eid (d/q '[:find ?d . :where [?d :kontor.audit-doc/code "DOC-WO-1"]]
                         (d/db *conn*))
            result (kwo/write-off-case!
                    *conn*
                    {:case "CASE-WO"
                     :written-off-by (actor "bob")
                     :journal-ref [:kontor.journal/code "SALES"]
                     :reason :uncollectible-90-days
                     :supporting-doc doc-eid})]
        (testing ":case-eid + :invoices-written-off + :total-written-off returned"
          (is (some? (:case-eid result)))
          (is (= 1 (:invoices-written-off result)))
          (is (= 0 (.compareTo 1000M
                               (:total-written-off result)))))
        (let [db (d/db *conn*)]
          (testing "case :state :written-off"
            (is (= :written-off
                   (sm/current-status db
                                      (kcase/by-code db "CASE-WO")
                                      :kontor.collection-case/state))))
          (testing "case :closed-at set"
            (let [c (kcase/pull-case db "CASE-WO")]
              (is (some? (:kontor.collection-case/closed-at c)))))
          (testing "Dr Bad-Debt-Expense = 1000"
            (is (= 0 (.compareTo 1000M
                                 (d/q '[:find (sum ?amt) .
                                        :where
                                        [?a :kontor.account/path "6900"]
                                        [?p :kontor.posting/account ?a]
                                        [?p :kontor.posting/amount ?amt]]
                                      db)))))
          (testing "Cr AR = -1000"
            (is (= 0 (.compareTo (bigdec "-1000")
                                 (d/q '[:find (sum ?amt) .
                                        :where
                                        [?a :kontor.account/path "1200"]
                                        [?p :kontor.posting/account ?a]
                                        [?p :kontor.posting/amount ?amt]]
                                      db)))))
          (testing "balance = 0"
            (is (= 0 (.compareTo 0M
                                 (d/q '[:find (sum ?amt) .
                                        :where [_ :kontor.posting/amount ?amt]]
                                      db))))))))))

;; ============================================================================
;; note 198 audit HIGH-3 — the write-off must relieve the SUBLEDGER too
;; ============================================================================

(deftest write-off-closes-the-ar-open-item-and-ties-out
  (testing "write-off-case! relieves the GL receivable AND closes the AR
            open item; the subledger and the control account still tie"
    (let [{:keys [invoice]} (make-posted-invoice! "INV-TIE" 1000M)
          eur (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db *conn*))
          tie #(recon/ar-tie-out *conn* {:ar-codes #{"1200"} :commodity eur})]

      (testing "baseline — one 1,000 invoice, subledger == GL"
        ;; Dr AR 1000 / Cr Revenue 1000 → GL AR = 1000; one open item of 1000.
        (let [t (tie)]
          (is (= 0 (.compareTo 1000M (:subledger t))))
          (is (= 0 (.compareTo 1000M (:gl t))))
          (is (:ok? t))))

      (kcase/open-case! *conn* {:code "CASE-TIE"
                                :partner (partner "CUST")
                                :entity (entity "ACME-DE")
                                :opened-by-uid (actor "alice")})
      (drive-case-to-legal! "CASE-TIE")
      (adoc/create-doc! *conn* {:code "DOC-TIE" :type :write-off-supporting
                                :title "WO package" :uploaded-by-uid (actor "bob")
                                :storage-uri "file:///wo-tie.pdf"
                                :content-hash "sha256:tie"})
      (let [doc (d/q '[:find ?d . :where [?d :kontor.audit-doc/code "DOC-TIE"]] (d/db *conn*))
            result (kwo/write-off-case! *conn*
                                        {:case "CASE-TIE"
                                         :written-off-by (actor "bob")
                                         :journal-ref [:kontor.journal/code "SALES"]
                                         :reason :uncollectible-90-days
                                         :supporting-doc doc})]
        (is (= 1 (:invoices-written-off result)))

        (testing "the GL receivable is relieved: 1000 − 1000 = 0"
          (is (= 0 (.compareTo 0M (ar-balance))))
          (is (= 0 (.compareTo 1000M (bad-debt-balance)))))

        (testing "and so is the SUBLEDGER — this is what HIGH-3 left behind"
          ;; open-amount = gross 1000 − applied (0 cash + 1000 write-off) = 0
          (is (= 0 (.compareTo 0M (papp/open-amount-of-invoice (d/db *conn*) invoice))))
          (is (= :paid (:kontor.invoice/status
                        (d/pull (d/db *conn*) [:kontor.invoice/status] invoice)))))

        (testing "subledger and control account still tie at zero"
          (let [t (tie)]
            (is (= 0 (.compareTo 0M (:subledger t))))
            (is (= 0 (.compareTo 0M (:gl t))))
            (is (:ok? t) (str "AR tie-out broken: " t))))))))

(deftest a-second-write-off-of-the-same-invoice-is-a-no-op
  (testing "a SECOND collections case over the same partner cannot write the
            same invoice off again — the open item is already closed"
    (make-posted-invoice! "INV-TWICE" 1000M)
    (let [doc (fn [c] (d/q '[:find ?d . :in $ ?c :where [?d :kontor.audit-doc/code ?c]]
                           (d/db *conn*) c))
          ;; Only ONE case may be open per (partner, entity), so the second
          ;; case can only be opened after the first has closed — exactly the
          ;; sequence a real second collections cycle follows.
          wo! (fn [case-code doc-code]
                (kcase/open-case! *conn* {:code case-code
                                          :partner (partner "CUST")
                                          :entity (entity "ACME-DE")
                                          :opened-by-uid (actor "alice")})
                (drive-case-to-legal! case-code)
                (adoc/create-doc! *conn* {:code doc-code :type :write-off-supporting
                                          :title "WO" :uploaded-by-uid (actor "bob")
                                          :storage-uri (str "file:///" doc-code ".pdf")
                                          :content-hash (str "sha256:" doc-code)})
                (kwo/write-off-case! *conn*
                                     {:case case-code
                                      :written-off-by (actor "bob")
                                      :journal-ref [:kontor.journal/code "SALES"]
                                      :reason :uncollectible-90-days
                                      :supporting-doc (doc doc-code)}))
          first-run  (wo! "CASE-A" "DOC-A")
          second-run (wo! "CASE-B" "DOC-B")]
      (is (= 1 (:invoices-written-off first-run)))
      (testing "the second case finds nothing open and posts nothing"
        (is (= 0 (:invoices-written-off second-run)))
        (is (= 0 (.compareTo 0M (:total-written-off second-run)))))
      (testing "the ledger carries ONE write-off, not two"
        ;; Before the fix: AR −2,000 and bad-debt +2,000 against a 1,000
        ;; invoice, while the subledger still read 1,000 open.
        (is (= 0 (.compareTo 0M (ar-balance))) "AR is 0, not −1,000")
        (is (= 0 (.compareTo 1000M (bad-debt-balance))) "bad debt is 1,000, not 2,000"))
      (testing "the second case still closes cleanly"
        (is (= :written-off
               (sm/current-status (d/db *conn*)
                                  (kcase/by-code (d/db *conn*) "CASE-B")
                                  :kontor.collection-case/state)))))))

(deftest write-off-rejects-missing-supporting-doc
  (testing "write-off-case! throws when :supporting-doc is missing"
    (kcase/open-case! *conn*
                     {:code "CASE-WO-NO-DOC"
                      :partner (partner "CUST")
                      :entity (entity "ACME-DE")
                      :opened-by-uid (actor "alice")})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":supporting-doc required"
         (kwo/write-off-case!
          *conn*
          {:case "CASE-WO-NO-DOC"
           :written-off-by (actor "bob")
           :journal-ref [:kontor.journal/code "SALES"]
           :reason :uncollectible})))))

(deftest end-to-end-collection-case-full-cycle
  ;; Showcase the whole lifecycle in one test.
  (testing "Open → dunning-l1 → dunning-l2 → final-notice → legal →
            written-off, with status-history recording each step"
    (make-invoice! "INV-E2E" 2000M)
    (kcase/open-case! *conn*
                     {:code "CASE-E2E"
                      :partner (partner "CUST")
                      :entity (entity "ACME-DE")
                      :opened-by-uid (actor "alice")
                      :strategy :reminder-only})
    (kcase/advance-case-state! *conn*
                         {:case "CASE-E2E" :to :dunning-l1
                          :changed-by-uid (actor "alice")
                          :reason :dunning-l1-sent})
    (kcase/advance-case-state! *conn*
                         {:case "CASE-E2E" :to :dunning-l2
                          :changed-by-uid (actor "alice")
                          :reason :dunning-l2-sent})
    (kcase/advance-case-state! *conn*
                         {:case "CASE-E2E" :to :final-notice
                          :changed-by-uid (actor "alice")
                          :reason :final-notice-sent})
    (kcase/advance-case-state! *conn*
                         {:case "CASE-E2E" :to :legal
                          :changed-by-uid (actor "alice")
                          :reason :legal-escalation})
    (adoc/create-doc! *conn* {:code "DOC-E2E"
                              :type :write-off-supporting
                              :uploaded-by-uid (actor "bob")
                              :title "WO package"
                              :storage-uri "file:///wo.pdf"
                              :content-hash "sha256:test"})
    (let [doc (d/q '[:find ?d . :where [?d :kontor.audit-doc/code "DOC-E2E"]]
                   (d/db *conn*))]
      (kwo/write-off-case!
       *conn*
       {:case "CASE-E2E"
        :written-off-by (actor "bob")
        :journal-ref [:kontor.journal/code "SALES"]
        :reason :uncollectible-by-counsel
        :supporting-doc doc}))
    (let [db (d/db *conn*)
          eid (kcase/by-code db "CASE-E2E")
          history (sm/status-history-of db eid :kontor.collection-case/state)]
      (testing "Six history rows: open, l1, l2, final, legal, written-off"
        (is (= 6 (count history))))
      (testing "Final state :written-off"
        (is (= :written-off
               (sm/current-status db eid :kontor.collection-case/state))))
      (testing "Status-history captures reasons across the chain"
        (let [reasons (mapv :kontor.status-history/reason history)]
          (is (some #{:case-opened} reasons))
          (is (some #{:dunning-l1-sent} reasons))
          (is (some #{:legal-escalation} reasons))
          (is (some #{:uncollectible-by-counsel} reasons))))
      (testing "GL = 0 (balanced posting)"
        (is (= 0 (.compareTo 0M
                             (d/q '[:find (sum ?amt) .
                                    :where [_ :kontor.posting/amount ?amt]]
                                  db))))))))
