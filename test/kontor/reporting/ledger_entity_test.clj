(ns kontor.reporting.ledger-entity-test
  "Tests for the parallel-ledger entity helpers in kontor.reporting.ledger
   (ADR-021). The per-account statement-view tests live in
   `kontor.reporting.ledger-test` and are unaffected by this namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.reporting.ledger :as ledger]))

(deftest primary-ledger-bootstrapped
  (testing "install-schema! seeds the primary ledger entity"
    (let [conn (core/create-test-db)
          db   (d/db conn)
          eid  (ledger/primary db)]
      (is (some? eid)
          "Primary ledger eid must be resolvable after install-schema!")
      (let [pulled (d/pull db '[*] eid)]
        (is (= "primary"        (:kontor.ledger/code pulled)))
        (is (= "Primary ledger" (:kontor.ledger/name pulled)))
        (is (= :primary         (:kontor.ledger/type pulled)))
        (is (= :local           (:kontor.ledger/framework pulled)))
        (is (true?              (:kontor.ledger/active pulled)))))))

(deftest install-defaults-is-idempotent
  (testing "Re-running install-defaults! does not duplicate the
            primary ledger (unique-identity match collapses)"
    (let [conn (core/create-test-db)
          _ (ledger/install-defaults! conn)
          _ (ledger/install-defaults! conn)
          db (d/db conn)
          eids (d/q '[:find [?e ...]
                      :where [?e :kontor.ledger/code "primary"]]
                    db)]
      (is (= 1 (count eids))
          "Idempotent install must keep exactly one primary entity"))))

(deftest by-code-resolves-secondary-ledgers
  (testing "Consumers can add secondary ledgers; by-code resolves them"
    (let [conn (core/create-test-db)
          _ (d/transact conn
                        [{:kontor.ledger/code      "ifrs"
                          :kontor.ledger/name      "IFRS reporting ledger"
                          :kontor.ledger/type      :secondary
                          :kontor.ledger/framework :IFRS
                          :kontor.ledger/active    true}
                         {:kontor.ledger/code      "hgb"
                          :kontor.ledger/name      "HGB statutory ledger"
                          :kontor.ledger/type      :secondary
                          :kontor.ledger/framework :HGB
                          :kontor.ledger/active    true}])
          db (d/db conn)]
      (is (some? (ledger/by-code db "ifrs")))
      (is (some? (ledger/by-code db "hgb")))
      (is (nil?  (ledger/by-code db "nope"))
          "Missing code returns nil"))))

(deftest resolve-ledger-coerces-spec
  (let [conn (core/create-test-db)
        db   (d/db conn)
        prim (ledger/primary db)]
    (testing "nil → primary"
      (is (= prim (ledger/resolve-ledger db nil))))
    (testing "string → looked up by :kontor.ledger/code"
      (is (= prim (ledger/resolve-ledger db "primary"))))
    (testing "long eid → returned as-is"
      (is (= prim (ledger/resolve-ledger db prim))))))

(deftest posting-ledger-attr-installed
  (testing ":kontor.posting/ledger attribute is part of the schema"
    (let [conn (core/create-test-db)
          db   (d/db conn)
          attr (d/pull db '[*] :kontor.posting/ledger)]
      (is (= :db.type/ref         (:db/valueType attr)))
      (is (= :db.cardinality/one  (:db/cardinality attr))))))

(deftest account-required-analytic-plans-attr-installed
  (testing ":kontor.account/required-analytic-plans attribute is part of
            the schema (ADR-022)"
    (let [conn (core/create-test-db)
          db   (d/db conn)
          attr (d/pull db '[*] :kontor.account/required-analytic-plans)]
      (is (= :db.type/ref          (:db/valueType attr)))
      (is (= :db.cardinality/many  (:db/cardinality attr))))))
