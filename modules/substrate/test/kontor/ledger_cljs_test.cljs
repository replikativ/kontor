(ns kontor.ledger-cljs-test
  "The account-ledger read path — `kontor.reporting.ledger/postings-against`
   + `running-balance` — running against a datahike-cljs db with REAL
   `:db.type/bigdec` amounts.

   `ledger` was the one read-side namespace with no cljs exercise (it is
   `.cljc`, so it compiled, but nothing proved it RAN). It is the account-
   statement view a frontend renders — the ordered postings against an
   account with a per-row running balance — so it is exactly the kind of
   read a client computes locally. `reporting-coverage-test` (JVM) now
   fails if a reporting namespace is left uncovered like this again."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.money :as money]
            [kontor.reporting.ledger :as ledger]))

(def schema
  [{:db/ident :kontor.account/path        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount      :db/valueType :db.type/bigdec  :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/narration   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   ;; postings-against pulls partner + ledger; the attrs must exist even
   ;; though these fixtures set neither
   {:db/ident :kontor.posting/partner     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/ledger      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/narration :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def rev  [:kontor.account/path "Income:Sales"])

(defn- posting [id acct amt narr]
  {:db/id id :kontor.posting/transaction -1 :kontor.posting/account acct
   :kontor.posting/amount (money/->amount amt) :kontor.posting/commodity :EUR
   :kontor.posting/narration narr})

(defn- eid [conn path]
  (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn path))

(deftest ledger-reads-run-in-cljs-with-real-amounts
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales" :kontor.account/type :income}]))
               ;; two posted sales against Cash: +100 then +40
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted}
                                      (posting -100 cash "100.00" "Sale 1")
                                      (posting -101 rev "-100.00" "Sale 1")]))
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted}
                                      (posting -100 cash "40.00" "Sale 2")
                                      (posting -101 rev "-40.00" "Sale 2")]))
               (let [cash-eid (eid conn "Assets:Cash")
                     rows     (ledger/postings-against conn cash-eid)]
                 (is (= 2 (count rows))
                     (str "two postings against Cash; got " (pr-str rows)))
                 (is (every? #(money/money? (:amount %)) rows)
                     "each ledger row carries a Money :amount")
                 (is (= #{"Sale 1" "Sale 2"} (set (map :narration rows)))
                     "narrations round-trip through the cljs read"))
               ;; running balance is cumulative: 100 then 140
               (let [cash-eid (eid conn "Assets:Cash")
                     runs     (ledger/running-balance conn cash-eid)
                     amounts  (map #(:running %) runs)]
                 (is (money/equiv? (money/money "100.00" :EUR) (first amounts))
                     (str "running balance after Sale 1 = 100.00; got " (pr-str (first amounts))))
                 (is (money/equiv? (money/money "140.00" :EUR) (last amounts))
                     (str "running balance after Sale 2 = 140.00; got " (pr-str (last amounts))))))
             (<! (d/delete-database cfg))
             (done)))))
