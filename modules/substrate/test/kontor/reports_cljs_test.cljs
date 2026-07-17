(ns kontor.reports-cljs-test
  "Phase-E1b (note 192, rung 3): the declarative report engine
   (`kontor.reporting.report`) and the P&L/BS builder
   (`kontor.reporting.financial-statements`) run against a datahike-cljs db
   with REAL :db.type/bigdec amounts (unblocked by the datahike bigdec fix).

   fx (`kontor.reporting.report` requires it) compiles in cljs but its
   multi-currency `convert` throws until a bigdec rounding shim lands
   (note 191 open item); single-currency reports — the common case — never
   reach it and work here end-to-end."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [kontor.money :as money]
            [kontor.reporting.report :as report]
            [kontor.reporting.financial-statements :as fs]))

;; Full set of attrs the report engine pulls (this datahike-cljs build rejects
;; undeclared attrs), even the ones this fixture doesn't populate.
(def schema
  [{:db/ident :kontor.account/path :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/code :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/tags :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :kontor.account-tag/name :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount :db/valueType :db.type/bigdec :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/ledger :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/entity :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/partner :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account-tags :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :kontor.posting/dimensions :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :kontor.posting-dimension/axis :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting-dimension/value :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}])

(def pnl-def
  {:statement/name "P&L"
   :statement/sections
   [{:section/code "I" :section/label "Income"
     :section/lines [{:line/code "1" :line/label "Sales" :line/codes ["4400"] :line/sign :inflow}]}
    {:section/code "E" :section/label "Expenses"
     :section/lines [{:line/code "2" :line/label "Supplies" :line/codes ["6800"] :line/sign :inflow}]}]})

(defn- amt [id acct a] {:db/id id :kontor.posting/transaction -1 :kontor.posting/account acct
                        :kontor.posting/amount (money/->amount a) :kontor.posting/commodity :EUR})

(deftest report-engine-runs-in-cljs-with-real-amounts
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)
                   cash [:kontor.account/path "Assets:Cash"]
                   inc  [:kontor.account/path "Income:Sales"]
                   exp  [:kontor.account/path "Expenses:Supplies"]]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash"        :kontor.account/code "1000" :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales"       :kontor.account/code "4400" :kontor.account/type :income}
                                      {:kontor.account/path "Expenses:Supplies"  :kontor.account/code "6800" :kontor.account/type :expense}]))
          ;; sale 100: Dr Cash / Cr Income
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :sale :kontor.transaction/state :posted}
                                      (amt -100 cash "100.00") (amt -101 inc "-100.00")]))
          ;; expense 30: Dr Expense / Cr Cash
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :gen :kontor.transaction/state :posted}
                                      (amt -100 exp "30.00") (amt -101 cash "-30.00")]))
          ;; report engine: :account-codes line summing the 4400 income (inflow → +100)
               (let [r (report/compute-report conn
                                              {:report/lines [{:line/code "1" :line/label "Sales"
                                                               :line/expression {:engine :account-codes :codes ["4400"] :sign :inflow :commodity :EUR}}]}
                                              {})
                     line (first (:report/lines r))]
                 (is (money/equiv? (money/money "100.00" :EUR) (:line/value line))
                     (str "report :account-codes line sums 4400 income to +100.00 in cljs; got " (pr-str (:line/value line)))))
          ;; P&L statement: income subtotal 100, expense subtotal 30
               (let [st       (fs/compute-statement conn pnl-def {})
                     sections (into {} (map (juxt :section/code :section/subtotal) (:statement/sections st)))]
                 (is (money/equiv? (money/money "100.00" :EUR) (get sections "I"))
                     (str "P&L income subtotal = 100.00 in cljs; got " (pr-str (get sections "I"))))
                 (is (money/equiv? (money/money "30.00" :EUR) (get sections "E"))
                     (str "P&L expense subtotal = 30.00 in cljs; got " (pr-str (get sections "E"))))))
             (<! (d/delete-database cfg))
             (done)))))
