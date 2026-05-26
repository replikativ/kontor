(ns kontor.collections.writeoff-test
  "Tests for the bad-debt write-off transactor — ADR-043 commit 5/5.

   Walks a case → :legal → :written-off and verifies:
     - Status-machine transitions are recorded with audit metadata.
     - A balanced kernel :transaction is posted Dr Bad-Debt-Expense
       / Cr AR.
     - The :collection-case/closed-at is set.
     - :supporting-doc is required (smoke-tests :missing-supporting
       throw)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.audit-doc :as adoc]
            [kontor.collections.case :as kcase]
            [kontor.collections.schema :as coll-schema]
            [kontor.collections.writeoff :as kwo]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.status-machine :as sm]))

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
                 {:gl-account-default/account-type :ar
                  :gl-account-default/account [:kontor.account/path "1200"]}
                 {:gl-account-default/account-type :bad-debt-expense
                  :gl-account-default/account [:kontor.account/path "6900"]}
                 ;; Journal
                 {:kontor.journal/code "SALES" :kontor.journal/name "Sales Journal"
                  :kontor.journal/type :sales}])
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
      (let [doc-eid (d/q '[:find ?d . :where [?d :audit-doc/code "DOC-WO-1"]]
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
                                      :collection-case/state))))
          (testing "case :closed-at set"
            (let [c (kcase/pull-case db "CASE-WO")]
              (is (some? (:collection-case/closed-at c)))))
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
    (let [doc (d/q '[:find ?d . :where [?d :audit-doc/code "DOC-E2E"]]
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
          history (sm/status-history-of db eid :collection-case/state)]
      (testing "Six history rows: open, l1, l2, final, legal, written-off"
        (is (= 6 (count history))))
      (testing "Final state :written-off"
        (is (= :written-off
               (sm/current-status db eid :collection-case/state))))
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
