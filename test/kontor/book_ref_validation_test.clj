(ns kontor.book-ref-validation-test
  "ADR-124 — a bare string in a `:db.type/ref` position is never a tempid.

   datahike reads a string in a ref slot as a TEMPID. Passing an account
   PATH there therefore used to mint an empty phantom entity and post the
   money into it: the entry still summed to zero, sealing and the period
   lock still passed, the transaction still reported `:posted`, and the
   consumer's balance query on their real account read 0.

   Every test here asserts a BALANCE, not a row count — the defect was
   invisible to row counting by construction."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.banking.reconciliation :as recon]
            [kontor.book :as book]
            [kontor.book.build :as build]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]))

(def ^:private d1 #inst "2026-03-01")

(defn- fresh-book
  "A schema-loaded conn with a minimal chart. `:kontor.account/code` values
   are set so the AR tie-out can find the control account."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "CASH" :kontor.journal/type :cash}
                 {:kontor.journal/code "GEN"  :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                 {:kontor.account/path "Assets:AR"    :kontor.account/type :asset
                  :kontor.account/code "1400"}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income}])
    conn))

(defn- bal
  [conn path]
  (let [m (balance/account-balance conn [:kontor.account/path path])]
    (if-let [v (first (vals m))] (:amount v) 0M)))

(defn- eid [conn path]
  (d/q '[:find ?a . :in $ ?p :where [?a :kontor.account/path ?p]] (d/db conn) path))

;; ============================================================================
;; The defect: a bare string reaches the real account, or nothing is written
;; ============================================================================

(deftest bare-string-account-lands-on-the-real-account
  (testing "a bare string means :kontor.account/path and the MONEY ARRIVES"
    (let [conn (fresh-book)]
      (book/sell! conn {:amount 1190M :commodity "EUR"
                        :debit-account  "Assets:AR"
                        :credit-account "Income:Sales"
                        :effective-date d1})
      ;; This is the assertion the original defect defeated: before ADR-124
      ;; the postings referenced two attribute-less phantom entities and BOTH
      ;; of these read 0M while the transaction reported :posted.
      (is (= 1190M  (bal conn "Assets:AR")))
      (is (= -1190M (bal conn "Income:Sales")))
      (is (true? (trial/balanced? (trial/trial-balance conn))))))

  (testing "no phantom entity was created — every posting's account has attributes"
    (let [conn (fresh-book)]
      (book/sell! conn {:amount 100M :commodity "EUR"
                        :debit-account "Assets:AR" :credit-account "Income:Sales"
                        :effective-date d1})
      (let [db       (d/db conn)
            accounts (d/q '[:find [?a ...] :where [?p :kontor.posting/account ?a]] db)]
        (is (= 2 (count accounts)))
        (doseq [a accounts]
          (is (some? (:kontor.account/path (d/pull db [:kontor.account/path] a)))
              "a posting's account must be a real account, not a minted tempid")))))

  (testing "the lookup-ref form still works and is equivalent"
    (let [via-string (fresh-book)
          via-lookup (fresh-book)]
      (book/sell! via-string {:amount 500M :commodity "EUR"
                              :debit-account "Assets:AR" :credit-account "Income:Sales"
                              :effective-date d1})
      (book/sell! via-lookup {:amount 500M :commodity [:kontor.commodity/symbol "EUR"]
                              :debit-account  [:kontor.account/path "Assets:AR"]
                              :credit-account [:kontor.account/path "Income:Sales"]
                              :effective-date d1})
      (is (= (bal via-string "Assets:AR") (bal via-lookup "Assets:AR")))
      (is (= 500M (bal via-string "Assets:AR")))))

  (testing "an eid still works"
    (let [conn (fresh-book)]
      (book/sell! conn {:amount 7M :commodity "EUR"
                        :debit-account  (eid conn "Assets:AR")
                        :credit-account (eid conn "Income:Sales")
                        :effective-date d1})
      (is (= 7M (bal conn "Assets:AR"))))))

;; ============================================================================
;; Refs are resolved STRICTLY — a name that does not exist throws
;; ============================================================================

(deftest unresolvable-refs-throw-with-the-slot-named
  (let [conn (fresh-book)]
    (testing ":debit-account that does not exist"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":debit-account"
           (book/sell! conn {:amount 1M :commodity "EUR"
                             :debit-account "Assets:Nope"
                             :credit-account "Income:Sales"
                             :effective-date d1}))))
    (testing "the ex-data names the slot and the identity attribute"
      (let [e (try (book/sell! conn {:amount 1M :commodity "EUR"
                                     :debit-account "Assets:Nope"
                                     :credit-account "Income:Sales"
                                     :effective-date d1})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :kontor.book/unresolved-ref (:type (ex-data e))))
        (is (= :debit-account (:slot (ex-data e))))
        (is (= :kontor.account/path (:identity-attribute (ex-data e))))))
    (testing ":credit-account that does not exist"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":credit-account"
           (book/sell! conn {:amount 1M :commodity "EUR"
                             :debit-account "Assets:AR"
                             :credit-account "Income:Nope"
                             :effective-date d1}))))
    (testing ":commodity that does not exist"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":commodity"
           (book/sell! conn {:amount 1M :commodity "XXX"
                             :debit-account "Assets:AR"
                             :credit-account "Income:Sales"
                             :effective-date d1}))))
    (testing ":journal that does not exist"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":journal"
           (book/entry! conn {:amount 1M :commodity "EUR" :journal "NOPE"
                              :debit-account "Assets:AR"
                              :credit-account "Income:Sales"
                              :effective-date d1}))))
    (testing "a :postings leg's :account that does not exist"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":account"
           (book/adjust! conn {:commodity "EUR" :effective-date d1
                               :postings [{:account "Assets:Nope" :amount 1M}
                                          {:account "Income:Sales" :amount -1M}]}))))
    (testing "an eid nothing has been written under does not resolve"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"does not resolve"
           (book/sell! conn {:amount 1M :commodity "EUR"
                             :debit-account 99999999
                             :credit-account "Income:Sales"
                             :effective-date d1}))))
    (testing "nothing was written by any of the above"
      (is (= 0M (bal conn "Assets:AR")))
      (is (= 0M (bal conn "Income:Sales"))))))

