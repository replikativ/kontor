(ns kontor.agent-tools-test
  "Tests for `kontor.agent-tools` — the server-agnostic tool catalog.

   The tests exercise the catalog at registry + invocation level
   directly (no MCP transport involved). The dvergr adapter is exercised
   at unit-level too (no live dvergr server needed)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.agent-tools :as kt]
            [kontor.core :as core]
            [kontor.posting :as posting]))

;; ============================================================================
;; Fixture — a small DB with one account + one journal + two postings
;; ============================================================================

(defn- seed-db []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/precision 2}
                 {:account/code "1000" :account/name "Bank"
                  :account/type :asset :account/active true
                  :account/concept-iri "urn:test:bank"}
                 {:account/code "5000" :account/name "Office"
                  :account/type :expense :account/active true}
                 {:journal/code "GEN" :journal/name "General"
                  :journal/type :general}
                 {:period/name "2026-01"
                  :period/start #inst "2026-01-01"
                  :period/end #inst "2026-02-01"}])
    conn))

;; ============================================================================
;; Registry tests
;; ============================================================================

(deftest registry-register-and-summarize
  ;; Use a setup/teardown of the registry to avoid polluting the
  ;; defonce across tests.
  (let [original @kt/registry]
    (try
      (reset! kt/registry {})
      (kt/register-tool!
       {:name "my_test_tool"
        :description "noop"
        :handler (fn [_ctx] {:result :ok})})
      (testing "registered tool appears in tools + summaries"
        (is (= ["my_test_tool"]
               (mapv :name (kt/tools))))
        (is (= [{:name "my_test_tool" :description "noop"}]
               (kt/tool-summaries))))
      (testing "second register-tool with the same name overwrites"
        (kt/register-tool!
         {:name "my_test_tool"
          :description "noop v2"
          :handler (fn [_] {:result :ok2})})
        (is (= "noop v2" (-> (kt/tools) first :description))))
      (testing "unregister-tool! removes the entry"
        (kt/unregister-tool! "my_test_tool")
        (is (= [] (kt/tools))))
      (testing "register-tool! requires :name + :handler"
        (is (thrown? clojure.lang.ExceptionInfo
                     (kt/register-tool! {:handler (fn [_])})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (kt/register-tool! {:name "x"}))))
      (finally
        (reset! kt/registry original)))))

(deftest default-catalog-tools-count
  ;; Doesn't touch the registry — just inspects the bundled set.
  (is (>= (count (kt/default-catalog nil)) 8))
  (is (every? :name (kt/default-catalog nil)))
  (is (every? :handler (kt/default-catalog nil)))
  (is (every? :description (kt/default-catalog nil)))
  (testing "the curated set names follow kontor_ prefix"
    (is (every? #(.startsWith ^String % "kontor_")
                (mapv :name (kt/default-catalog nil))))))

;; ============================================================================
;; Invocation tests — read tools
;; ============================================================================

(deftest invoke-handles-unknown-tool
  (let [original @kt/registry]
    (try
      (reset! kt/registry {})
      (is (thrown? clojure.lang.ExceptionInfo
                   (kt/invoke! "nope" {:conn nil :args {}})))
      (finally (reset! kt/registry original)))))

(deftest entities-with-concept-iri-via-catalog
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (let [result (kt/invoke! "kontor_entities_with_concept_iri"
                               {:conn conn
                                :args {:iri "urn:test:bank"}})]
        (testing "result is the result-shape, not error-shape"
          (is (some? (:result result)))
          (is (nil? (:error result))))
        (testing "the concept-iri reverse-lookup found the bank account"
          (let [account-eid (d/q '[:find ?e .
                                   :in $ ?c
                                   :where [?e :account/code ?c]]
                                 (d/db conn) "1000")]
            (is (some #{account-eid}
                      (:account (:result result)))))))
      (finally (reset! kt/registry original)))))

(deftest trial-balance-via-catalog-with-empty-db
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (let [result (kt/invoke! "kontor_trial_balance"
                               {:conn conn :args {}})]
        (testing "no postings yet — trial balance result is a map"
          (is (some? (:result result)))
          (is (nil? (:error result)))))
      (finally (reset! kt/registry original)))))

