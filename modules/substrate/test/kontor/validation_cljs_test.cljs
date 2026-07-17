(ns kontor.validation-cljs-test
  "Phase-D acceptance (note 192, rung 2): the composed structural gate runs in
   datahike-cljs. Requiring kontor.validation registers `validate-and-apply`
   (legal-hold ∘ sealing ∘ period ∘ state-machine ∘ sum-to-zero) into the gate.
   We exercise it directly against an in-memory datahike-cljs db — a good
   balanced entry passes; an unbalanced one throws :validation/sum-to-zero —
   proving every sub-validator loads and composes in cljs.

   NOTE — one datahike-cljs gap blocks the *invariant* half of the full
   `validate-candidate` on real posting data: `datahike/schema.cljc:12` defines
   `:db.type/bigdec` as `(complement any?)` in cljs, so the speculative
   `db-with` of a posting whose `:kontor.posting/amount` is a fress Bigdec is
   rejected. That is a datahike-side fix (accept `fress.impl.bigdec/Bigdec` for
   `:db.type/bigdec`); it does not affect reads from a konserve-synced replica
   (which bypass transact-time validation) nor the structural gate tested here,
   which reads amounts off the tx-data, not the db."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.validation :as validation]
            [kontor.money :as money]))

;; Minimal schema — the identity attrs so the tx-data lookup-refs are well
;; formed + what the structural validators query. `:kontor.posting/amount` is
;; deliberately NOT declared :db.type/bigdec here: validate-and-apply reads
;; amounts off the tx-data, never commits them, so the datahike-cljs bigdec
;; gap (see ns docstring) is irrelevant to this test.
(def schema
  [{:db/ident :kontor.account/path      :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/active    :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.commodity/symbol  :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/code      :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/type      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def rev  [:kontor.account/path "Income:Sales"])
(def cash-journal [:kontor.journal/code "CASH"])

(defn- posting [id acct amt-str]
  {:db/id id :kontor.posting/transaction -1 :kontor.posting/account acct
   :kontor.posting/amount (money/->amount amt-str)
   :kontor.posting/commodity [:kontor.commodity/symbol "EUR"]
   :kontor.posting/display-type :product})

(defn- tx [& postings]
  (into [{:db/id -1 :kontor.transaction/journal cash-journal
          :kontor.transaction/effective-date #inst "2026-03-15"
          :kontor.transaction/state :draft}]
        postings))

(deftest structural-gate-composes-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.commodity/symbol "EUR"}
                                      {:kontor.journal/code "CASH" :kontor.journal/type :cash}
                                      {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset  :kontor.account/active true}
                                      {:kontor.account/path "Income:Sales" :kontor.account/type :income :kontor.account/active true}]))
               (let [db @conn]
            ;; GOOD: balanced → validate-and-apply returns the tx-data unchanged.
                 (let [good (tx (posting -100 cash "100.00") (posting -101 rev "-100.00"))]
                   (is (= good (validation/validate-and-apply db good))
                       "balanced entry passes the composed structural gate in cljs"))
            ;; VIOLATION: 100 vs -99 → sum-to-zero throws.
                 (let [bad (tx (posting -100 cash "100.00") (posting -101 rev "-99.00"))
                       typ (try (validation/validate-and-apply db bad) :no-throw
                                (catch :default e (:type (ex-data e))))]
                   (is (= :validation/sum-to-zero typ)
                       (str "unbalanced entry throws :validation/sum-to-zero; got " typ)))
            ;; multi-commodity: EUR balances, USD does not → still throws.
                 (let [bad2 (tx (posting -100 cash "100.00") (posting -101 rev "-100.00")
                                (assoc (posting -102 cash "5.00") :kontor.posting/commodity [:kontor.commodity/symbol "USD"]))
                       typ (try (validation/validate-and-apply db bad2) :no-throw
                                (catch :default e (:type (ex-data e))))]
                   (is (= :validation/sum-to-zero typ)
                       "per-commodity sum-to-zero: a lone USD leg fails"))))
             (<! (d/delete-database cfg))
             (done)))))
