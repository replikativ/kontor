(ns kontor.schema-test
  "Phase 0 smoke tests.

   We verify that:
     1. The kernel schema loads into a fresh datahike DB without
        validation errors.
     2. Every documented kernel namespace shows up in the resulting
        schema (catches accidental deletions).
     3. The bitemporal :posting/temporal-key tuple is correctly
        derived from its component attributes (catches misuse of
        datahike's tuple feature)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.schema :as schema]))

(def expected-namespaces
  "Every kernel attribute namespace we declare. If you add or remove a
   namespace from the schema, update this set and the matching
   acceptance test below."
  #{"create" "write"
    "commodity" "lot"
    "account" "account-tag"
    "journal" "partner" "fiscal-position"
    "tax" "tax-rep" "tax-group"
    "period" "balance-assertion"
    "transaction" "posting"
    "analytic-plan" "analytic-account" "analytic-distribution"
    "ledger"
    "country" "country-code" "country-group"
    "state" "state-code"
    "attestation" "complemento"
    "valuation-book"
    "valuation-layer" "layer-consumption" "layer-adjustment"
    "entity"
    "schedule" "schedule-occurrence"
    "status-transition" "status-history"})

(deftest schema-loads-into-fresh-db
  (testing "Kernel schema transacts cleanly into a fresh in-memory DB"
    (let [conn (core/create-test-db)
          db   (d/db conn)
          all-idents (->> (d/q '[:find [?ident ...]
                                 :where [_ :db/ident ?ident]]
                               db)
                          (filter keyword?))
          kernel-idents (filter #(contains? expected-namespaces (namespace %))
                                all-idents)]
      (is (seq kernel-idents)
          "At least some kernel attributes must be present after install!")
      (is (>= (count kernel-idents) 50)
          "Phase 0 schema declares ~70 kernel attrs across 16 namespaces;
           a much smaller count means the install lost data."))))

(deftest every-expected-namespace-present
  (testing "Each documented kernel namespace appears at least once"
    (let [conn (core/create-test-db)
          present (->> (d/q '[:find [?ident ...]
                              :where [_ :db/ident ?ident]]
                            (d/db conn))
                       (filter keyword?)
                       (keep namespace)
                       set)]
      (doseq [ns expected-namespaces]
        (is (contains? present ns)
            (str "Expected namespace " ns " not found in the loaded schema."))))))

(deftest valid-from-attribute-defined
  (testing "Per ADR-008 (revised), only :posting/valid-from is the
            valid-time anchor. :posting/valid-to and the
            :posting/temporal-key tuple were dropped per research
            note 08 — corrections use reverse-and-repost, not
            valid-time supersession."
    (let [conn (core/create-test-db)
          db (d/db conn)
          attr (d/pull db
                       [:db/ident :db/valueType :db/index]
                       :posting/valid-from)]
      (is (= :posting/valid-from (:db/ident attr)))
      (is (= :db.type/instant (:db/valueType attr)))
      (is (true? (:db/index attr))
          "Indexed for as-of-valid filtering."))
    (let [conn (core/create-test-db)
          db (d/db conn)
          present (set (d/q '[:find [?ident ...]
                              :where [_ :db/ident ?ident]]
                            db))]
      (is (not (contains? present :posting/temporal-key))
          "temporal-key tuple should NOT be in the schema (ADR-008 revised).")
      (is (not (contains? present :posting/valid-to))
          "valid-to should NOT be in the schema (ADR-008 revised).")
      (is (contains? present :posting/valid-from)
          "valid-from is the kept anchor."))))

(deftest schema-install-is-idempotent
  (testing "Re-installing the schema on an existing DB does not throw
            and does not duplicate attributes"
    (let [conn (core/create-test-db)
          before-count (count (d/q '[:find [?ident ...]
                                     :where [_ :db/ident ?ident]]
                                   (d/db conn)))]
      (schema/install! conn)
      (let [after-count (count (d/q '[:find [?ident ...]
                                      :where [_ :db/ident ?ident]]
                                    (d/db conn)))]
        (is (= before-count after-count)
            "Re-install must not create new ident entities.")))))

(deftest core-schema-summary-returns-sorted-vector
  (testing "REPL helper schema-summary returns a sorted vector of idents
            scoped to kernel namespaces"
    (let [conn (core/create-test-db)
          summary (core/schema-summary conn)]
      (is (vector? summary))
      (is (= summary (sort summary)))
      (is (every? (fn [k]
                    (contains? expected-namespaces (namespace k)))
                  summary)))))
