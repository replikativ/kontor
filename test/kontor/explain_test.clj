(ns kontor.explain-test
  "Tests for `kontor.explain` — the McComb-aligned 'explain this
   number' substrate (ADR-091). Verifies:

   - `explain-balance` returns the balance + contributing postings
     for a real GL account, bitemporal-aware.
   - `explain-posting` walks the posting → transaction → status-history
     → audit-doc chain.
   - `entities-with-concept-iri` (ADR-090) finds all entities bound to
     a given external IRI across the seam set (account, account-tag,
     partner, commodity, tax, document-type)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as adoc]
            [kontor.core :as core]
            [kontor.explain :as explain]
            [kontor.ledger :as ledger]
            [kontor.money :as m]
            [kontor.posting :as posting]
            [kontor.status-machine :as sm]))

(def ^:private some-date  #inst "2026-05-09T00:00:00Z")
(def ^:private later-date #inst "2026-06-15T00:00:00Z")

(defn- seed!
  "Plant the minimum substrate the explain tests need: ledger,
   commodity, two accounts, one journal, one partner, plus a custom
   facet attr for the status-history tests (the kernel ships the
   :status-transition + :status-history substrate but does not own
   any per-transaction facet — consumers carry their own)."
  [conn]
  (ledger/install-defaults! conn)
  ;; Bring our own facet attr — same pattern status-machine-test uses.
  (d/transact conn
              [{:db/ident :tx-test/review-status
                :db/valueType :db.type/keyword
                :db/cardinality :db.cardinality/one}])
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"
                ;; ADR-090: optional concept-iri.
                :kontor.commodity/concept-iri "https://www.omg.org/spec/EDMC-FIBO/iso4217/EUR"}
               {:db/id -2 :account/path "Assets:Receivable"
                :account/name "Trade receivables"
                :account/type :asset :account/active true
                ;; ADR-090: account-level concept-iri seam.
                :account/concept-iri "http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#TradeAndOtherReceivables"}
               {:db/id -3 :account/path "Income:Sales"
                :account/name "Sales revenue"
                :account/type :income :account/active true
                :account/concept-iri "http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#Revenue"}
               {:db/id -4 :journal/code "INV" :journal/name "Customer invoices"
                :journal/type :sale :journal/active true}
               ;; ADR-090: partner with concept-iri (FIBO Organization).
               {:db/id -5 :kontor.partner/external-id "acme"
                :kontor.partner/name "ACME Corp"
                :kontor.partner/kind :customer
                :kontor.partner/concept-iri "https://gleif.org/lei/254900XYZ0000000ACME"}
               ;; status-transition: nil → :reviewed for our test facet
               {:status-transition/entity-type :transaction
                :status-transition/facet :tx-test/review-status
                :status-transition/from :nil
                :status-transition/to :reviewed
                :status-transition/active true
                :status-transition/name "Mark transaction reviewed"}])
  (d/db conn))

