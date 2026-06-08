(ns kontor.book-test
  "Stage 1 of research note 99 — the `kontor.book` verb facade
   (ADR-095). Acceptance criterion: a full cash+accrual cycle booked
   through the facade leaves a zero trial balance and matches a
   hand-built `post-transaction!` baseline."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.reporting.trial :as trial]))

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
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "PUR"  :kontor.journal/type :purchase}
                 {:kontor.journal/code "CASH" :kontor.journal/type :cash}
                 {:kontor.journal/code "GEN"  :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"         :kontor.account/type :asset}
                 {:kontor.account/path "Assets:Receivable"   :kontor.account/type :asset}
                 {:kontor.account/path "Liabilities:Payable" :kontor.account/type :liability}
                 {:kontor.account/path "Income:Sales"        :kontor.account/type :income}
                 {:kontor.account/path "Expenses:Supplies"   :kontor.account/type :expense}])
    conn))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private ar   [:kontor.account/path "Assets:Receivable"])
(def ^:private ap   [:kontor.account/path "Liabilities:Payable"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])
(def ^:private exp  [:kontor.account/path "Expenses:Supplies"])

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
                    :journal [:kontor.journal/code "CASH"]})]
      (is (vector? tx-data))
      (is (some #(= :posted (:kontor.transaction/state %)) tx-data)
          "post-transaction-tx-data seals the transaction")))
  (testing "entry-tx-data throws on a missing required field"
    (is (thrown? clojure.lang.ExceptionInfo
                 (book/entry-tx-data {:debit-account cash :credit-account rev
                                      :amount 50 :commodity eur
                                      :effective-date d1})))  ; no :journal
    (is (thrown? clojure.lang.ExceptionInfo
                 (book/entry-tx-data {:debit-account cash :credit-account rev
                                      :amount 50 :commodity eur
                                      :journal [:kontor.journal/code "CASH"]}))))) ; no date

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
                        :journal [:kontor.journal/code "GEN"]})
      (is (= 500M (bal conn ar)))))
  (testing "ambiguous journal type throws a clear error"
    (let [conn (fresh-book)]
      (d/transact conn [{:kontor.journal/code "CASH2" :kontor.journal/type :cash}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
                            (book/pay! conn {:debit-account exp :credit-account cash
                                             :amount 10 :commodity eur
                                             :effective-date d1})))))
  (testing "missing journal type throws a clear error"
    (let [conn (core/create-test-db)]
      (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                         :kontor.commodity/precision 2}
                        {:kontor.account/path "A" :kontor.account/type :asset}
                        {:kontor.account/path "B" :kontor.account/type :income}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :journal of type"
                            (book/sell! conn {:debit-account [:kontor.account/path "A"]
                                              :credit-account [:kontor.account/path "B"]
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
     {:transaction {:kontor.transaction/journal        [:kontor.journal/code "SALE"]
                    :kontor.transaction/effective-date d1}
      :postings    [{:kontor.posting/account ar  :kontor.posting/amount 1000M  :kontor.posting/commodity eur}
                    {:kontor.posting/account rev :kontor.posting/amount -1000M :kontor.posting/commodity eur}]})
    (is (= (bal via-facade ar)  (bal via-baseline ar)))
    (is (= (bal via-facade rev) (bal via-baseline rev)))
    (is (= 1000M (bal via-facade ar)))))

;; ============================================================================
;; :entity stamping — F10 regression (note 159/160 §I-6)
;;
;; Pre-fix, `kontor.book` verbs accepted no `:entity` — so postings carried
;; no `:kontor.posting/entity`, and per-entity trial-balance / BS / GuV filters
;; returned empty for any real consumer. This test asserts that:
;; (1) top-level `:entity` is stamped on every posting,
;; (2) per-posting `:entity` overrides (the intercompany pattern), and
;; (3) `kontor.reporting.trial/trial-balance {:entity …}` actually filters.
;; ============================================================================

(deftest entity-is-stamped-on-every-posting
  (let [conn (fresh-book)
        _    (d/transact conn [{:kontor.entity/name "UG"   :kontor.entity/code "UG"}
                               {:kontor.entity/name "Hans" :kontor.entity/code "HANS"}])
        ug   [:kontor.entity/code "UG"]
        hans [:kontor.entity/code "HANS"]]

    (testing "2-leg entry: top-level :entity stamps both postings"
      (book/sell! conn {:debit-account ar :credit-account rev
                        :amount 500 :commodity eur :effective-date d1
                        :entity ug})
      (let [ents (set (d/q '[:find [?ec ...]
                             :where [_ :kontor.posting/entity ?e]
                             [?e :kontor.entity/code ?ec]]
                           (d/db conn)))]
        (is (contains? ents "UG"))))

    (testing "n-leg entry: per-posting :entity overrides for intercompany
              (each entity's legs must self-balance per ADR-031)"
      ;; Intercompany loan UG → Hans:
      ;;   UG side:   Dr AR ←→ Cr Cash    (UG sum = 0)
      ;;   Hans side: Dr Exp ←→ Cr AP     (Hans sum = 0)
      (book/entry! conn
                   {:journal [:kontor.journal/code "GEN"] :effective-date d1
                    :commodity eur
                    :narration "Intercompany loan UG → Hans (€100)"
                    :postings [{:account ar    :amount 100M  :entity ug}
                               {:account cash  :amount -100M :entity ug}
                               {:account exp   :amount 100M  :entity hans}
                               {:account ap    :amount -100M :entity hans}]})
      (let [pairs (set (d/q '[:find ?acct-path ?ent-code
                              :where [?p :kontor.posting/account ?a]
                              [?a :kontor.account/path ?acct-path]
                              [?p :kontor.posting/entity ?e]
                              [?e :kontor.entity/code ?ent-code]]
                            (d/db conn)))]
        (is (contains? pairs ["Assets:Receivable"   "UG"]))
        (is (contains? pairs ["Liabilities:Payable" "HANS"]))))

    (testing "per-posting :partner stamps :kontor.posting/partner (I-15 regression).
              Multi-shareholder dividend declaration is the motivating case:
              one Dr Gewinnvortrag leg, N Cr Dividenden-Payable legs each
              tagged with a distinct shareholder."
      (let [_ (d/transact conn [{:kontor.partner/name "Sarah" :kontor.partner/external-id "SARAH"}
                                {:kontor.partner/name "Tomas" :kontor.partner/external-id "TOMAS"}])
            sarah [:kontor.partner/external-id "SARAH"]
            tomas [:kontor.partner/external-id "TOMAS"]]
        (book/entry! conn
                     {:journal [:kontor.journal/code "GEN"] :effective-date d1
                      :commodity eur
                      :narration "Dividend declaration to 2 shareholders (60/40)"
                      :postings [{:account rev :amount 1000M}
                                 {:account ap  :amount -600M :partner sarah}
                                 {:account ap  :amount -400M :partner tomas}]})
        (let [pairs (set (d/q '[:find ?path ?amt ?pa-code
                                :where [?p :kontor.posting/account ?a]
                                [?a :kontor.account/path ?path]
                                [?p :kontor.posting/amount ?amt]
                                [?p :kontor.posting/partner ?pa]
                                [?pa :kontor.partner/external-id ?pa-code]]
                              (d/db conn)))]
          (is (contains? pairs ["Liabilities:Payable" -600M "SARAH"])
              "Sarah's leg carries her :kontor.posting/partner")
          (is (contains? pairs ["Liabilities:Payable" -400M "TOMAS"])
              "Tomas's leg carries his :kontor.posting/partner"))))

    (testing "I-2 regression: bare keyword + short string :commodity
              are auto-coerced to [:kontor.commodity/symbol …] lookup-ref"
      ;; All three forms must succeed and produce identical postings.
      (let [d2 #inst "2026-04-01"
            mk (fn [c]
                 (book/entry! conn
                              {:journal [:kontor.journal/code "GEN"] :effective-date d2
                               :commodity c
                               :narration (str "I-2 coerce: " (pr-str c))
                               :postings [{:account rev :amount 10M}
                                          {:account ap  :amount -10M}]}))]
        ;; Lookup-ref (canonical)
        (mk [:kontor.commodity/symbol "EUR"])
        ;; Bare keyword (Phase D / note 159 §F6)
        (mk :EUR)
        ;; Short string
        (mk "EUR")
        ;; All 3 should have produced postings with the same :kontor.posting/commodity eid
        (let [eids (set (d/q '[:find [?c ...]
                               :in $ ?d
                               :where [?t :kontor.transaction/effective-date ?d]
                               [?p :kontor.posting/transaction ?t]
                               [?p :kontor.posting/commodity ?c]]
                             (d/db conn) d2))]
          (is (= 1 (count eids))
              "all 3 :commodity forms resolve to the same EUR eid"))))

    (testing "trial-balance {:entity ug} returns only UG-stamped postings"
      (let [tb (trial/trial-balance conn {:entity ug})
            pull-path (fn [eid] (:kontor.account/path (d/pull (d/db conn) [:kontor.account/path] eid)))
            paths (set (map pull-path (keys tb)))]
        ;; UG should have AR, Cash, Income, but NOT Hans's AP or Exp.
        (is (contains? paths "Assets:Receivable"))
        (is (contains? paths "Income:Sales"))
        (is (not (contains? paths "Liabilities:Payable"))
            "Hans's AP leg must not show under UG's per-entity TB")
        (is (not (contains? paths "Expenses:Supplies"))
            "Hans's Exp leg must not show under UG's per-entity TB")))))
