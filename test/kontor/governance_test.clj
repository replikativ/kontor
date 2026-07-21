(ns kontor.governance-test
  "The report-based governor (`kontor.governance/validate-report`) — the
   post-resolution realization of the gate for governed stores (ADR-118 /
   research note 193). Reports are built with `datahike.core/with` (exactly the
   resolved shape a `datahike.tx-preds` tx-pred receives) and run through the
   governor: the full red-team battery must be REJECTED, legitimate writes
   ACCEPTED."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.book.build :as build]
            [kontor.governance :as gov]))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private gen  [:kontor.journal/code "GEN"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])
(def ^:private arch [:kontor.account/path "Archived"])
(def ^:private d1 #inst "2026-03-15")

(defn- setup
  "Fresh governed-style book with one POSTED balanced tx; returns {:conn :pd :pc}
   (the posted debit/credit posting eids)."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset  :kontor.account/active true}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income :kontor.account/active true}
                 {:kontor.account/path "Archived"     :kontor.account/type :asset  :kontor.account/active false}])
    (gate/transact-with-validation conn
                                   (build/entry-tx-data {:debit-account cash :credit-account rev :amount 1000
                                                         :commodity eur :journal gen :effective-date d1}))
    {:conn conn
     :pd (d/q '[:find ?p . :where [?p :kontor.posting/account ?a]
                [?a :kontor.account/path "Assets:Cash"]] @conn)
     :pc (d/q '[:find ?p . :where [?p :kontor.posting/account ?a]
                [?a :kontor.account/path "Income:Sales"]] @conn)}))

(defn- outcome
  "Build the resolved report for `txf`'s tx-data and run the governor; returns
   :accepted or the rejection `:type`."
  [txf]
  (let [{:keys [conn] :as s} (setup)]
    (try (gov/validate-report (dc/with @conn (txf s))) :accepted
         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))

(deftest rejects-retract-of-posted-leg
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd]}] [[:db/retract pd :kontor.posting/amount 1000M]])))))

(deftest rejects-retract-entity-of-posted
  ;; the A2 vector — retractEntity of a posted posting (report-level, via db-before)
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd]}] [[:db/retractEntity pd]])))))

(deftest rejects-in-place-edit-of-posted
  ;; the A4 vector — an upsert that rewrites a posted amount
  (is (contains? #{:sealing/silent-retract-of-posted :validation/sum-to-zero}
                 (outcome (fn [{:keys [pd]}] [{:db/id pd :kontor.posting/amount 9999M}])))))

(deftest rejects-retract-both-legs
  ;; A7 — balance stays 0, only the sealing scan catches the audit destruction
  (is (= :sealing/silent-retract-of-posted
         (outcome (fn [{:keys [pd pc]}] [[:db/retractEntity pd] [:db/retractEntity pc]])))))

(deftest rejects-unbalanced-new-tx
  (is (= :validation/sum-to-zero
         (outcome (fn [_]
                    [{:db/id -1 :kontor.transaction/journal gen
                      :kontor.transaction/effective-date d1 :kontor.transaction/state :draft}
                     {:db/id -2 :kontor.posting/transaction -1 :kontor.posting/account cash
                      :kontor.posting/amount 5M :kontor.posting/commodity eur :kontor.posting/display-type :product}
                     {:db/id -3 :kontor.posting/transaction -1 :kontor.posting/account rev
                      :kontor.posting/amount -4M :kontor.posting/commodity eur :kontor.posting/display-type :product}])))))

(deftest rejects-posting-to-inactive-account
  ;; the datalog invariant tier (account-active) fires on the resolved report
  (is (= :invariant/invariant-mismatch
         (outcome (fn [_] (build/entry-tx-data {:debit-account arch :credit-account rev :amount 50
                                                :commodity eur :journal gen :effective-date d1}))))))

(deftest accepts-legitimate-writes
  (testing "a new balanced tx"
    (is (= :accepted
           (outcome (fn [_] (build/entry-tx-data {:debit-account cash :credit-account rev :amount 50
                                                  :commodity eur :journal gen :effective-date d1}))))))
  (testing "re-asserting the SAME value on a posted row is a no-op (no false positive)"
    (is (= :accepted
           (outcome (fn [{:keys [pd]}] [{:db/id pd :kontor.posting/amount 1000M}]))))))

(deftest short-circuits-source-construction-when-nothing-is-keyed
  ;; `invariant-violations` resolves WHICH invariants apply before building the
  ;; sources they need. That ordering is load-bearing: `report-empty+txs` builds
  ;; an empty db over the store's ENTIRE schema, so it scales with the schema
  ;; rather than the delta, and `validate-report` runs in the writer on every
  ;; committed transaction — including writes from a co-tenant (chat, wiki) that
  ;; keys no invariant at all. Asserted structurally rather than by timing, which
  ;; would be flaky, and here rather than nowhere, because reverting the ordering
  ;; keeps every behavioural test in this namespace green.
  (let [{:keys [conn]} (setup)
        ;; opening an account touches no attribute any invariant is keyed on
        ;; (the kernel keys :kontor.posting/account + :kontor.posting/commodity)
        unkeyed (dc/with @conn [{:kontor.account/path "Assets:Bank"
                                 :kontor.account/type :asset
                                 :kontor.account/active true}])
        keyed   (dc/with @conn (build/entry-tx-data {:debit-account cash :credit-account rev :amount 12
                                                     :commodity eur :journal gen :effective-date d1}))
        calls   (atom 0)
        real    @#'gov/report-empty+txs]
    (with-redefs [gov/report-empty+txs (fn [r] (swap! calls inc) (real r))]
      (testing "no keyed attribute — the sources are never built"
        (is (= [] (gov/invariant-violations unkeyed)))
        (is (zero? @calls)))
      (testing "a keyed attribute — the sources ARE built, exactly once"
        (is (= [] (gov/invariant-violations keyed)))
        (is (= 1 @calls))))))

(deftest violation-fns-are-pure-over-the-report
  (let [{:keys [conn]} (setup)
        good (dc/with @conn (build/entry-tx-data {:debit-account cash :credit-account rev :amount 7
                                                  :commodity eur :journal gen :effective-date d1}))]
    (is (= [] (gov/balance-violations good)))
    (is (= [] (gov/sealing-violations good)))
    (is (= [] (gov/invariant-violations good)))
    (is (nil? (gov/validate-report good)))))
