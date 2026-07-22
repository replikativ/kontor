(ns kontor.reporting.read-side-contract-test
  "Two contracts the balance-side readers have to keep, each of which was
   broken in a way no existing test could see (research note 194 §1).

   1. **They agree with each other.** `explain-balance` is supposed to
      itemise the number `account-balance` returns. It coerced a nil
      `:as-of-valid` to wall-clock now and pushed that into the opts it
      forwarded, so on a book holding a future-dated posting it explained
      a different number than the one it was explaining — measured 600 vs
      670. Note 160 §I-17 had already moved the default to nil for
      `account-balance` and said 'same for anything else with the same
      default'; only `balance.cljc` got it.

   2. **They can be scoped to one parallel book.** ADR-021 makes
      `:kontor.posting/ledger` a first-class axis and `compute-report`
      honoured it, but `account-balance` and everything built on it did
      not — and passing `:ledger` was accepted and ignored rather than
      rejected, so an HGB-plus-IFRS book returned a blend belonging to
      neither framework. `close-period!` read through the same gap, which
      is where a blended figure becomes a permanent posting."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.closing :as closing]
            [kontor.reporting.explain :as explain]
            [kontor.reporting.ledger :as kledger]
            [kontor.reporting.report :as report]
            [kontor.reporting.trial :as trial]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private gj  [:kontor.journal/code "GJ"])
(defn- p [path] [:kontor.account/path path])
(def ^:private primary [:kontor.ledger/code "primary"])
(def ^:private ifrs    [:kontor.ledger/code "ifrs"])

(defn- book-with-accounts []
  (let [conn (core/create-test-db)]
    (gate/transact-with-validation
     conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
            :kontor.commodity/precision 2}
           {:kontor.journal/code "GJ" :kontor.journal/type :general}
           {:kontor.account/path "Assets:Cash" :kontor.account/code "1000"
            :kontor.account/type :asset :kontor.account/active true}
           {:kontor.account/path "Income:Sales" :kontor.account/code "4000"
            :kontor.account/type :income :kontor.account/active true}
           {:kontor.account/path "Equity:Retained" :kontor.account/code "3000"
            :kontor.account/type :equity :kontor.account/active true}])
    conn))

(defn- entry! [conn date amount & [ledger]]
  (book/entry! conn (cond-> {:debit-account (p "Assets:Cash")
                             :credit-account (p "Income:Sales")
                             :amount amount :commodity eur :journal gj
                             :effective-date date}
                      ledger (assoc :ledger ledger))))

