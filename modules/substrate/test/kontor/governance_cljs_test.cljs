(ns kontor.governance-cljs-test
  "Phase-E (note 192): `kontor.governance/validate-report` — the post-resolution
   governed-store validator (ADR-118 / note 193) — EXECUTES in datahike-cljs.

   simmis runs governance for optimistic, browser-side pre-checks, so the
   governance ns's `runs identically JVM + cljs` claim must be EXERCISED in
   cljs, not merely asserted. This builds resolved reports with
   `datahike.core/with` — the exact `{:db-before :db-after :tx-data}` shape a
   `datahike.tx-preds` tx-pred receives on the JVM writer — and runs them through
   the governor in the browser runtime: a balanced write is ACCEPTED, an
   unbalanced write and a retract of a posted leg are REJECTED.

   It is data-bearing on the datahike-cljs `:db.type/bigdec` fix (accept the fress
   Bigdec) — the posting amounts are real `Bigdec`s, and `balance-violations`
   sums them via `money/add-amount` (NOT datahike's `sum` aggregate, which cannot
   add cljs Bigdec values)."
  (:require [cljs.test :refer [deftest is async]]
            [cljs.core.async :refer [go <!] :include-macros true]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.money :as money]
            [kontor.governance :as gov]))

(def schema
  [{:db/ident :kontor.account/path        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.account/type        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/account     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/amount      :db/valueType :db.type/bigdec  :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/commodity   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/transaction :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.posting/posted-at   :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/state   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.transaction/journal :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   ;; ADR-153 — the attribution family. `:kontor.actor/uid` is
   ;; `:db.unique/identity` in the kernel schema; keep that here so the cljs
   ;; lane exercises the same shape the guard sees on the JVM.
   {:db/ident :kontor.actor/uid           :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.actor/active        :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :kontor.audit/create-uid    :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}])

(def cash [:kontor.account/path "Assets:Cash"])
(def rev  [:kontor.account/path "Income:Sales"])

(defn- outcome
  "Run the governor on report `r`; return :accepted or the rejection :type."
  [r]
  (try (gov/validate-report r) :accepted
       (catch :default e (:type (ex-data e)))))

(defn- leg
  "A posting map (auto-tempid) on transaction tempid `tx`, against account
   lookup-ref `acct`, for raw amount string `amt`."
  [tx acct amt]
  {:kontor.posting/transaction tx :kontor.posting/account acct
   :kontor.posting/amount (money/->amount amt) :kontor.posting/commodity :EUR})

(deftest validate-report-runs-in-cljs
  (async done
         (go
           (let [cfg {:store {:backend :memory :id (random-uuid)}
                      :schema-flexibility :write :keep-history? true}]
             (<! (d/create-database cfg))
             (let [conn (d/connect cfg)]
               (<! (d/transact! conn schema))
               (<! (d/transact! conn [{:kontor.account/path "Assets:Cash"  :kontor.account/type :asset}
                                      {:kontor.account/path "Income:Sales" :kontor.account/type :income}]))
               ;; one POSTED balanced tx — posted-at set on both legs, so the sealing
               ;; check has a posted entity to protect in db-before.
               (<! (d/transact! conn [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :posted}
                                      {:db/id -100 :kontor.posting/transaction -1 :kontor.posting/account cash
                                       :kontor.posting/amount (money/->amount "100.00") :kontor.posting/commodity :EUR
                                       :kontor.posting/posted-at #inst "2026-03-15"}
                                      {:db/id -101 :kontor.posting/transaction -1 :kontor.posting/account rev
                                       :kontor.posting/amount (money/->amount "-100.00") :kontor.posting/commodity :EUR
                                       :kontor.posting/posted-at #inst "2026-03-15"}]))
               (let [db  @conn
                     pd  (d/q '[:find ?p . :in $ ?a :where
                                [?p :kontor.posting/account ?a]
                                [?p :kontor.posting/posted-at _]] db cash)]
                 (is (some? pd) "the bigdec-bearing posted fixture committed in cljs")
                 ;; 1. a balanced NEW tx is ACCEPTED
                 (is (= :accepted
                        (outcome (dc/with db [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :draft}
                                              (leg -1 cash "50.00") (leg -1 rev "-50.00")])))
                     "validate-report accepts a balanced report in cljs")
                 ;; 2. an UNBALANCED new tx is REJECTED (balance sums bigdec via money/add-amount)
                 (is (= :validation/sum-to-zero
                        (outcome (dc/with db [{:db/id -1 :kontor.transaction/journal :cash :kontor.transaction/state :draft}
                                              (leg -1 cash "5.00") (leg -1 rev "-4.00")])))
                     "validate-report rejects an unbalanced report in cljs")
                 ;; 3. a retractEntity of a POSTED leg is REJECTED (sealing, via db-before)
                 (is (= :sealing/silent-retract-of-posted
                        (outcome (dc/with db [[:db/retractEntity pd]])))
                     "validate-report catches destruction of a posted entity in cljs")
                 ;; 4-6. ADR-153 attribution. `retract-entity` retracts the
                 ;; inbound ref datoms too, so deleting the actor nils
                 ;; `:kontor.audit/create-uid` on everything they created —
                 ;; which, with `:no-self-approval` failing closed, strands
                 ;; those entities. The guard must run in the browser too:
                 ;; simmis pre-checks writes optimistically, and a pre-check
                 ;; that accepts what the writer will reject is worse than no
                 ;; pre-check.
                 (let [db2 (:db-after (dc/with db [{:db/id -1 :kontor.actor/uid "alice"}
                                                   {:db/id -2 :kontor.transaction/journal :cash
                                                    :kontor.transaction/state :draft
                                                    :kontor.audit/create-uid -1}]))
                       alice (d/q '[:find ?a . :where [?a :kontor.actor/uid "alice"]] db2)
                       doc   (d/q '[:find ?e . :where [?e :kontor.audit/create-uid _]] db2)]
                   (is (= :kontor.actor/attribution-destroyed
                          (outcome (dc/with db2 [[:db/retractEntity alice]])))
                       "deleting an actor is refused in cljs")
                   (is (= :kontor.actor/attribution-destroyed
                          (outcome (dc/with db2 [[:db/retract doc :kontor.audit/create-uid alice]])))
                       "a bare creator retraction is refused in cljs")
                   (is (= :accepted
                          (outcome (dc/with db2 [{:db/id alice :kontor.actor/active false}])))
                       "deactivation — the modelled path — is still permitted in cljs"))))
             (<! (d/delete-database cfg))
             (done)))))