(deftest validate-entry-reports-an-unresolved-ref-as-a-diagnostic
  (let [conn (fresh-book)
        r    (book/validate-entry conn {:amount 1M :commodity "EUR" :journal-type :sale
                                        :debit-account "Assets:Nope"
                                        :credit-account "Income:Sales"
                                        :effective-date d1})]
    (is (false? (:ok? r)))
    (is (= [:kontor.book/unresolved-ref] (mapv :code (:diagnostics r))))))

;; ============================================================================
;; The gate-level check — the same class for all 211 *-tx-data builders
;; ============================================================================

(deftest gate-refuses-an-undeclared-string-in-a-ref-position
  (let [conn (fresh-book)]
    (testing "a raw entity map with a string account is refused"
      (let [e (try (gate/transact-with-validation
                    conn [{:kontor.posting/account "Assets:AR"
                           :kontor.posting/amount  1M}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :kontor.gate/dangling-string-ref (:type (ex-data e))))
        (is (= [{:attribute :kontor.posting/account :value "Assets:AR"}]
               (:refs (ex-data e))))
        (is (re-find #"lookup-ref \[:kontor.account/path" (ex-message e))
            "the error shows the form the caller should have written")))

    (testing "a list-form :db/add with a string in the value slot is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"dangling|bare string"
           (gate/transact-with-validation
            conn [[:db/add -1 :kontor.posting/account "Assets:AR"]]))))

    (testing "a string in a NON-ref position is fine"
      (is (empty? (gate/dangling-string-refs
                   (d/db conn)
                   [{:kontor.transaction/narration "Assets:AR"}]))))

    (testing "a lookup-ref is not mistaken for a cardinality-many collection"
      (is (empty? (gate/dangling-string-refs
                   (d/db conn)
                   [{:kontor.posting/account [:kontor.account/path "Assets:AR"]
                     :kontor.posting/commodity [:kontor.commodity/symbol "EUR"]}]))))

    (testing "a string DECLARED as a :db/id in the same tx-data is a legitimate tempid"
      (is (empty? (gate/dangling-string-refs
                   (d/db conn)
                   [{:db/id "p0" :kontor.posting/amount 1M}
                    {:kontor.posting/dimensions "p0"}])))
      ;; the shape the kernel builders actually emit
      (is (empty? (gate/dangling-string-refs
                   (d/db conn)
                   [{:db/id "tx" :kontor.transaction/narration "x"}
                    {:db/id "tx-p0" :kontor.posting/transaction "tx"}]))))

    (testing "a string declared in the ENTITY slot of a list form is a tempid"
      (is (empty? (gate/dangling-string-refs
                   (d/db conn)
                   [[:db/add "e0" :kontor.posting/amount 1M]
                    [:db/add -1 :kontor.posting/transaction "e0"]]))))))

(deftest gate-check-is-scoped-away-from-actor-uid-attributes
  ;; The `…-uid` family is `:db.type/ref` but points at a USER, which kontor
  ;; does not model — so an opaque actor string is the existing convention
  ;; (296 call sites in this suite). Those strings DO mint phantom entities,
  ;; but deciding what to do about that is separate from the money-loss fix
  ;; this check exists for; see kontor.gate/actor-uid-attr? and note 199 W10.
  (let [conn (fresh-book)
        db   (d/db conn)]
    (is (true? (gate/actor-uid-attr? :kontor.status-history/changed-by-uid)))
    (is (true? (gate/actor-uid-attr? :kontor.audit/create-uid)))
    (is (false? (gate/actor-uid-attr? :kontor.posting/account)))
    (testing "an opaque actor string is not refused"
      (is (empty? (gate/dangling-string-refs
                   db [{:kontor.status-history/changed-by-uid "sarah"}
                       {:kontor.payment-application/applied-by-uid "actor-1"}]))))
    (testing "an accounting ref in the same tx-data still is"
      (is (= [[:kontor.posting/account "Assets:AR"]]
             (gate/dangling-string-refs
              db [{:kontor.status-history/changed-by-uid "sarah"}
                  {:kontor.posting/account "Assets:AR"}]))))))

(deftest gate-check-does-not-disturb-a-normal-verb-write
  ;; The regression guard for the check itself: it walks tx-data looking for
  ;; strings, and a lookup-ref's second slot IS a string. Getting that wrong
  ;; rejects every well-formed write in the repo.
  (let [conn (fresh-book)]
    (book/sell! conn {:amount 1000M :commodity "EUR"
                      :debit-account "Assets:AR" :credit-account "Income:Sales"
                      :effective-date d1 :external-id "INV-1"
                      :narration "a narration containing a colon: Assets:AR"})
    (is (= 1000M (bal conn "Assets:AR")))
    (is (true? (trial/balanced? (trial/trial-balance conn))))))

;; ============================================================================
;; run-process concatenates step fragments into ONE tx-data
;; ============================================================================
;;
;; ADR-124's gate check accepts a string tempid only when the SAME tx-data
;; declares it. About ten builders (kontor.inventory.ops most visibly) declare
;; a tempid in one process step and reference it from another, which is only
;; safe because run-process concatenates every fragment before its single
;; commit. Pin that, because changing it breaks them all at once.

(deftest run-process-concatenates-fragments-before-committing
  (let [conn      (fresh-book)
        committed (atom [])
        result    ((requiring-resolve 'kontor.workflow.process/run-process)
                   conn
                   {:steps  [(fn [_db _ctx]
                               [{:db/id "shared" :kontor.posting/amount 1M}])
                             (fn [_db _ctx]
                               [{:kontor.posting/transaction "shared"
                                 :kontor.posting/amount -1M}])]
                    :commit (fn [_conn tx-data]
                              (swap! committed conj tx-data)
                              {:tempids {}})})]
    (is (some? result))
    (is (= 1 (count @committed))
        "run-process must commit ONCE; per-step commits would break cross-step tempids")
    (let [tx-data (first @committed)]
      (is (some #(= "shared" (:db/id %)) tx-data))
      (is (some #(= "shared" (:kontor.posting/transaction %)) tx-data))
      (is (empty? (gate/dangling-string-refs (d/db conn) tx-data))
          "declaration and use land in one tx-data, so the gate check accepts them"))))

;; ============================================================================
;; The same silent-drop class: unknown option keys
;; ============================================================================

(deftest unknown-option-keys-are-refused
  (let [conn (fresh-book)]
    (testing "a mistyped entry option throws instead of being dropped"
      (let [e (try (book/sell! conn {:amount 1M :commodity "EUR"
                                     :debit-account "Assets:AR"
                                     :credit-account "Income:Sales"
                                     :effective-date d1
                                     :naration "typo"})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :kontor.book/unknown-option (:type (ex-data e))))
        (is (= [:naration] (:unknown (ex-data e))))))
    (testing "a mistyped :postings key throws too"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unknown posting key"
           (book/adjust! conn {:commodity "EUR" :effective-date d1
                               :postings [{:account "Assets:AR" :amount 1M :ledgr 5}
                                          {:account "Income:Sales" :amount -1M}]}))))
    (testing ":settles as a bare amount is an explicit error, not a seq failure"
      ;; kontor.banking.payment-application uses :settles for a BigDecimal
      ;; AMOUNT — a name collision its own comment blames for the
      ;; missing-link bug going unnoticed (note 198 HIGH-4).
      (let [e (try (book/sell! conn {:amount 1M :commodity "EUR"
                                     :debit-account "Assets:AR"
                                     :credit-account "Income:Sales"
                                     :effective-date d1
                                     :settles 1190M})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :kontor.book/malformed-settles (:type (ex-data e))))))
    (testing "every documented option is accepted"
      (is (some? (build/build-input
                  {:debit-account "Assets:AR" :credit-account "Income:Sales"
                   :amount 1M :commodity "EUR" :journal "SALE" :effective-date d1
                   :narration "n" :external-id "x" :settles [1]}))))
    (is (= 0M (bal conn "Assets:AR")) "no partial write escaped")))

;; ============================================================================
;; :settles — the option whose silent drop broke the AR tie-out
;; ============================================================================

(deftest settles-links-the-payment-to-the-invoice-and-the-ar-tie-out-agrees
  (let [conn (fresh-book)
        eur  (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db conn))]
    (book/sell! conn {:amount 1190M :commodity "EUR"
                      :debit-account "Assets:AR" :credit-account "Income:Sales"
                      :effective-date d1 :external-id "INV-1"})
    (let [inv (d/q '[:find ?t . :where [?t :kontor.transaction/external-id "INV-1"]]
                   (d/db conn))]
      (testing "without the link the subledger and the GL disagree by the whole invoice"
        (let [t (recon/ar-tie-out conn {:commodity eur :ar-codes #{"1400"}})]
          (is (= 1190M (:subledger t)))
          (is (= 1190M (:gl t)))
          (is (true? (:ok? t)) "before payment both sides show the open invoice")))

      (book/receive-payment! conn {:amount 1190M :commodity "EUR"
                                   :debit-account "Assets:Cash"
                                   :credit-account "Assets:AR"
                                   :effective-date #inst "2026-03-20"
                                   :external-id "PAY-1"
                                   :settles [inv]})

      (testing ":settles reaches :kontor.transaction/settles"
        (is (= #{inv}
               (set (d/q '[:find [?s ...] :where [?t :kontor.transaction/settles ?s]]
                         (d/db conn))))))

      (testing "the GL cleared AND the open-item subledger agrees"
        (is (= 0M    (bal conn "Assets:AR")))
        (is (= 1190M (bal conn "Assets:Cash")))
        (let [t (recon/ar-tie-out conn {:commodity eur :ar-codes #{"1400"}})]
          (is (= 0M (:subledger t)))
          (is (= 0M (:gl t)))
          (is (= 0M (:difference t)))
          (is (true? (:ok? t))
              (str "AR tie-out must hold on a settled book: " t)))))))

(deftest without-settles-the-ar-tie-out-surfaces-the-drift
  ;; The counter-test: the tie-out is only meaningful if it FAILS when the
  ;; link is missing. This is what the silently-dropped :settles produced.
  (let [conn (fresh-book)
        eur  (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "EUR"]] (d/db conn))]
    (book/sell! conn {:amount 1190M :commodity "EUR"
                      :debit-account "Assets:AR" :credit-account "Income:Sales"
                      :effective-date d1 :external-id "INV-1"})
    (book/receive-payment! conn {:amount 1190M :commodity "EUR"
                                 :debit-account "Assets:Cash"
                                 :credit-account "Assets:AR"
                                 :effective-date #inst "2026-03-20"})
    (is (= 0M (bal conn "Assets:AR")) "the GL is settled")
    (let [t (recon/ar-tie-out conn {:commodity eur :ar-codes #{"1400"}})]
      (is (false? (:ok? t)))
      (is (= 1190M (:difference t))
          "the unlinked relief is surfaced as drift, not hidden"))))

;; ============================================================================
;; A settlement verb accepts a :bank journal when there is no :cash one
;; ============================================================================

(deftest settlement-verbs-fall-back-from-cash-to-bank
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "BANK" :kontor.journal/type :bank}
                 {:kontor.account/path "Assets:Bank" :kontor.account/type :asset}
                 {:kontor.account/path "Assets:AR"   :kontor.account/type :asset}])
    (testing "receive-payment! works against a book with only a :bank journal"
      (book/receive-payment! conn {:amount 100M :commodity "EUR"
                                   :debit-account "Assets:Bank"
                                   :credit-account "Assets:AR"
                                   :effective-date d1})
      (is (= 100M  (bal conn "Assets:Bank")))
      (is (= -100M (bal conn "Assets:AR")))
      (is (= "BANK"
             (d/q '[:find ?c . :where
                    [?t :kontor.transaction/journal ?j]
                    [?j :kontor.journal/code ?c]]
                  (d/db conn)))
          "the entry landed in the bank journal"))))

(deftest a-cash-journal-still-wins-over-the-bank-fallback
  ;; The fallback must never reroute an existing consumer's entries.
  (let [conn (fresh-book)]
    (d/transact conn [{:kontor.journal/code "BANK" :kontor.journal/type :bank}])
    (book/receive-payment! conn {:amount 50M :commodity "EUR"
                                 :debit-account "Assets:Cash"
                                 :credit-account "Assets:AR"
                                 :effective-date d1})
    (is (= "CASH"
           (d/q '[:find ?c . :where
                  [?t :kontor.transaction/journal ?j]
                  [?j :kontor.journal/code ?c]]
                (d/db conn))))
    (is (= 50M (bal conn "Assets:Cash")))))

(deftest no-settlement-journal-at-all-still-throws-a-clear-error
  (let [conn (core/create-test-db)]
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                      {:kontor.account/path "Assets:Bank" :kontor.account/type :asset}
                      {:kontor.account/path "Assets:AR"   :kontor.account/type :asset}])
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no :journal of type :cash \(nor \[:bank\]\)"
         (book/receive-payment! conn {:amount 1M :commodity "EUR"
                                      :debit-account "Assets:Bank"
                                      :credit-account "Assets:AR"
                                      :effective-date d1})))))