(defn- cash-eid [conn]
  (d/q '[:find ?a . :where [?a :kontor.account/path "Assets:Cash"]] @conn))

(defn- amount-of [balance-map] (some-> balance-map vals first :amount))

;; ---------------------------------------------------------------------------
;; 1. the readers agree
;; ---------------------------------------------------------------------------

(deftest explain-balance-explains-the-balance-it-reports
  (let [conn (book-with-accounts)]
    (entry! conn #inst "2026-01-15" 600M)
    ;; valid-from in the future — the case the wall-clock-now default hid
    (entry! conn #inst "2099-01-01" 70M)
    (let [cash (cash-eid conn)]
      (testing "the default window is all valid time, everywhere"
        (is (= 670M (amount-of (balance/account-balance conn cash))))
        (is (= 670M (amount-of (:balance (explain/explain-balance conn cash)))))
        (is (= 2 (count (kledger/postings-against conn cash))))
        (is (= 2 (count (kledger/running-balance conn cash)))))
      (testing "explain agrees with the balance it itemises"
        (is (= (balance/account-balance conn cash)
               (:balance (explain/explain-balance conn cash)))))
      (testing "an explicit as-of-valid still bounds all of them"
        (let [opts {:as-of-valid #inst "2026-06-30"}]
          (is (= 600M (amount-of (balance/account-balance conn cash opts))))
          (is (= 600M (amount-of (:balance (explain/explain-balance conn cash opts)))))
          (is (= 1 (count (kledger/postings-against conn cash opts)))))))))

;; ---------------------------------------------------------------------------
;; 2. the readers can be scoped to one book
;; ---------------------------------------------------------------------------

(defn- parallel-book []
  (let [conn (book-with-accounts)]
    (gate/transact-with-validation
     conn [{:kontor.ledger/code "primary" :kontor.ledger/name "Primary"
            :kontor.ledger/type :primary}
           {:kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS"
            :kontor.ledger/type :secondary}])
    (entry! conn #inst "2026-01-15" 600M primary)
    (entry! conn #inst "2026-01-20" 70M ifrs)
    conn))

(deftest book-can-write-a-ledger-tagged-posting
  ;; `->posting` rebuilt each posting from a fixed key list, so :ledger was
  ;; discarded and ADR-021 parallel books were unreachable from the facade
  ;; CLAUDE.md calls "start here for any new business write".
  (let [conn (parallel-book)]
    (is (= 4 (count (d/q '[:find ?p :where [?p :kontor.posting/ledger _]] @conn)))
        "entry-level :ledger reaches every leg")
    (testing "also via the per-posting :postings form"
      (let [c (book-with-accounts)]
        (gate/transact-with-validation
         c [{:kontor.ledger/code "ifrs" :kontor.ledger/name "IFRS"
             :kontor.ledger/type :secondary}])
        (book/entry! c {:journal gj :commodity eur :effective-date #inst "2026-01-15"
                        :postings [{:account (p "Assets:Cash") :amount 10M :ledger ifrs}
                                   {:account (p "Income:Sales") :amount -10M :ledger ifrs}]})
        (is (= 2 (count (d/q '[:find ?p :where [?p :kontor.posting/ledger _]] @c))))))))

(deftest every-reader-scopes-to-the-requested-ledger
  (let [conn (parallel-book)
        cash (cash-eid conn)]
    (testing "unscoped reads blend the books — 600 HGB + 70 IFRS"
      (is (= 670M (amount-of (balance/account-balance conn cash)))))
    (testing "account-balance"
      (is (= 600M (amount-of (balance/account-balance conn cash {:ledger primary}))))
      (is (= 70M (amount-of (balance/account-balance conn cash {:ledger ifrs})))))
    (testing "trial-balance"
      (is (= 600M (amount-of (get (trial/trial-balance conn {:ledger primary}) cash)))))
    (testing "postings-against"
      (is (= 2 (count (kledger/postings-against conn cash))))
      (is (= 1 (count (kledger/postings-against conn cash {:ledger primary})))))
    (testing "explain-balance"
      (is (= 600M (amount-of (:balance (explain/explain-balance conn cash {:ledger primary}))))))
    (testing "and they agree with the report engine, which already had the option"
      (is (= 600M (:amount (:line/value
                            (first (:report/lines
                                    (report/compute-report
                                     conn {:report/name "r"
                                           :report/lines [{:line/code "c" :line/label "c"
                                                           :line/expression
                                                           {:engine :account-codes :codes ["1000"]
                                                            :sign :inflow :commodity :EUR}}]}
                                     {:ledger primary})))))))))
  (testing "an unknown ledger is an error, not an empty result"
    (is (thrown? clojure.lang.ExceptionInfo
                 (balance/account-balance (parallel-book) (cash-eid (parallel-book))
                                          {:ledger [:kontor.ledger/code "nope"]})))))

(deftest a-nil-ledger-posting-belongs-to-the-primary-book
  ;; ADR-021: postings written before parallel books were set up carry no
  ;; ledger and must still appear in the primary book's figures.
  (let [conn (parallel-book)
        cash (cash-eid conn)]
    (entry! conn #inst "2026-02-01" 5M)          ; no :ledger
    (is (= 605M (amount-of (balance/account-balance conn cash {:ledger primary})))
        "untagged postings count as primary")
    (is (= 70M (amount-of (balance/account-balance conn cash {:ledger ifrs})))
        "and not as anything else")))

;; ---------------------------------------------------------------------------
;; 3. the close, which is where a blended figure becomes permanent
;; ---------------------------------------------------------------------------

(deftest close-period-scopes-and-tags-by-ledger
  (let [conn (parallel-book)
        _ (d/transact conn [{:kontor.period/start #inst "2026-01-01"
                             :kontor.period/end   #inst "2027-01-01"
                             :kontor.period/name  "FY2026"}])
        period (d/q '[:find ?e . :where [?e :kontor.period/name "FY2026"]] @conn)
        retained (d/q '[:find ?a . :where [?a :kontor.account/path "Equity:Retained"]] @conn)
        result (closing/close-period! conn {:period-eid period
                                            :retained-earnings-eid retained
                                            :journal-eid [:kontor.journal/code "GJ"]
                                            :ledger primary})]
    (testing "only the requested book's result is closed"
      ;; primary holds 600 of income; IFRS's 70 must not be swept in
      (is (= 600M (-> result :net-by-commodity vals first :amount abs))))
    (testing "every closing posting carries the ledger it closed"
      (let [tx (:transaction-eid result)
            legs (d/q '[:find [?p ...] :in $ ?t :where [?p :kontor.posting/transaction ?t]]
                      @conn tx)
            ledgers (into #{} (map #(:db/id (:kontor.posting/ledger
                                             (d/pull @conn [:kontor.posting/ledger] %))))
                          legs)]
        (is (pos? (count legs)))
        (is (= 1 (count ledgers)) "one ledger across all legs")
        (is (not (contains? ledgers nil))
            "the retained-earnings leg is tagged too — untagged would mean primary")))
    (testing "the IFRS book is untouched by the HGB close"
      (is (= 70M (amount-of (balance/account-balance conn (cash-eid conn) {:ledger ifrs})))))))
