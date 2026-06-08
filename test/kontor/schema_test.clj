(ns kontor.schema-test
  "Phase 0 smoke tests.

   We verify that:
     1. The kernel schema loads into a fresh datahike DB without
        validation errors.
     2. Every documented kernel namespace shows up in the resulting
        schema (catches accidental deletions).
     3. The bitemporal :kontor.posting/temporal-key tuple is correctly
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
  #{"kontor.audit"
    "kontor.commodity" "kontor.lot"
    "kontor.account" "kontor.account-tag"
    "kontor.journal" "kontor.partner" "kontor.fiscal-position"
    "kontor.tax" "kontor.tax-rep" "kontor.vat-group"
    "kontor.period" "kontor.balance-assertion"
    "kontor.transaction" "kontor.posting"
    "kontor.analytic-plan" "kontor.analytic-account" "kontor.analytic-distribution"
    "kontor.ledger"
    "kontor.country" "kontor.country-code" "kontor.country-group"
    "kontor.state" "kontor.state-code"
    "kontor.attestation" "kontor.complemento"
    "kontor.valuation-book"
    "kontor.valuation-layer" "kontor.layer-consumption" "kontor.layer-adjustment"
    "kontor.entity" "kontor.person"
    "kontor.schedule" "kontor.schedule-occurrence"
    "kontor.status-transition" "kontor.status-history"
    "kontor.audit-doc" "kontor.approval-policy"
    "kontor.partner-merge" "kontor.bank-account"
    "kontor.partner-bank-account" "kontor.partner-tag"
    "kontor.partner-tax-id"
    "kontor.side-effect-intent" "kontor.account-type-direction"})

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

(deftest valid-time-lives-on-tx
  (testing "Per ADR-048, valid-time lives on the writing tx via
            upstream `:db.valid/from` / `:db.valid/to` (pre-installed
            by datahike's feature/bitemporal-v1). There is no per-
            posting valid-from. :kontor.posting/valid-to and the
            :kontor.posting/temporal-key tuple were dropped per research
            note 08 — corrections use reverse-and-repost, not
            valid-time supersession."
    (let [conn (core/create-test-db)
          db (d/db conn)
          present (set (d/q '[:find [?ident ...]
                              :where [_ :db/ident ?ident]]
                            db))]
      (is (not (contains? present :kontor.posting/valid-from))
          "per-posting valid-from is removed (ADR-048 normalization).")
      (is (not (contains? present :kontor.posting/temporal-key))
          "temporal-key tuple should NOT be in the schema (ADR-008 revised).")
      (is (not (contains? present :kontor.posting/valid-to))
          "valid-to should NOT be in the schema (ADR-008 revised).")
      (testing "valid-from anchor :db.valid/from is upstream-implicit and usable"
        ;; Datahike's :db.valid/from / :db.valid/to are pre-installed in the
        ;; non-ref-implicit-schema (datahike feature/bitemporal-v1) — they
        ;; don't materialize as :db/ident datoms unless something references
        ;; them, but are immediately usable in tx-meta. Verify by writing
        ;; one and reading it back.
        (let [tx-report (d/transact conn
                                    [{:db/id "datomic.tx"
                                      :db.valid/from #inst "2024-06-15"
                                      :db.valid/to   #inst "2024-09-15"}
                                     {:db/id "e1" :kontor.account/name "vt-probe"
                                      :kontor.account/path "VtProbe"
                                      :kontor.account/type :asset}])
              tx-eid (get-in tx-report [:tempids "datomic.tx"])
              db2    (d/db conn)
              pulled (d/pull db2 [:db.valid/from :db.valid/to] tx-eid)]
          (is (= #inst "2024-06-15" (:db.valid/from pulled))
              ":db.valid/from on the tx persists and is readable.")
          (is (= #inst "2024-09-15" (:db.valid/to pulled))
              ":db.valid/to on the tx persists and is readable."))))))

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
            that excludes datahike-internal and invariant scaffolding
            namespaces (inverted allowlist → denylist per P1-9)"
    (let [conn (core/create-test-db)
          summary (core/schema-summary conn)
          forbidden #{"db" "db.alter" "db.attr" "db.bootstrap"
                      "db.cardinality" "db.entity" "db.excise" "db.fn"
                      "db.install" "db.lang" "db.part" "db.sys"
                      "db.type" "db.unique" "fressian" "invariant"}]
      (is (vector? summary))
      (is (= summary (sort summary)))
      (is (every? (fn [k] (not (contains? forbidden (namespace k))))
                  summary)
          "schema-summary should not surface datahike internals.")
      ;; All previously-allow-listed kernel namespaces still appear.
      ;; This guards against an over-eager denylist regression.
      (is (every? (fn [ns]
                    (some #(= ns (namespace %)) summary))
                  expected-namespaces)
          "All documented kernel namespaces remain present."))))
