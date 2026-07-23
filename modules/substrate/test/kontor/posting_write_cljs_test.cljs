(ns kontor.posting-write-cljs-test
  "The WRITE path, end to end, in ClojureScript: build a balanced sealed
   entry with the portable builder, commit it through the REAL gate
   (`kontor.gate/transact-with-validation` — the `[:db.fn/call
   validate-and-apply …]` transact wrap, not a direct call), then read it
   back with `account-balance` / `trial-balance`.

   This is the keystone the other cljs tests stop short of:
   `book-build-cljs-test` builds tx-data but never commits it;
   `validation-cljs-test` calls `validate-and-apply` directly, not through
   `d/transact`; `reporting-cljs-test` commits amounts but bypasses the
   gate. Here the frontend does what a backend does — build, gate-validate,
   commit, read — over datahike-cljs with real `:db.type/bigdec` amounts,
   proving there is one write path, not two.

   (The datahike-cljs `:db.type/bigdec` gap the validation-cljs-test
   docstring warns about is closed as of datahike 0.8.1744 — fress Bigdec
   is accepted — which is what lets the gate COMMIT amounts here, not just
   inspect them.)"
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.book.build :as bb]
            [kontor.gate :as gate]
            ;; requiring kontor.validation registers validate-and-apply into the gate
            [kontor.validation]
            [kontor.money :as money]
            [kontor.reporting.balance :as balance]
            [kontor.reporting.trial :as trial]))

(def schema
  [{:db/ident :kontor.account/path        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/active      :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.commodity/symbol    :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/code        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.journal/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   ;; the balance/ledger reads scope by entity + ledger, so the attrs must exist
   {:db/ident :kontor.posting/entity      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/ledger      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount      :db/valueType :db.type/bigdec  :db/cardinality :db.cardinality/one}
   ;; commodity is a ref — the kernel canonical shape entry-tx-data promotes
   ;; :EUR into ([:kontor.commodity/symbol "EUR"]), so balances key by its eid
   {:db/ident :kontor.posting/commodity   :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/display-type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/effective-date :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/posted-at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   ;; the seal stamps posted-at on the postings too (kontor.compliance.sealing)
   {:db/ident :kontor.posting/posted-at   :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def rev  [:kontor.account/path "Income:Sales"])

(defn- eid [conn path]
  (d/q '[:find ?e . :in $ ?p :where [?e :kontor.account/path ?p]] @conn path))

(deftest write-path-commits-a-gate-validated-entry-in-cljs
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
               ;; Build a balanced, sealed two-leg entry with the SAME portable
               ;; builder the backend uses, then commit through the gate.
               (let [tx-data (bb/entry-tx-data
                              {:journal        [:kontor.journal/code "CASH"]
                               :effective-date #inst "2026-03-15"
                               :commodity      :EUR
                               :debit-account  cash
                               :credit-account rev
                               :amount         (money/->amount "100.00")})
                     result (<! (gate/transact-with-validation conn tx-data))]
                 (is (not (instance? js/Error result))
                     (str "the gate committed a balanced entry in cljs; got "
                          (when (instance? js/Error result) (.-message result))))
                 ;; read it back — same reporting code as the backend. Balances
                 ;; key by the commodity eid (ref-typed), so compare amounts.
                 (let [cash-bal (balance/account-balance conn (eid conn "Assets:Cash"))
                       rev-bal  (balance/account-balance conn (eid conn "Income:Sales"))
                       amt      (fn [m] (money/amount->double (:amount m)))]
                   (is (= 1 (count cash-bal)) "one commodity bucket on Cash")
                   (is (== 100.0 (amt (first (vals cash-bal))))
                       (str "Cash = 100.00 after the gated write; got " (pr-str cash-bal)))
                   (is (== -100.0 (amt (first (vals rev-bal))))
                       (str "Income = -100.00; got " (pr-str rev-bal))))
                 ;; the book balances: every posting nets to zero
                 (let [tb     (trial/trial-balance conn)
                       amts   (map (comp money/amount->double :amount) (mapcat vals (vals tb)))]
                   (is (== 0.0 (reduce + amts))
                       (str "trial balance is zero after the gated write; got " (pr-str tb)))))
               ;; and the gate REJECTS an unbalanced entry on cljs, same as JVM.
               ;; datahike-cljs delivers a transact error as a js/Error VALUE on
               ;; the channel (not a throw), so we inspect the returned value.
               (let [bad [{:db/id -1 :kontor.transaction/journal [:kontor.journal/code "CASH"]
                           :kontor.transaction/effective-date #inst "2026-03-16"
                           :kontor.transaction/state :draft}
                          {:db/id -100 :kontor.posting/transaction -1 :kontor.posting/account cash
                           :kontor.posting/amount (money/->amount "50.00")
                           :kontor.posting/commodity :EUR
                           :kontor.posting/display-type :product}
                          {:db/id -101 :kontor.posting/transaction -1 :kontor.posting/account rev
                           :kontor.posting/amount (money/->amount "-49.00")   ; ← does not balance
                           :kontor.posting/commodity :EUR
                           :kontor.posting/display-type :product}]
                     result (<! (gate/transact-with-validation conn bad))]
                 (is (instance? js/Error result)
                     "an unbalanced entry is rejected by the gate in cljs (error on channel)")
                 (is (and (instance? js/Error result)
                          (re-find #"sum to zero" (or (.-message result) "")))
                     (str "the rejection is the sum-to-zero violation; got "
                          (when (instance? js/Error result) (.-message result))))))
             (<! (d/delete-database cfg))
             (done)))))
