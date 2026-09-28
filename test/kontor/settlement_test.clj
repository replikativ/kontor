(ns kontor.settlement-test
  "ADR-172 — a book that lived in a speculative world settles into its parent
   by replay: the parent numbers what it takes in, a world's self-cancelling
   speculation stays out, a retry books nothing twice, and what cannot be
   replayed is refused instead of carried over as datoms."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.compliance.period :as period]
            [kontor.core :as core]
            [kontor.numbering :as numbering]
            [kontor.reporting.balance :as balance]
            [kontor.settlement :as st]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private ar  [:kontor.account/path "Assets:Receivable"])
(def ^:private rev [:kontor.account/path "Income:Sales"])

(defn- fresh-book []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.account/path "Assets:Receivable" :kontor.account/type :asset}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income}])
    (numbering/configure-journal! conn [:kontor.journal/code "SALE"]
                                  {:prefix "RE/{year}/" :reset :yearly :padding 4})
    conn))

(defn- world-of
  "A branch of `conn`'s book: the book of a speculative world."
  [conn]
  (let [cfg (:config (d/db conn))
        branch (keyword (str "w-" (random-uuid)))]
    (d/branch! conn (:branch cfg) branch)
    (d/connect (assoc cfg :branch branch))))

(defn- sell! [conn amount date & [opts]]
  (book/sell! conn (merge {:debit-account ar :credit-account rev :amount amount
                           :commodity eur :effective-date date}
                          opts)))

(defn- numbers [db]
  (sort (d/q '[:find [?x ...]
               :where [?t :kontor.transaction/sequence-number _]
               [?t :kontor.transaction/external-id ?x]]
             db)))

(defn- ar-balance [conn]
  (or (:amount (first (vals (balance/account-balance conn ar)))) 0M))

