(ns kontor.regression.substrate-correctness-test
  "Regression suite — KERNEL SUBSTRATE correctness + the reporting /
   error-handling findings F1/F2/F3 from research note 196.

   Correct-behaviour tests (bitemporal restatement, period-lock,
   sealing, commodity-match rejection) assert the kernel guarantee and
   PASS today. The F1/F2/F3 tests assert the *intended* consumer-facing
   shape; they document open bugs and are marked `^:kaocha/pending` so
   the default suite stays green while the assertion pins the fix target
   (each will start passing the moment the corresponding finding lands).

   Everything is booked through `kontor.book` / the validation gate over
   a self-contained EUR/USD chart on `kontor.core/create-test-db`, so the
   suite exercises the same write path a real consumer uses."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.compliance.period :as period]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixture
;; ============================================================================

(def ^:private jan-1  #inst "2026-01-01T00:00:00Z")
(def ^:private jan-15 #inst "2026-01-15T00:00:00Z")
(def ^:private feb-1  #inst "2026-02-01T00:00:00Z")
(def ^:private feb-15 #inst "2026-02-15T00:00:00Z")

(defn- fresh-book
  "Schema-loaded conn with invariants installed and a minimal, realistic
   chart: EUR + USD commodities, one journal per type (so the verbs
   resolve unambiguously), and a small chart including a USD-restricted
   account (`:kontor.account/commodity`) used to exercise commodity-match."
  []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact
     conn
     [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
      {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
      {:kontor.journal/code "SALE" :kontor.journal/type :sale     :kontor.journal/name "Sales"}
      {:kontor.journal/code "PUR"  :kontor.journal/type :purchase :kontor.journal/name "Purchases"}
      {:kontor.journal/code "CASH" :kontor.journal/type :cash     :kontor.journal/name "Cash"}
      {:kontor.journal/code "GEN"  :kontor.journal/type :general  :kontor.journal/name "General"}])
    ;; USD-restricted account references the USD commodity eid.
    (let [usd (:db/id (d/entity (d/db conn) [:kontor.commodity/symbol "USD"]))]
      (d/transact
       conn
       [{:kontor.account/path "Assets:Cash"       :kontor.account/code "1000" :kontor.account/type :asset :kontor.account/active true}
        {:kontor.account/path "Assets:Receivable" :kontor.account/code "1200" :kontor.account/type :asset :kontor.account/active true}
        {:kontor.account/path "Assets:USD-Cash"   :kontor.account/code "1010" :kontor.account/type :asset :kontor.account/active true
         :kontor.account/commodity usd}
        {:kontor.account/path "Income:Sales"      :kontor.account/code "4000" :kontor.account/type :income  :kontor.account/active true}
        {:kontor.account/path "Expenses:Supplies" :kontor.account/code "5000" :kontor.account/type :expense :kontor.account/active true}]))
    conn))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private ar   [:kontor.account/path "Assets:Receivable"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])
(def ^:private usd-cash [:kontor.account/path "Assets:USD-Cash"])

(defn- eur-amt
  "Single-commodity (EUR) balance amount on `account`, as a BigDecimal
   (0M when the account has no postings). Compare with `==`, never `=`."
  [conn account & [opts]]
  (let [m (first (vals (balance/account-balance conn account (or opts {}))))]
    (if m (:amount m) 0M)))

(defn- tx-instant
  "The commit `:db/txInstant` for a tx-report from a `kontor.book` write —
   used as an `:as-of-tx` cut for as-filed vs restated views."
  [conn report]
  (:db/txInstant (d/pull (d/db conn) [:db/txInstant] (kbt/commit-tx-eid report))))

;; ============================================================================
;; 1. Bitemporal restatement — as-filed vs restated views (ADR-008)
;; ============================================================================

(deftest bitemporal-restatement-as-filed-vs-restated
  (testing "A revenue booked at jan-15, then restated (+200) at the SAME
            valid-time but a later transaction-time, reads 1000 as-filed
            (as-of the first commit) and 1200 restated (latest knowledge,
            valid @ jan-15)."
    (let [conn (fresh-book)
          ;; As filed: sale of 1000 EUR effective jan-15.
          r1 (book/sell! conn {:debit-account ar :credit-account rev
                               :amount 1000 :commodity eur
                               :effective-date jan-15 :narration "Original invoice"})
          t1 (tx-instant conn r1)
          ;; Correction booked LATER (now), same valid-time jan-15: +200.
          _  (book/adjust! conn {:effective-date jan-15
                                 :narration "Restatement +200"
                                 :postings [{:account ar  :amount  200 :commodity eur}
                                            {:account rev :amount -200 :commodity eur}]})]
      (testing "as-filed view — as-of the first commit's tx-time"
        (is (== 1000M (eur-amt conn ar {:as-of-tx t1 :as-of-valid feb-1}))
            "the as-filed AR balance excludes the later restatement"))
      (testing "restated view — latest knowledge, valid @ jan-15"
        (is (== 1200M (eur-amt conn ar {:as-of-valid jan-15}))
            "the restated AR balance includes the +200 correction"))
      (testing "both legs of the restatement are valid at/after jan-15"
        (is (== 1200M (eur-amt conn ar {:as-of-valid feb-15})))))))

;; ============================================================================
;; 2. Period lock — a posting into a closed period is rejected (ADR-014)
;; ============================================================================

(deftest closed-period-rejects-posting-inside-it
  (testing "After closing Jan [jan-1, feb-1), a sale effective jan-15 is
            refused by the gate; a sale effective feb-15 still commits."
    (let [conn (fresh-book)
          period-eid (-> (d/transact conn [{:db/id -1
                                            :kontor.period/start jan-1
                                            :kontor.period/end   feb-1}])
                         :tempids (get -1))
          _ (period/close! conn period-eid {:pre-checks (constantly [])})]
      (testing "the period is locked"
        (is (not (period/open? (d/db conn) period-eid))))
      (testing "posting inside the closed period is rejected"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Period violation"
             (book/sell! conn {:debit-account ar :credit-account rev
                               :amount 500 :commodity eur
                               :effective-date jan-15 :narration "Backdated into closed Jan"}))))
      (testing "posting outside the closed period still commits"
        (is (some? (book/sell! conn {:debit-account ar :credit-account rev
                                     :amount 500 :commodity eur
                                     :effective-date feb-15 :narration "Open Feb"}))))
      (testing "the rejected Jan posting left no AR balance at jan-15"
        (is (== 0M (eur-amt conn ar {:as-of-valid jan-15})))))))

;; ============================================================================
;; 3. Sealing — a posted entry cannot be silently retracted / edited (ADR-007)
;; ============================================================================

(deftest sealed-posting-cannot-be-silently-retracted-or-edited
  (testing "A sale posted through kontor.book is sealed; retracting or
            in-place-editing any of its posting datoms is refused, and
            the balance stays intact."
    (let [conn (fresh-book)
          _ (book/sell! conn {:debit-account ar :credit-account rev
                              :amount 800 :commodity eur
                              :effective-date jan-15 :narration "Sealed sale"})
          ar-eid (:db/id (d/entity (d/db conn) ar))
          posting-eid (d/q '[:find ?p .
                             :in $ ?acct
                             :where [?p :kontor.posting/account ?acct]]
                           (d/db conn) ar-eid)]
      (is (some? posting-eid) "the AR posting exists")
      (is (some? (:kontor.posting/posted-at
                  (d/pull (d/db conn) [:kontor.posting/posted-at] posting-eid)))
          "the posting is sealed (:posted-at set)")
      (testing "silent retract of the sealed amount is refused"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Sealing violation"
             (v/transact-with-validation
              conn [[:db/retract posting-eid :kontor.posting/amount 800M]]))))
      (testing "silent in-place edit of the sealed amount is refused"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Sealing violation"
             (v/transact-with-validation
              conn [{:db/id posting-eid :kontor.posting/amount 9999M}]))))
      (testing "the sealed balance is unchanged after the refused writes"
        (is (== 800M (eur-amt conn ar)))))))

