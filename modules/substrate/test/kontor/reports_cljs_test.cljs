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
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; ADR-022 / ADR-140 — the weighted analytic axis. `pull-posting` reads
   ;; these only when they are in the schema (a consumer doing no cost
   ;; accounting has none of them), so declaring them here is what gives the
   ;; cljs lane coverage of the POSITIVE branch: without it the guard could
   ;; silently disable analytics in cljs and the lane would still be green.
   {:db/ident :kontor.posting/analytic-distributions :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :kontor.analytic-distribution/plan :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.analytic-distribution/account :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.analytic-distribution/percent :db/valueType :db.type/bigdec :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.analytic-plan/code :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.analytic-account/code :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.analytic-account/path :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}])

;; The same schema minus every analytic attr — the shape a consumer that does
;; no cost accounting actually has, and the shape that used to make the report
;; engine throw `:transact/schema` on a plain P&L (ADR-140).
(def schema-without-analytics
  (let [analytic? (fn [{:db/keys [ident]}]
                    (contains? #{"kontor.analytic-distribution" "kontor.analytic-plan"
                                 "kontor.analytic-account"}
                               (namespace ident)))]
    (into [] (remove #(or (analytic? %)
                          (= :kontor.posting/analytic-distributions (:db/ident %))))
          schema)))

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

;; ============================================================================
;; ADR-140 — the weighted analytic axis, and the partial-schema guard
;;
;; `marginalize`'s `:weighted` axis is `.cljc`, so it gets cljs coverage on
;; principle. But the sharper reason both tests are here is that CI caught the
;; guard's ABSENCE on this lane: `pull-posting` read
;; `:kontor.posting/analytic-distributions` unconditionally, and this build
;; rejects undeclared attrs, so a plain P&L threw `:transact/schema` on any db
;; without the analytic block. The pair pins both directions — the axis really
;; apportions when the schema is there, and a plain report really runs when it
;; is not.
;; ============================================================================

(deftest analytic-weighted-axis-apportions-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)
                   cash [:kontor.account/path "Assets:Cash"]
                   exp  [:kontor.account/path "Expenses:Supplies"]]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash" :kontor.account/code "1000" :kontor.account/type :asset}
                                      {:kontor.account/path "Expenses:Supplies" :kontor.account/code "6800" :kontor.account/type :expense}
                                      {:kontor.analytic-plan/code "cost-center"}
                                      {:kontor.analytic-account/path "cc:A" :kontor.analytic-account/code "CC-A"}
                                      {:kontor.analytic-account/path "cc:B" :kontor.analytic-account/code "CC-B"}]))
               ;; 100 of supplies split 60/40 across CC-A / CC-B
               (<! (d/transact! conn
                                [{:db/id -1 :kontor.transaction/journal :gen :kontor.transaction/state :posted}
                                 (assoc (amt -100 exp "100.00")
                                        :kontor.posting/analytic-distributions [-200 -201])
                                 (amt -101 cash "-100.00")
                                 {:db/id -200 :kontor.analytic-distribution/plan [:kontor.analytic-plan/code "cost-center"]
                                  :kontor.analytic-distribution/account [:kontor.analytic-account/path "cc:A"]
                                  :kontor.analytic-distribution/percent (money/->amount "60")}
                                 {:db/id -201 :kontor.analytic-distribution/plan [:kontor.analytic-plan/code "cost-center"]
                                  :kontor.analytic-distribution/account [:kontor.analytic-account/path "cc:B"]
                                  :kontor.analytic-distribution/percent (money/->amount "40")}]))
               (let [ps    (report/report-postings conn {})
                     by-cc (report/marginalize ps {:analytic-plan "cost-center" :by :code}
                                               {:commodity :EUR})
                     v     #(some-> (get by-cc %) :value)]
                 (is (seq (filter (comp seq :analytics) ps))
                     "pull-posting surfaces :analytics in cljs when the attrs ARE in the schema")
                 (is (money/equiv? (money/money "60.00" :EUR) (v "CC-A"))
                     (str "CC-A gets 60% of 100.00 in cljs; got " (pr-str (v "CC-A"))))
                 (is (money/equiv? (money/money "40.00" :EUR) (v "CC-B"))
                     (str "CC-B gets 40% of 100.00 in cljs; got " (pr-str (v "CC-B"))))
                 ;; TEETH: set-valued treatment would report the FULL 100 under
                 ;; each class. Only a weighted fold sums back to the posting.
                 (is (money/equiv? (money/money "100.00" :EUR)
                                   (money/add (v "CC-A") (v "CC-B")))
                     "the classes sum back to the undistributed total"))
               (<! (d/delete-database cfg))
               (done))))))

(deftest report-engine-runs-on-a-schema-with-no-analytic-attrs-in-cljs
  ;; The regression CI caught. Nothing populates analytics here; the point is
  ;; that the engine must not DEMAND the attrs exist.
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)
                   cash [:kontor.account/path "Assets:Cash"]
                   inc  [:kontor.account/path "Income:Sales"]]
               (<! (d/transact! conn schema-without-analytics))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash" :kontor.account/code "1000" :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales" :kontor.account/code "4400" :kontor.account/type :income}]))
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :sale :kontor.transaction/state :posted}
                                      (amt -100 cash "100.00") (amt -101 inc "-100.00")]))
               (let [ps (report/report-postings conn {})]
                 (is (= 2 (count ps))
                     "report-postings pulls without throwing :transact/schema")
                 (is (every? (comp empty? :analytics) ps)
                     "and reports no analytics rather than failing")
                 (let [line (-> (report/compute-report
                                 conn {:report/lines
                                       [{:line/code "1" :line/label "Sales"
                                         :line/expression {:engine :account-codes :codes ["4400"]
                                                           :sign :inflow :commodity :EUR}}]}
                                 {})
                                :report/lines first)]
                   (is (money/equiv? (money/money "100.00" :EUR) (:line/value line))
                       (str "a plain P&L line still computes; got " (pr-str (:line/value line))))))
               (<! (d/delete-database cfg))
               (done))))))