(defn- post-invoice!
  "Post one balanced sales invoice. Returns [tx-eid posting-eids]."
  [conn external-id amount narration]
  (let [db  (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        rec (:db/id (d/entity db [:account/path "Assets:Receivable"]))
        rev (:db/id (d/entity db [:account/path "Income:Sales"]))
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        prt (:db/id (d/entity db [:kontor.partner/external-id "acme"]))
        _   (posting/post-transaction!
             conn
             {:transaction
              {:transaction/external-id    external-id
               :transaction/journal        jnl
               :transaction/effective-date some-date
               :transaction/partner        prt
               :transaction/narration      narration}
              :postings
              [{:posting/account rec :posting/amount  amount :posting/commodity eur}
               {:posting/account rev :posting/amount  (- amount) :posting/commodity eur}]}
             {:vt-from some-date})
        db' (d/db conn)
        tx-eid (:db/id (d/entity db' [:transaction/external-id external-id]))
        posting-eids (d/q '[:find [?p ...]
                            :in $ ?tx
                            :where [?p :posting/transaction ?tx]]
                          db' tx-eid)]
    [tx-eid (vec posting-eids)]))

;; ============================================================================
;; explain-balance
;; ============================================================================

(deftest explain-balance-returns-balance-and-postings
  (let [conn (core/create-test-db)
        _    (seed! conn)
        _    (post-invoice! conn "INV-EXP-1" 100.00M "First sale")
        _    (post-invoice! conn "INV-EXP-2"  50.00M "Second sale")
        db   (d/db conn)
        rec  (:db/id (d/entity db [:account/path "Assets:Receivable"]))
        r    (explain/explain-balance conn rec)]
    (testing "result shape"
      (is (= rec (:account r)))
      (is (map? (:balance r)))
      (is (vector? (:postings r)))
      (is (some? (:as-of-valid r)))
      (is (some? (:as-of-tx r))))
    (testing "balance equals sum of contributing postings"
      (let [eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
            bal (get (:balance r) eur)
            posting-monies (mapv :amount (:postings r))
            sum (m/sum posting-monies eur)]
        (is (m/equiv? sum bal))))
    (testing "two postings show up in :postings (one per invoice)"
      (is (= 2 (count (:postings r)))))
    (testing "each posting carries the kernel-required keys"
      (doseq [p (:postings r)]
        (is (some? (:posting p)))
        (is (some? (:transaction p)))
        (is (some? (:amount p)))
        (is (some? (:valid-from p)))
        (is (= :posted (:tx-state p)))))))

(deftest explain-balance-respects-as-of-valid
  (testing "An :as-of-valid before the postings excludes them."
    (let [conn (core/create-test-db)
          _    (seed! conn)
          _    (post-invoice! conn "INV-EXP-PAST" 75.00M "Past sale")
          db   (d/db conn)
          rec  (:db/id (d/entity db [:account/path "Assets:Receivable"]))
          before #inst "2025-01-01T00:00:00Z"
          r      (explain/explain-balance conn rec {:as-of-valid before})]
      (is (empty? (:postings r)) "no postings yet active in 2025")
      (is (empty? (:balance r)) "and no balance"))))

;; ============================================================================
;; explain-posting
;; ============================================================================

(deftest explain-posting-returns-transaction-and-shape
  (let [conn (core/create-test-db)
        _    (seed! conn)
        [tx-eid [p1 _p2]] (post-invoice! conn "INV-EXP-PV" 200.00M "Posting view")
        r    (explain/explain-posting conn p1)]
    (testing "primary shape"
      (is (some? (:posting r)))
      (is (= p1 (-> r :posting :db/id)))
      (is (some? (:transaction r)))
      (is (= tx-eid (-> r :transaction :db/id))))
    (testing "transaction state surfaces"
      (is (= :posted (-> r :transaction :transaction/state))))
    (testing "transaction effective date and partner are visible"
      (is (= some-date (-> r :transaction :transaction/effective-date)))
      (is (some? (-> r :transaction :transaction/partner))))))

(deftest explain-posting-walks-status-history
  (testing "Status changes on the originating tx surface in :status-history."
    (let [conn (core/create-test-db)
          _    (seed! conn)
          [tx-eid [p1 _]] (post-invoice! conn "INV-EXP-SH" 100.00M "With history")]
      ;; Record a status change on the transaction itself — exercises
      ;; the kernel-internal lifecycle facet via the kernel's status
      ;; machine. We use a custom facet to avoid running afoul of the
      ;; transaction-state machine (which is gated by validation).
      (sm/record-status-change!
       conn
       {:entity      tx-eid
        :entity-type :transaction
        :facet       :tx-test/review-status
        :from        :nil
        :to          :reviewed
        :changed-at  later-date
        :reason      :other
        :reason-note "smoke-test review"})
      (let [r (explain/explain-posting conn p1)]
        (is (some? (:status-history r)))
        (is (= 1 (count (:status-history r))))
        (is (= :reviewed (-> r :status-history first :status-history/to)))))))

(deftest explain-posting-pulls-audit-docs-via-status-history
  (testing "An audit-doc attached to a status-history row surfaces as :audit-docs."
    (let [conn (core/create-test-db)
          _    (seed! conn)
          [tx-eid [p1 _]] (post-invoice! conn "INV-EXP-AD" 50.00M "With audit doc")
          ;; Create an audit-doc + a status-history row that references it.
          {db-after :db-after}
          (adoc/create-doc!
           conn
           {:code        "credit-memo-PD-001"
            :type        :credit-memo
            :title       "Credit memo for INV-EXP-AD"
            :storage-uri "s3://kontor-test/credit-memo-001.pdf"})
          doc-eid (:db/id (d/entity db-after [:audit-doc/code "credit-memo-PD-001"]))]
      (sm/record-status-change!
       conn
       {:entity         tx-eid
        :entity-type    :transaction
        :facet          :tx-test/review-status
        :from           :nil
        :to             :reviewed
        :changed-at     later-date
        :supporting-doc doc-eid
        :reason         :other
        :reason-note    "doc-attached smoke"})
      (let [r (explain/explain-posting conn p1)]
        (is (= 1 (count (:audit-docs r))))
        (is (= "credit-memo-PD-001"
               (-> r :audit-docs first :audit-doc/code)))))))

(deftest explain-posting-returns-nil-for-unknown-eid
  (let [conn (core/create-test-db)]
    (is (nil? (explain/explain-posting conn 9999999999)))))

;; ============================================================================
;; entities-with-concept-iri (ADR-090 reverse lookup)
;; ============================================================================

(deftest entities-with-concept-iri-finds-account
  (let [conn (core/create-test-db)
        _    (seed! conn)
        db   (d/db conn)
        rec  (:db/id (d/entity db [:account/path "Assets:Receivable"]))
        r    (explain/entities-with-concept-iri
              db
              "http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#TradeAndOtherReceivables")]
    (is (= [rec] (:account r)))
    (is (empty? (:partner r)))
    (is (empty? (:account-tag r)))
    (is (empty? (:commodity r)))
    (is (empty? (:tax r)))
    (is (empty? (:document-type r)))))

(deftest entities-with-concept-iri-finds-partner
  (let [conn (core/create-test-db)
        _    (seed! conn)
        db   (d/db conn)
        prt  (:db/id (d/entity db [:kontor.partner/external-id "acme"]))
        r    (explain/entities-with-concept-iri
              db
              "https://gleif.org/lei/254900XYZ0000000ACME")]
    (is (= [prt] (:partner r)))))

(deftest entities-with-concept-iri-finds-commodity
  (let [conn (core/create-test-db)
        _    (seed! conn)
        db   (d/db conn)
        eur  (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        r    (explain/entities-with-concept-iri
              db
              "https://www.omg.org/spec/EDMC-FIBO/iso4217/EUR")]
    (is (= [eur] (:commodity r)))))

(deftest entities-with-concept-iri-returns-empty-for-unknown
  (let [conn (core/create-test-db)
        _    (seed! conn)
        r    (explain/entities-with-concept-iri
              (d/db conn)
              "https://example.org/concepts/Nonexistent")]
    (is (every? empty? (vals r)))))