(deftest the-parent-numbers-what-it-takes-in
  (let [conn (fresh-book)
        _ (sell! conn 100 #inst "2026-03-01")
        base (d/db conn)
        w (world-of conn)]
    (sell! w 250 #inst "2026-03-02")
    (testing "a sale the world reversed itself is speculation: it stays out"
      (let [t (get (:tempids (sell! w 9 #inst "2026-03-02")) -1)]
        (book/reverse! w {:transaction t :reversal-date #inst "2026-03-03"})))
    (is (= ["RE/2026/0001" "RE/2026/0002" "RE/2026/0003" "RE/2026/0004"] (numbers (d/db w))))
    ;; the parent books meanwhile
    (sell! conn 40 #inst "2026-03-02")
    (let [intents (st/extract base (d/db w))]
      (is (= 1 (count intents)))
      (is (= {"RE/2026/0002" "RE/2026/0003"} (st/stamp! conn intents))
          "the world's number moves to the parent's next one")
      (is (= ["RE/2026/0001" "RE/2026/0002" "RE/2026/0003"] (numbers (d/db conn))) "gapless")
      (is (= 390M (ar-balance conn)))
      (testing "a retry books nothing twice"
        (st/stamp! conn intents)
        (is (= ["RE/2026/0001" "RE/2026/0002" "RE/2026/0003"] (numbers (d/db conn))))
        (is (= 390M (ar-balance conn)))))))

(deftest a-reversal-of-a-base-entry-replays-and-is-claimed
  (let [conn (fresh-book)
        t (get (:tempids (sell! conn 100 #inst "2026-03-01")) -1)
        base (d/db conn)
        w (world-of conn)]
    (book/reverse! w {:transaction t :reversal-date #inst "2026-03-05"})
    (let [[i] (st/extract base (d/db w))]
      (is (contains? (:footprint i) [:reverses t]))
      (testing "the parent reversing the same entry meanwhile is visible as a claim"
        (let [other (world-of conn)]
          (book/reverse! other {:transaction t :reversal-date #inst "2026-03-04"})
          (is (contains? (st/parent-claims base (d/db other)) [:reverses t]))))
      (st/stamp! conn [i])
      (is (= t (:db/id (:kontor.transaction/reverses
                        (d/entity (d/db conn) [:kontor.transaction/origin-id (:intent/id i)])))))
      (is (= 0M (ar-balance conn))))))

(deftest caller-ids-are-footprints
  (let [conn (fresh-book)
        base (d/db conn)
        w (world-of conn)]
    (sell! w 70 #inst "2026-03-02" {:external-id "PAY-line-7"})
    (sell! conn 70 #inst "2026-03-02" {:external-id "PAY-line-7"})
    (let [[i] (st/extract base (d/db w))]
      (is (contains? (:footprint i) [:external-id "PAY-line-7"]))
      (is (nil? (:world-number i)) "a caller's own id is not a rendered number")
      (is (contains? (st/parent-claims base (d/db conn)) [:external-id "PAY-line-7"])
          "the same bank line matched in the parent meanwhile: a conflict for review"))))

(deftest what-cannot-be-replayed-is-refused
  (testing "an outbox intent (external effects leave only from the root)"
    (let [conn (fresh-book)
          base (d/db conn)
          w (world-of conn)]
      (d/transact w [{:kontor.side-effect-intent/key "mail-1"}])
      (is (= ::st/unsupported
             (try (st/extract base (d/db w)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

(deftest reference-data-a-world-created-is-replayed-by-identity
  (let [conn (fresh-book)
        base (d/db conn)
        w (world-of conn)
        consulting [:kontor.account/path "Income:Consulting"]]
    (d/transact w [{:kontor.partner/external-id "NEWCO" :kontor.partner/name "NewCo GmbH"}
                   {:kontor.account/path "Income:Consulting" :kontor.account/type :income}])
    (book/sell! w {:debit-account ar :credit-account consulting :amount 500 :commodity eur
                   :effective-date #inst "2026-03-05" :partner [:kontor.partner/external-id "NEWCO"]})
    (let [intents (st/extract base (d/db w))]
      (is (= #{:catalog :entry} (set (map :kind intents))))
      (st/stamp! conn intents)
      (let [db (d/db conn)
            t (d/q '[:find ?t . :where [?t :kontor.transaction/partner ?p]
                     [?p :kontor.partner/external-id "NEWCO"]] db)]
        (is (= "NewCo GmbH" (:kontor.partner/name (d/entity db [:kontor.partner/external-id "NEWCO"]))))
        (is (some? t) "the entry refers to the replayed partner")
        (is (= -500M (:amount (first (vals (balance/account-balance conn consulting))))))))))

(deftest the-same-reference-data-on-both-sides
  (testing "identical: it is the same entity"
    (let [conn (fresh-book)
          base (d/db conn)
          w (world-of conn)]
      (d/transact w [{:kontor.partner/external-id "NEWCO" :kontor.partner/name "NewCo GmbH"}])
      (d/transact conn [{:kontor.partner/external-id "NEWCO" :kontor.partner/name "NewCo GmbH"}])
      (st/stamp! conn (st/extract base (d/db w)))
      (is (= 1 (count (d/q '[:find [?p ...] :where [?p :kontor.partner/external-id "NEWCO"]] (d/db conn)))))))
  (testing "different: the settlement is refused and books nothing"
    (let [conn (fresh-book)
          base (d/db conn)
          w (world-of conn)]
      (d/transact w [{:kontor.partner/external-id "NEWCO" :kontor.partner/name "NewCo GmbH"}])
      (book/sell! w {:debit-account ar :credit-account rev :amount 10 :commodity eur
                     :effective-date #inst "2026-03-05" :partner [:kontor.partner/external-id "NEWCO"]})
      (d/transact conn [{:kontor.partner/external-id "NEWCO" :kontor.partner/name "Other NewCo AG"}])
      (let [e (try (st/stamp! conn (st/extract base (d/db w))) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= ::st/conflicting-entity (:type e)))
        (is (= [:kontor.partner/external-id "NEWCO"] (:identity e))))
      (is (= [] (numbers (d/db conn)))))))

(deftest a-refused-intent-refuses-the-settlement
  (let [conn (fresh-book)
        base (d/db conn)
        w (world-of conn)]
    (sell! w 50 #inst "2026-03-10")
    (sell! w 60 #inst "2026-04-10")
    ;; the parent closes March before it takes the world in
    (let [march (-> (d/transact conn [{:db/id -1
                                       :kontor.period/start #inst "2026-03-01"
                                       :kontor.period/end #inst "2026-04-01"}])
                    :tempids (get -1))]
      (period/close! conn march {:pre-checks (constantly [])}))
    (is (thrown? clojure.lang.ExceptionInfo (st/stamp! conn (st/extract base (d/db w))))
        "an entry dated into a closed period is not re-dated: the settlement fails")
    (is (= [] (numbers (d/db conn))) "and nothing of it is booked")))