;; ============================================================================
;;    Commodity-match — EUR posted to a USD-restricted account is rejected.
;;    (Correct behaviour; the DIAGNOSABILITY of the rejection is F3 below.)
;; ============================================================================

(deftest commodity-mismatch-is-rejected
  (testing "Posting EUR to an account restricted to USD is refused by the
            commodity-match invariant (a correct rejection)."
    (let [conn (fresh-book)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (book/transfer! conn {:debit-account usd-cash :credit-account cash
                                         :amount 100 :commodity eur
                                         :effective-date jan-15
                                         :narration "EUR into a USD account"})))
      (testing "nothing was committed — the USD account balance is empty"
        (is (empty? (balance/account-balance conn usd-cash)))))))

;; ============================================================================
;; F1 — trial-balance SHOULD be human-readable (account codes/paths + symbols)
;; ============================================================================

;; PENDING(F1): `trial-balance` returns {account-eid {commodity-eid Money}}
;; with RAW eids for both the account and the commodity — unusable to
;; eyeball or render without hand-joining eids (note 196 F1, P1). It SHOULD
;; key by the account's code/path and use the commodity SYMBOL.
(deftest trial-balance-should-be-human-readable-F1
  ;; F1 fix (note 196): trial-balance itself stays eid-keyed because write-back
  ;; consumers (closing, consolidation) re-transact its commodity as a ref;
  ;; trial-balance-readable is the presentation view that resolves eids →
  ;; account path + commodity symbol.
  (let [conn (fresh-book)
        _ (book/sell! conn {:debit-account ar :credit-account rev
                            :amount 4000 :commodity eur
                            :effective-date jan-15 :narration "Sale"})
        tb (trial/trial-balance-readable conn)]
    (testing "the outer map should be addressable by account code or path"
      (is (some (fn [k] (contains? #{"Income:Sales" "4000"} k)) (keys tb))
          "trial-balance-readable should key by account code/path, not a raw eid"))
    (testing "the inner map should be keyed by the commodity SYMBOL"
      ;; kontor's commodity symbol is the keyword :EUR (money/money, the cljs
      ;; reads, and every posting all use the keyword form) — not a string.
      (is (some (fn [inner] (contains? (set (keys inner)) :EUR)) (vals tb))
          "commodity should be the symbol :EUR, not a raw eid")
      (is (every? (fn [inner] (every? keyword? (keys inner))) (vals tb))
          "every inner commodity key is a symbol keyword, never an eid"))))

;; ============================================================================
;; F2 — unbalanced book/entry! should raise the typed :validation/sum-to-zero
;; ============================================================================

;; PENDING(F2): an unbalanced entry via `book/entry!` fails inside
;; `posting.build/build-transaction` with ex-data `{:report … :input …}`
;; and NO `:type` — while the same imbalance through the raw gate raises
;; `:type :validation/sum-to-zero`. A consumer dispatching on
;; `(:type (ex-data e))` gets nil from the facade path (note 196 F2, P2).
;; The vocabulary should be uniform across both write paths.
(deftest unbalanced-entry-raises-typed-sum-to-zero-F2
  (let [conn (fresh-book)
        ex (try
             (book/adjust! conn {:effective-date jan-15
                                 :narration "Deliberately unbalanced"
                                 :postings [{:account ar  :amount  100 :commodity eur}
                                            {:account rev :amount  -50 :commodity eur}]})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "an unbalanced entry must throw")
    (is (= :validation/sum-to-zero (:type (ex-data ex)))
        "the facade path should carry the same :type as the raw gate path")))

;; ============================================================================
;; F3 — invariant violations should name the rule + offending value
;; ============================================================================

;; PENDING(F3): a rejected commodity-match write surfaces
;; `:type :invariant/invariant-mismatch` with only `:attribute` set — it
;; does NOT name the human rule (commodity-match) nor the offending values
;; (the account's expected commodity vs the posting's actual). A consumer
;; can't tell commodity-match from account-active from the error (note 196
;; F3, P2). ex-data should carry a `:violations`/`:rule` + offending value.
(deftest invariant-violation-names-rule-and-value-F3
  (let [conn (fresh-book)
        ex (try
             (book/transfer! conn {:debit-account usd-cash :credit-account cash
                                   :amount 100 :commodity eur
                                   :effective-date jan-15
                                   :narration "EUR into a USD account"})
             nil
             (catch clojure.lang.ExceptionInfo e e))
        d (ex-data ex)]
    (is (some? ex) "the mismatched-commodity write must throw")
    (is (= :invariant/invariant-mismatch (:type d))
        "commodity-match surfaces as an invariant mismatch")
    (testing "the error should name WHICH invariant fired and the offending value"
      ;; Today ex-data has only :attribute/:invariant/:tx-data — none of
      ;; these names the rule in human terms or the offending commodity.
      (is (or (contains? d :rule)
              (contains? d :violations)
              (contains? d :offending-value)
              (contains? d :value))
          "ex-data should name the rule + the offending posting/value"))))
