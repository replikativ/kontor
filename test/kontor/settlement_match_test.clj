(ns kontor.settlement-match-test
  "ADR-172 — bank matches a world made are replayed in its parent as match
   decisions, re-derived against the parent's state; a bank line is never
   matched twice."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.banking.reconciliation :as recon]
            [kontor.document.invoice :as invoice]
            [kontor.invoice-number-test :as fixture]
            [kontor.settlement :as st]))

(defn- world-of [conn]
  (let [cfg (:config (d/db conn))
        b (keyword (str "w-" (random-uuid)))]
    (d/branch! conn (:branch cfg) b)
    (d/connect (assoc cfg :branch b))))

(defn- book []
  (let [conn (#'fixture/bootstrap true)]
    (d/transact conn [{:kontor.journal/code "BANK" :kontor.journal/name "Bank"
                       :kontor.journal/type :bank :kontor.journal/active true}])
    conn))

(defn- ids [db]
  {:bank (d/q '[:find ?a . :where [?a :kontor.account/code "1200"]] db)
   :eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
   :journal (:db/id (d/entity db [:kontor.journal/code "BANK"]))
   :actor (:db/id (d/entity db [:kontor.partner/external-id "OWN"]))})

(defn- import! [conn amount date memo]
  (let [{:keys [bank eur]} (ids (d/db conn))]
    (recon/ingest-statement! conn [{:bank :test :date date :amount amount
                                    :counterparty "ACME" :description memo
                                    :raw-row [(str date) (str amount) memo]}]
                             {:source-account-eid bank :commodity-eid eur})
    (d/q '[:find ?l . :in $ ?m :where [?l :kontor.bank-line/description ?m]] (d/db conn) memo)))

(defn- match! [conn line invoice-tx]
  (let [{:keys [journal actor]} (ids (d/db conn))]
    (recon/commit-match! conn line {:kind :settle :transactions [invoice-tx]} journal
                         {:applied-by-uid actor})))

(defn- paid? [db ext]
  (= :paid (:kontor.invoice/status (d/entity db [:kontor.invoice/external-id ext]))))

(deftest a-world-s-match-of-a-parent-invoice-is-replayed
  (let [conn (book)
        inv (#'fixture/create-and-send! conn "p-inv-1" #inst "2025-03-01T00:00:00Z")
        gross (:kontor.invoice/total-gross inv)
        base (d/db conn)
        w (world-of conn)
        line (import! w gross #inst "2025-03-20T00:00:00Z" "payment p-inv-1")]
    (match! w line (:db/id (:kontor.invoice/transaction inv)))
    (is (paid? (d/db w) "p-inv-1"))
    (let [intents (st/extract base (d/db w))]
      (is (= #{:import :match} (set (map :kind intents)))
          "the payment is not replayed as a plain entry: the match re-derives it")
      (st/stamp! conn intents)
      (let [db (d/db conn)]
        (is (paid? db "p-inv-1") "the parent re-derived the invoice's status")
        (is (= :reconciled (:kontor.bank-line/status
                            (d/entity db [:kontor.bank-line/external-id
                                          (:kontor.bank-line/external-id (d/entity (d/db w) line))]))))
        (is (= 1 (count (d/q '[:find [?a ...] :where [?a :kontor.payment-application/payment _]] db)))))
      (testing "a retry books nothing twice"
        (st/stamp! conn intents)
        (is (= 1 (count (d/q '[:find [?a ...] :where [?a :kontor.payment-application/payment _]]
                             (d/db conn)))))))))

(deftest a-world-can-invoice-and-be-paid
  (let [conn (book)
        base (d/db conn)
        w (world-of conn)
        inv (#'fixture/create-and-send! w "w-inv-1" #inst "2025-03-01T00:00:00Z")
        line (import! w (:kontor.invoice/total-gross inv) #inst "2025-03-20T00:00:00Z" "payment w-inv-1")]
    (match! w line (:db/id (:kontor.invoice/transaction inv)))
    (st/stamp! conn (st/extract base (d/db w)))
    (let [db (d/db conn)]
      (is (paid? db "w-inv-1") "the match settles the invoice's replayed posting")
      (is (= "RE/2025/0001" (:kontor.invoice/number (d/entity db [:kontor.invoice/external-id "w-inv-1"])))))))

(deftest a-bank-line-is-never-matched-twice
  (let [conn (book)
        inv (#'fixture/create-and-send! conn "p-inv-2" #inst "2025-03-01T00:00:00Z")
        gross (:kontor.invoice/total-gross inv)
        line (import! conn gross #inst "2025-03-20T00:00:00Z" "payment p-inv-2")
        base (d/db conn)
        w (world-of conn)]
    (match! w line (:db/id (:kontor.invoice/transaction inv)))
    ;; the parent matches the same line meanwhile
    (match! conn line (:db/id (:kontor.invoice/transaction inv)))
    (let [intents (st/extract base (d/db w))
          m (first (filter #(= :match (:kind %)) intents))
          line-key [:bank-line (:kontor.bank-line/external-id (d/entity base line))]]
      (is (contains? (:footprint m) line-key))
      (is (contains? (st/parent-claims base (d/db conn)) line-key) "a conflict for review")
      (testing "and stamping anyway is refused, not a second payment"
        (is (= :reconciliation/already-matched
               (try (st/stamp! conn [m]) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))
