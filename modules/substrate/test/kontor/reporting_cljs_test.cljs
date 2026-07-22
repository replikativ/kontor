(ns kontor.reporting-cljs-test
  "Phase-E1a (note 192, rung 3): the core read path — account/trial balance —
   runs against a datahike-cljs db holding REAL :db.type/bigdec posting amounts.

   This is data-bearing thanks to the datahike-cljs `:db.type/bigdec` fix
   (accept the fress Bigdec) carried by modules/substrate/cljs-overlay (branch
   fix/cljs-bigdec-schema). Before it, storing a bigdec amount was rejected;
   now the browser stores + reads amounts and the ported reads sum them —
   exercised end-to-end here: d/db → d/as-of → bitemporal get-else → d/pull →
   money/sum-by-commodity, all in cljs."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.money :as money]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]))

(def schema
  [{:db/ident :kontor.account/path        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/entity      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   ;; the balance-side readers pull :ledger to scope to a parallel book (ADR-021)
   {:db/ident :kontor.posting/ledger      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount      :db/valueType :db.type/bigdec  :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def rev  [:kontor.account/path "Income:Sales"])

(defn- posting [id acct amt]
  {:db/id id :kontor.posting/transaction -1 :kontor.posting/account acct
   :kontor.posting/amount (money/->amount amt) :kontor.posting/commodity :EUR})

(defn- eid [conn path]
  (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn path))

(deftest core-reads-run-in-cljs-with-real-amounts
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales" :kontor.account/type :income}]))
          ;; two posted sales: 100 then 40. bigdec amounts now store in cljs.
               (let [r1 (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted}
                                               (posting -100 cash "100.00") (posting -101 rev "-100.00")]))]
                 (is (not (instance? js/Error r1))
                     (str "storing a :db.type/bigdec amount now succeeds in cljs; got "
                          (when (instance? js/Error r1) (.-message r1)))))
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted}
                                      (posting -100 cash "40.00") (posting -101 rev "-40.00")]))
          ;; reads sum the real amounts
               (let [cash-bal (balance/account-balance conn (eid conn "Assets:Cash"))
                     rev-bal  (balance/account-balance conn (eid conn "Income:Sales"))]
                 (is (money/equiv? (money/money "140.00" :EUR) (first (vals cash-bal)))
                     (str "Cash balance = 140.00 EUR in cljs; got " (pr-str cash-bal)))
                 (is (money/equiv? (money/money "-140.00" :EUR) (first (vals rev-bal)))
                     (str "Income balance = -140.00 EUR; got " (pr-str rev-bal))))
          ;; trial balance sums the whole book to zero per commodity
               (let [tb    (trial/trial-balance conn)
                     total (money/sum (mapcat vals (vals tb)) :EUR)]
                 (is (money/zero? total)
                     (str "trial balance sums to zero per commodity in cljs; got " (pr-str tb)))))
             (<! (d/delete-database cfg))
             (done)))))
