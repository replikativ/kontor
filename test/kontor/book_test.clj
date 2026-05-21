(ns kontor.book-test
  "Stage 1 of research note 99 — the `kontor.book` verb facade
   (ADR-095). Acceptance criterion: a full cash+accrual cycle booked
   through the facade leaves a zero trial balance and matches a
   hand-built `post-transaction!` baseline."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.trial :as trial]))

;; ============================================================================
;; Fixture helpers
;; ============================================================================

(def ^:private d1 #inst "2026-03-15")

(defn- fresh-book
  "A schema-loaded conn with a minimal chart, three typed journals,
   and a EUR commodity."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:journal/code "PUR"  :journal/type :purchase}
                 {:journal/code "CASH" :journal/type :cash}
                 {:journal/code "GEN"  :journal/type :general}
                 {:account/path "Assets:Cash"         :account/type :asset}
                 {:account/path "Assets:Receivable"   :account/type :asset}
                 {:account/path "Liabilities:Payable" :account/type :liability}
                 {:account/path "Income:Sales"        :account/type :income}
                 {:account/path "Expenses:Supplies"   :account/type :expense}])
    conn))

(def ^:private eur  [:commodity/symbol "EUR"])
(def ^:private cash [:account/path "Assets:Cash"])
(def ^:private ar   [:account/path "Assets:Receivable"])
(def ^:private ap   [:account/path "Liabilities:Payable"])
(def ^:private rev  [:account/path "Income:Sales"])
(def ^:private exp  [:account/path "Expenses:Supplies"])

(defn- bal
  "Single-commodity balance amount on `account` (EUR)."
  [conn account]
  (let [m (first (vals (balance/account-balance conn account)))]
    (if m (:amount m) 0M)))

;; ============================================================================
;; The acceptance test — a full cash+accrual cycle
;; ============================================================================

(deftest full-cycle-leaves-a-zero-trial-balance
  (let [conn (fresh-book)]
    ;; buy supplies on account → sell on account → collect → pay the bill
    (book/buy!             conn {:debit-account exp :credit-account ap
                                 :amount 300 :commodity eur :effective-date d1})
    (book/sell!            conn {:debit-account ar :credit-account rev
                                 :amount 1000 :commodity eur :effective-date d1})
    (book/receive-payment! conn {:debit-account cash :credit-account ar
                                 :amount 1000 :commodity eur :effective-date d1})
    (book/pay-bill!        conn {:debit-account ap :credit-account cash
                                 :amount 300 :commodity eur :effective-date d1})
    (testing "per-account balances are correct"
      (is (= 700M  (bal conn cash)) "received 1000, paid 300")
      (is (= 0M    (bal conn ar))   "sold 1000, collected 1000")
      (is (= 0M    (bal conn ap))   "bought 300, paid 300")
      (is (= -1000M (bal conn rev)) "revenue is credit-natural (signed negative)")
      (is (= 300M  (bal conn exp))  "expense is debit-natural"))
    (testing "the trial balance sums to zero per commodity (Ker σ)"
      (is (true? (trial/balanced? (trial/trial-balance conn)))))))

;; ============================================================================
;; entry-tx-data is the pure ADR-068 builder
;; ============================================================================

(deftest entry-tx-data-is-pure
  (testing "entry-tx-data needs no conn and yields a transactable vector"
    (let [tx-data (book/entry-tx-data
                   {:debit-account cash :credit-account rev
                    :amount 50 :commodity eur :effective-date d1
                    :journal [:journal/code "CASH"]})]
      (is (vector? tx-data))
      (is (some #(= :posted (:transaction/state %)) tx-data)
          "post-transaction-tx-data seals the transaction")))
  (testing "entry-tx-data throws on a missing required field"
    (is (thrown? clojure.lang.ExceptionInfo
                 (book/entry-tx-data {:debit-account cash :credit-account rev
                                      :amount 50 :commodity eur
                                      :effective-date d1})))  ; no :journal
    (is (thrown? clojure.lang.ExceptionInfo
                 (book/entry-tx-data {:debit-account cash :credit-account rev
                                      :amount 50 :commodity eur
                                      :journal [:journal/code "CASH"]}))))) ; no date

;; ============================================================================
;; adjust! — the multi-leg / judgment-entry verb
;; ============================================================================

(deftest adjust-handles-multi-leg-entries
  (let [conn (fresh-book)]
    ;; a 3-leg reclassification: move 120 cash into AR and the bank fee to expense
    (book/adjust! conn {:effective-date d1 :commodity eur
                        :postings [{:account ar   :amount 100}
                                   {:account exp  :amount 20}
                                   {:account cash :amount -120}]})
    (is (= 100M  (bal conn ar)))
    (is (= 20M   (bal conn exp)))
    (is (= -120M (bal conn cash)))
    (is (true? (trial/balanced? (trial/trial-balance conn))))))

;; ============================================================================
;; Journal resolution by type
;; ============================================================================

(deftest journal-resolved-by-type
  (testing "a verb resolves its journal from the sole journal of that type"
    (let [conn (fresh-book)]
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 500 :commodity eur :effective-date d1})
      (is (= 500M (bal conn ar)))))
  (testing "an explicit :journal overrides type resolution"
    (let [conn (fresh-book)]
      (book/sell! conn {:debit-account ar :credit-account rev :amount 500
                        :commodity eur :effective-date d1
                        :journal [:journal/code "GEN"]})
      (is (= 500M (bal conn ar)))))
  (testing "ambiguous journal type throws a clear error"
    (let [conn (fresh-book)]
      (d/transact conn [{:journal/code "CASH2" :journal/type :cash}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
                            (book/pay! conn {:debit-account exp :credit-account cash
                                             :amount 10 :commodity eur
                                             :effective-date d1})))))
  (testing "missing journal type throws a clear error"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro"
                         :commodity/precision 2}
                        {:account/path "A" :account/type :asset}
                        {:account/path "B" :account/type :income}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :journal of type"
                            (book/sell! conn {:debit-account [:account/path "A"]
                                              :credit-account [:account/path "B"]
                                              :amount 10 :commodity eur
                                              :effective-date d1}))))))

;; ============================================================================
;; The facade matches a hand-built post-transaction! baseline
;; ============================================================================

(deftest facade-matches-hand-built-baseline
  (let [via-facade   (fresh-book)
        via-baseline (fresh-book)]
    (book/sell! via-facade {:debit-account ar :credit-account rev
                            :amount 1000 :commodity eur :effective-date d1})
    (posting/post-transaction!
     via-baseline
     {:transaction {:transaction/journal        [:journal/code "SALE"]
                    :transaction/effective-date d1}
      :postings    [{:posting/account ar  :posting/amount 1000M  :posting/commodity eur}
                    {:posting/account rev :posting/amount -1000M :posting/commodity eur}]})
    (is (= (bal via-facade ar)  (bal via-baseline ar)))
    (is (= (bal via-facade rev) (bal via-baseline rev)))
    (is (= 1000M (bal via-facade ar)))))
