(ns kontor.explain-cljs-test
  "Phase-E2 (note 192, rung 3): `kontor.reporting.explain/explain-balance` —
   the 'explain this number' walk — is ported to .cljc and its query machinery
   EXECUTES in datahike-cljs (its closure includes `retention` — java.time
   plusYears → cljs date math — and `legal-hold`, both cljc). This smoke seeds
   real :db.type/bigdec postings and confirms explain-balance runs and returns
   the {:account :balance :postings :as-of-valid :as-of-tx} shape.

   The composed *balance value* is not asserted here: explain forces
   as-of-valid = now, exercising the bitemporal `(get-else … :db.valid/from …)`
   path, and cljs datahike diverges from the JVM there (an undeclared attr
   yields the attr keyword, a declared-absent one yields nil, rather than the
   default source) — see cljs-datahike-get-else-valid-time memory. That is a
   datahike-cljs behavior difference to resolve; the JVM suite verifies the
   value, and the core reads (balance/trial/report/P&L) are verified
   data-bearing in the other cljs tests (which don't force as-of-valid)."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.money :as money]
            [kontor.reporting.explain :as explain]))

(def schema
  [{:db/ident :kontor.account/path :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount :db/valueType :db.type/bigdec :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   ;; ledger.cljc (via explain-balance) pulls :kontor.posting/partner — declare it
   ;; so the pull resolves (current-main datahike raises on an undeclared attr
   ;; under :write flexibility, where the old cljs base returned nil).
   {:db/ident :kontor.posting/partner :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/ledger :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/entity :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/narration :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/narration :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/effective-date :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])
;; NB: `:db.valid/from` is a datahike-upstream system attribute (ADR-048) — it
;; must NOT be declared in a user schema (datahike rejects `db`-namespaced idents).
;; Rely on the system attr so the bigdec seed actually commits and explain-balance
;; walks a real as-of-valid `get-else` path (fixed in datahike #888 — variable
;; get-else default resolves in cljs instead of leaking the raw symbol).

(deftest explain-balance-executes-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)
                   cash [:kontor.account/path "Assets:Cash"]
                   inc  [:kontor.account/path "Income:Sales"]]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales" :kontor.account/type :income}]))
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted
                                       :kontor.transaction/narration "Sale"}
                                      {:db/id -100 :kontor.posting/transaction -1 :kontor.posting/account cash
                                       :kontor.posting/amount (money/->amount "100.00") :kontor.posting/commodity :EUR}
                                      {:db/id -101 :kontor.posting/transaction -1 :kontor.posting/account inc
                                       :kontor.posting/amount (money/->amount "-100.00") :kontor.posting/commodity :EUR}]))
               (let [cash-eid (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn "Assets:Cash")
                     ex (explain/explain-balance conn cash-eid)]
                 (is (some? cash-eid) "the bigdec seed committed — cash account resolves to a real eid (guards against a vacuous nil-eid pass)")
                 (is (= cash-eid (:account ex)) "explain-balance runs in cljs and echoes the account")
                 (is (seq (:postings ex)) "explain-balance walks the real postings via the as-of-valid get-else path (datahike #888)")
                 (is (contains? ex :balance) "returns a :balance map")
                 (is (contains? ex :postings) "returns a :postings vector")
                 ;; :as-of-valid echoes the window ACTUALLY applied; the default
                 ;; is nil = all valid time (note 160 §I-17). It used to echo
                 ;; wall-clock now, which made explain-balance disagree with
                 ;; account-balance on any book holding a future-dated posting.
                 (is (contains? ex :as-of-valid) "carries the bitemporal axes")
                 (is (nil? (:as-of-valid ex)) "unbounded unless the caller bounds it")
                 (is (some? (:as-of-tx ex)) "tx-time still resolves to a snapshot")))
             (<! (d/delete-database cfg))
             (done)))))