(deftest explain-balance-lookup-ref-resolution
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (testing "lookup-ref resolves the account before passing to the kernel"
        (let [result (kt/invoke! "kontor_explain_balance"
                                 {:conn conn
                                  :args {:account [:account/code "1000"]}})]
          (is (some? (:result result)))
          (is (nil? (:error result)))
          (is (every? #(contains? (:result result) %)
                      #{:account :balance :postings :as-of-valid :as-of-tx}))))
      (testing "non-existent account surfaces a structured error"
        (let [result (kt/invoke! "kontor_explain_balance"
                                 {:conn conn
                                  :args {:account [:account/code "DOES-NOT-EXIST"]}})]
          (is (some? (:error result)))
          (is (= :agent-tools/account-not-found
                 (-> result :ex-data :type)))))
      (finally (reset! kt/registry original)))))

;; ============================================================================
;; Invocation tests — write tools (the validation gate fires)
;; ============================================================================

(deftest post-transaction-via-catalog-routes-through-validation-gate
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (testing "balanced posting writes through the gate"
        (let [result
              (kt/invoke! "kontor_post_transaction"
                          {:conn conn
                           :args {:transaction
                                  {:transaction/external-id "TX-AGENT-1"
                                   :transaction/journal [:journal/code "GEN"]
                                   :transaction/effective-date "2026-01-15T00:00:00Z"
                                   :transaction/narration "agent-driven write"}
                                  :postings
                                  [{:posting/account [:account/code "5000"]
                                    :posting/amount "100.00"
                                    :posting/commodity [:commodity/symbol "EUR"]
                                    :posting/narration "Office supplies"}
                                   {:posting/account [:account/code "1000"]
                                    :posting/amount "-100.00"
                                    :posting/commodity [:commodity/symbol "EUR"]
                                    :posting/narration "Paid from bank"}]}})]
          (is (nil? (:error result)) (str result))
          (is (some? (:result result)))
          (let [db (d/db conn)
                tx-eid (d/q '[:find ?t .
                              :in $ ?ext
                              :where [?t :transaction/external-id ?ext]]
                            db "TX-AGENT-1")]
            (is (some? tx-eid)
                "the transaction landed in the DB"))))

      (testing "unbalanced posting fails-fast through the gate"
        (let [result
              (kt/invoke! "kontor_post_transaction"
                          {:conn conn
                           :args {:transaction
                                  {:transaction/external-id "TX-AGENT-2"
                                   :transaction/journal [:journal/code "GEN"]
                                   :transaction/effective-date "2026-01-15T00:00:00Z"}
                                  :postings
                                  [{:posting/account [:account/code "5000"]
                                    :posting/amount "100.00"
                                    :posting/commodity [:commodity/symbol "EUR"]}
                                   {:posting/account [:account/code "1000"]
                                    :posting/amount "-99.00"
                                    :posting/commodity [:commodity/symbol "EUR"]}]}})]
          (is (some? (:error result))
              "unbalanced postings are rejected before any datom is written")
          (is (string? (:error result)))))
      (finally (reset! kt/registry original)))))

(deftest create-audit-doc-with-canonical-category
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (let [result
            (kt/invoke! "kontor_create_audit_doc"
                        {:conn conn
                         :args {:code "DOC-AGENT-1"
                                :type "agent-narrative"
                                :storage-uri "memory://transient"
                                :category "hr-grievance"
                                :language "de"}})]
        (is (nil? (:error result)) (str result))
        (let [eid (d/q '[:find ?e .
                         :in $ ?c
                         :where [?e :audit-doc/code ?c]]
                       (d/db conn) "DOC-AGENT-1")
              row (d/pull (d/db conn) '[*] eid)]
          (is (some? eid))
          (is (= :hr-grievance (:audit-doc/category row)))
          (is (= :de (:audit-doc/language row)))))
      (finally (reset! kt/registry original)))))

;; ============================================================================
;; dvergr adapter — wrap into the expected handler shape
;; ============================================================================

(deftest dvergr-handler-wraps-result-into-mcp-content-shape
  (let [original @kt/registry
        conn (seed-db)]
    (try
      (reset! kt/registry {})
      (run! kt/register-tool! (kt/default-catalog conn))
      (let [handler (kt/dvergr-handler
                     conn
                     (first (filter #(= "kontor_entities_with_concept_iri" (:name %))
                                    (kt/default-catalog conn))))
            response (handler nil {:iri "urn:test:bank"})]
        (testing "shape is MCP-conformant"
          (is (false? (:isError response)))
          (is (vector? (:content response)))
          (is (= "text" (-> response :content first :type)))
          (is (string? (-> response :content first :text))))
        (testing "the text body is a Clojure-pr-str of the result"
          (is (.startsWith ^String (-> response :content first :text) "{"))))
      (testing "dvergr-handlers returns a map keyed by tool-name"
        (let [m (kt/dvergr-handlers conn (take 2 (kt/default-catalog conn)))]
          (is (= 2 (count m)))
          (is (every? string? (keys m)))
          (is (every? fn? (vals m)))))
      (finally (reset! kt/registry original)))))
