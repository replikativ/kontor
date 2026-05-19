(ns kontor.agent-tools
  "Server-agnostic tool catalog over kontor's read + write surface.
   Note 94 §3.2 — \"the leverage point is the tool catalog, not another
   server.\"

   ## Shape

   A tool spec is a plain map:

   ```clojure
   {:name             \"snake_case_name\"
    :description      \"What it does — visible to the agent picking a tool.\"
    :input-schema     {:type \"object\"
                       :properties {...}
                       :required [...]}
    :side-effects?    true | false   ; nil treated as false
    :handler          (fn [{:keys [conn db args]}] result-map)}
   ```

   - **Read tools** set `:side-effects? false` and read from `db`
     (defaults to `(d/db conn)`).
   - **Write tools** set `:side-effects? true` and route through
     `kontor.validation/transact-with-validation` — every kernel gate
     (sealing, period-lock, audit-doc category, status-machine,
     approval-policy, legal-hold, invariants) fires identically to a
     direct call.

   Schemas follow JSON Schema's object shape so any MCP server / OpenAI
   tool-use bridge / Anthropic tool-use bridge consumes them
   unchanged. Consumers that want a different transport (gRPC, GraphQL,
   etc.) translate on their side.

   ## Composition

   Register the catalog into your favorite MCP server's tool registry.
   For `../dvergr/src/dvergr/mcp/server.clj`:

   ```clojure
   (require '[dvergr.mcp.server :as dvergr-mcp]
            '[kontor.agent-tools :as kt])

   (swap! dvergr-mcp/tool-handlers merge
          (kt/dvergr-handlers (kt/default-catalog conn)))
   (dvergr-mcp/start! {:port 17888})
   ```

   ## Discipline

   - **No transport opinions.** This namespace ships pure data + a
     small invocation primitive. The kontor project deliberately does
     NOT ship a standalone MCP server (note 94 §3.2 — dvergr supplies
     one; reinventing the JSON-RPC plumbing has no value).
   - **Structured errors.** Handlers throw `ex-info` with `:type` keys
     so agent runtimes can pattern-match + repair. The default
     `invoke!` catches handler exceptions and returns
     `{:error <message> :ex-data <ex-data>}` rather than propagating.
   - **The kernel validation gate is the only enforcement layer.** This
     namespace does NOT add its own permission system — `:audit-doc/
     privilege`, sealing, approval-policy etc. fire identically to a
     bare `kontor.validation/transact-with-validation` call.
   - **Idempotent registry.** `register-tool!` overwrites by `:name`.
     A second registration with the same name replaces the prior
     spec — useful for hot-reload from the REPL.

   ## Per-tool conventions

   - All eid-style args (account, posting, partner, …) accept either
     an integer eid OR a lookup-ref tuple `[:account/code \"6020\"]` —
     the handlers normalize via `d/q`.
   - Dates are ISO-8601 strings in the JSON shape; handlers parse via
     `java.util.Date.from(java.time.Instant.parse ...)`.
   - Money amounts are BigDecimal-shaped strings (not floats — never
     floats; ADR-013). Handlers parse via `BigDecimal.`.

   ADR-094 + note 94 §3.2."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.balance :as balance]
            [kontor.dsar :as dsar]
            [kontor.explain :as explain]
            [kontor.posting :as posting]
            [kontor.trial :as trial]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal]
           [java.time Instant]
           [java.util Date]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce ^{:doc "Process-local atom of `name → tool-spec`. `defonce` so
                  ns reload preserves consumer registrations."}
  registry
  (atom {}))

(defn register-tool!
  "Idempotently register `tool-spec` keyed by its `:name`. A second
   registration with the same `:name` overwrites the prior spec —
   useful for hot-reload from the REPL."
  [tool-spec]
  (when-not (string? (:name tool-spec))
    (throw (ex-info "tool-spec missing :name string" {:spec tool-spec})))
  (when-not (fn? (:handler tool-spec))
    (throw (ex-info "tool-spec missing :handler fn" {:spec tool-spec})))
  (swap! registry assoc (:name tool-spec) tool-spec)
  (:name tool-spec))

(defn unregister-tool!
  "Remove a tool by name. No-op if not registered."
  [tool-name]
  (swap! registry dissoc tool-name)
  nil)

(defn tools
  "Snapshot of the registry as a vector of tool-specs (no handlers
   stripped). For MCP `tools/list`, route through `tool-summaries`."
  []
  (vec (vals @registry)))

(defn tool-summaries
  "JSON-friendly summaries — `{:name :description :input-schema}` per
   tool. Drops the `:handler` and any other internal keys. This is what
   an MCP `tools/list` returns."
  []
  (mapv (fn [{:keys [name description input-schema side-effects?]}]
          (cond-> {:name name :description description}
            input-schema       (assoc :inputSchema input-schema)
            (some? side-effects?)
            (assoc :sideEffects side-effects?)))
        (tools)))

;; ============================================================================
;; Invocation
;; ============================================================================

(defn- coerce-args [args]
  (cond
    (map? args) args
    (nil? args) {}
    :else (throw (ex-info "args must be a map" {:args args}))))

(defn invoke!
  "Invoke the named tool. Returns `{:result …}` on success or
   `{:error message :ex-data ex-data}` on handler exception.

   Args:
     :conn  — datahike conn. Required for write tools; reads default
              to `(d/db conn)` if no `:db` passed.
     :db    — optional explicit db snapshot. If absent + `:conn`
              given, the handler uses `(d/db conn)`.
     :args  — map of tool-specific arguments. Defaults to `{}`."
  [tool-name {:keys [conn db args]}]
  (let [spec (get @registry tool-name)]
    (when-not spec
      (throw (ex-info "Unknown tool" {:tool-name tool-name
                                      :available (vec (keys @registry))})))
    (try
      {:result ((:handler spec)
                {:conn conn
                 :db   (or db (when conn (d/db conn)))
                 :args (coerce-args args)})}
      (catch clojure.lang.ExceptionInfo e
        {:error    (.getMessage e)
         :ex-data  (assoc (ex-data e) :tool-name tool-name)})
      (catch Exception e
        {:error    (.getMessage e)
         :ex-data  {:tool-name tool-name :class (str (class e))}}))))

;; ============================================================================
;; Coercion helpers (eid normalization + money / date parsing)
;; ============================================================================

(defn- ->date [v]
  (cond
    (nil? v)            nil
    (instance? Date v)  v
    (string? v)         (Date/from (Instant/parse v))
    :else               (throw (ex-info "expected ISO-8601 date string"
                                        {:value v :type (type v)}))))

(defn- ->bigdec [v]
  (cond
    (nil? v)                 nil
    (instance? BigDecimal v) v
    (string? v)              (BigDecimal. ^String v)
    (number? v)              (BigDecimal/valueOf (long v))
    :else                    (throw (ex-info "expected BigDecimal-shaped string"
                                             {:value v :type (type v)}))))

(defn- resolve-eid
  "Normalize either an integer eid or a `[attr value]` lookup-ref pair
   into an eid against `db`. Returns nil on miss; throws on malformed
   input."
  [db v]
  (cond
    (nil? v)        nil
    (number? v)     v
    (sequential? v) (let [[attr value] v]
                      (d/q '[:find ?e .
                             :in $ ?a ?v
                             :where [?e ?a ?v]]
                           db attr value))
    :else           (throw (ex-info "expected eid or [attr value] lookup-ref"
                                    {:value v :type (type v)}))))

;; ============================================================================
;; Default catalog — load-bearing read + write surface
;; ============================================================================
;;
;; Curated initial set. Consumers extend via register-tool!. Per note
;; 94 §3.2 — \"the catalog is the surface; consumers pick the transport
;; they want.\"

(def ^:private kontor-explain-balance
  {:name "kontor_explain_balance"
   :description "Return an account's balance plus the ordered postings
                 that compose it. Bitemporal-aware (:as-of-valid +
                 :as-of-tx). Per ADR-091 — the substrate-tier
                 'explain this number' walk for an account."
   :input-schema {:type "object"
                  :properties
                  {:account     {:type "object"
                                 :description "eid OR [attr value] lookup-ref (e.g. [\"account/code\" \"6020\"])"}
                   :as-of-valid {:type "string"
                                 :description "ISO-8601 date. Default: now."}
                   :as-of-tx    {:type "string"
                                 :description "ISO-8601 date. Default: now."}
                   :entity      {:type "object"
                                 :description "Optional eid OR lookup-ref to restrict to one :posting/entity."}}
                  :required ["account"]}
   :side-effects? false
   :handler (fn [{:keys [conn db args]}]
              (let [acct (resolve-eid db (:account args))]
                (when-not acct
                  (throw (ex-info ":account did not resolve to an eid"
                                  {:type :agent-tools/account-not-found
                                   :spec (:account args)})))
                (explain/explain-balance
                 conn acct
                 (cond-> {}
                   (:as-of-valid args) (assoc :as-of-valid (->date (:as-of-valid args)))
                   (:as-of-tx args)    (assoc :as-of-tx    (->date (:as-of-tx args)))
                   (:entity args)      (assoc :entity      (resolve-eid db (:entity args)))))))})

(def ^:private kontor-account-balance
  {:name "kontor_account_balance"
   :description "Return an account's balance as `{commodity-eid Money}`.
                 Bitemporal-aware. ADR-008 / kontor.balance."
   :input-schema {:type "object"
                  :properties
                  {:account     {:type "object"}
                   :as-of-valid {:type "string"}
                   :as-of-tx    {:type "string"}}
                  :required ["account"]}
   :side-effects? false
   :handler (fn [{:keys [conn db args]}]
              (let [acct (resolve-eid db (:account args))]
                (balance/account-balance
                 conn acct
                 (cond-> {}
                   (:as-of-valid args) (assoc :as-of-valid (->date (:as-of-valid args)))
                   (:as-of-tx args)    (assoc :as-of-tx    (->date (:as-of-tx args)))))))})

(def ^:private kontor-trial-balance
  {:name "kontor_trial_balance"
   :description "Return the trial balance — every active account's
                 balance, broken down by commodity. Bitemporal-aware.
                 kontor.trial."
   :input-schema {:type "object"
                  :properties
                  {:as-of-valid {:type "string"}
                   :as-of-tx    {:type "string"}}}
   :side-effects? false
   :handler (fn [{:keys [conn args]}]
              (trial/trial-balance
               conn
               (cond-> {}
                 (:as-of-valid args) (assoc :as-of-valid (->date (:as-of-valid args)))
                 (:as-of-tx args)    (assoc :as-of-tx    (->date (:as-of-tx args))))))})

(def ^:private kontor-explain-posting
  {:name "kontor_explain_posting"
   :description "Walk a posting back through its lifecycle stack:
                 transaction → status-history → audit-docs → legal-holds
                 → retention → origin-transaction. ADR-091."
   :input-schema {:type "object"
                  :properties
                  {:posting   {:type "object"
                               :description "eid OR lookup-ref."}
                   :as-of-tx  {:type "string"}}
                  :required ["posting"]}
   :side-effects? false
   :handler (fn [{:keys [conn db args]}]
              (let [p (resolve-eid db (:posting args))]
                (explain/explain-posting
                 conn p
                 (cond-> {}
                   (:as-of-tx args) (assoc :as-of-tx (->date (:as-of-tx args)))))))})

(def ^:private kontor-entities-with-concept-iri
  {:name "kontor_entities_with_concept_iri"
   :description "Reverse-lookup: given an external-vocabulary IRI
                 (XBRL / FIBO / gist / internal taxonomy), return all
                 kontor entities binding to that concept. Walks the six
                 ADR-090 substrate seams (:account /:account-tag /
                 :partner / :commodity / :tax / :document-type)."
   :input-schema {:type "object"
                  :properties
                  {:iri {:type "string"
                         :description "The IRI to look up."}}
                  :required ["iri"]}
   :side-effects? false
   :handler (fn [{:keys [db args]}]
              (explain/entities-with-concept-iri db (:iri args)))})

(def ^:private kontor-dsar-collect
  {:name "kontor_dsar_collect"
   :description "ADR-052 DSAR bundle for a partner. Walks every
                 substrate attr touching the partner + every registered
                 extension collector (HR, payroll, etc.)."
   :input-schema {:type "object"
                  :properties
                  {:partner {:type "object"
                             :description "eid OR lookup-ref (e.g. [\"partner/external-id\" \"PARTNER-1\"])"}
                   :viewer-privilege {:type "array"
                                      :description "Set of privilege labels the requester holds."
                                      :items {:type "string"}}}
                  :required ["partner"]}
   :side-effects? false
   :handler (fn [{:keys [db args]}]
              (let [p (resolve-eid db (:partner args))
                    opts (cond-> {}
                           (:viewer-privilege args)
                           (assoc :viewer-privilege
                                  (mapv keyword (:viewer-privilege args))))]
                (dsar/collect db p opts)))})

(def ^:private kontor-create-audit-doc
  {:name "kontor_create_audit_doc"
   :description "Create an :audit-doc entity. Required: :code :type
                 :storage-uri. Optional: :title :description :content-hash
                 :uploaded-by-uid :category :language. Routes through
                 the kernel validation gate."
   :input-schema {:type "object"
                  :properties
                  {:code         {:type "string"}
                   :type         {:type "string" :description "Keyword name (e.g. \"credit-memo\")"}
                   :storage-uri  {:type "string"}
                   :title        {:type "string"}
                   :description  {:type "string"}
                   :content-hash {:type "string"}
                   :uploaded-by-uid {:type "object"}
                   :category     {:type "string"
                                  :description "Keyword name from kontor.audit-doc/canonical-categories or consumer extension."}
                   :language     {:type "string"}}
                  :required ["code" "type" "storage-uri"]}
   :side-effects? true
   :handler (fn [{:keys [conn args]}]
              (let [spec (cond-> {:code        (:code args)
                                  :type        (keyword (:type args))
                                  :storage-uri (:storage-uri args)}
                           (:title args)           (assoc :title (:title args))
                           (:description args)     (assoc :description (:description args))
                           (:content-hash args)    (assoc :content-hash (:content-hash args))
                           (:uploaded-by-uid args) (assoc :uploaded-by-uid (:uploaded-by-uid args))
                           (:category args)        (assoc :category (keyword (:category args)))
                           (:language args)        (assoc :language (keyword (:language args))))]
                (audit-doc/create-doc! conn spec)))})

(def ^:private kontor-post-transaction
  {:name "kontor_post_transaction"
   :description "Build + post a balanced :transaction with its
                 :postings in one tx. Routes through the kernel
                 validation gate — period-lock, sealing, sum-to-zero
                 are all enforced. Per ADR-068 *-tx-data convention,
                 the actual builder is kontor.posting/post-transaction-tx-data."
   :input-schema {:type "object"
                  :properties
                  {:transaction {:type "object"
                                 :description "Transaction header: :journal (ref), :external-id, :date, :narration."}
                   :postings    {:type "array"
                                 :description "Vector of {:account (ref) :amount (BigDecimal-shaped string) :commodity (ref) :narration (optional)}. Sum-to-zero per (ledger, commodity) is enforced."
                                 :items {:type "object"}}}
                  :required ["transaction" "postings"]}
   :side-effects? true
   :handler (fn [{:keys [conn db args]}]
              ;; Caller passes resolved-eid refs; we decorate with
              ;; BigDecimal coercion + Date parsing + lookup-ref
              ;; resolution before passing to the kernel builder.
              (let [tx-header (cond-> (:transaction args)
                               (:transaction/journal (:transaction args))
                               (update :transaction/journal #(resolve-eid db %))
                               (string? (:transaction/effective-date (:transaction args)))
                               (update :transaction/effective-date ->date))
                    postings (mapv
                              (fn [p]
                                (cond-> p
                                  (:posting/amount p)    (update :posting/amount ->bigdec)
                                  (:posting/account p)   (update :posting/account #(resolve-eid db %))
                                  (:posting/commodity p) (update :posting/commodity #(resolve-eid db %))
                                  (:posting/partner p)   (update :posting/partner #(resolve-eid db %))
                                  (:posting/entity p)    (update :posting/entity #(resolve-eid db %))
                                  (:posting/ledger p)    (update :posting/ledger #(resolve-eid db %))))
                              (:postings args))
                    tx-data (posting/post-transaction-tx-data
                             {:transaction tx-header
                              :postings postings})]
                (validation/transact-with-validation conn tx-data)))})

(def default-catalog-tools
  "Curated initial set. Consumers extend via register-tool!."
  [kontor-explain-balance
   kontor-account-balance
   kontor-trial-balance
   kontor-explain-posting
   kontor-entities-with-concept-iri
   kontor-dsar-collect
   kontor-create-audit-doc
   kontor-post-transaction])

(defn install-default-catalog!
  "Register the bundled set of tools into the process-local registry.
   Idempotent. Returns the names registered."
  []
  (mapv register-tool! default-catalog-tools))

(defn default-catalog
  "Return the bundled set as a vector of tool-specs WITHOUT mutating
   the registry. Useful when a consumer wants to filter / wrap /
   namespace before registering."
  [_conn]
  default-catalog-tools)

;; ============================================================================
;; dvergr MCP-server adapter
;; ============================================================================
;;
;; dvergr's MCP server (../dvergr/src/dvergr/mcp/server.clj) holds tool
;; handlers in a `tool-handlers` atom keyed by tool-name string. Each
;; handler is `(fn [context arguments] {:content [...] :isError bool})`.
;; This adapter wraps a kontor tool-spec into that shape — keeps the
;; kontor-side ergonomics clean while interoperating with the dvergr
;; transport.

(defn- ->mcp-content
  "Render a Clojure result map into MCP's `{:content [{:type :text :text}]}`
   shape — JSON-encoded for the text body."
  [result-or-error]
  (if-let [err (:error result-or-error)]
    {:content [{:type "text" :text (str err)}]
     :isError true}
    {:content [{:type "text"
                :text (pr-str (:result result-or-error))}]
     :isError false}))

(defn dvergr-handler
  "Wrap one kontor tool-spec into dvergr's `(fn [context arguments]
   {:content :isError})` handler shape. Consumer threads its
   `conn` in via partial application:

   ```clojure
   (swap! dvergr-mcp/tool-handlers assoc
          (:name kt/kontor-explain-balance)
          (kt/dvergr-handler conn kt/kontor-explain-balance))
   ```"
  [conn tool-spec]
  (fn [_context arguments]
    (->mcp-content
     (invoke! (:name tool-spec)
              {:conn conn
               :args (or arguments {})}))))

(defn dvergr-handlers
  "Wrap a catalog into a map suitable for `(swap! dvergr-mcp/tool-handlers
   merge ...)`. Note: this only works if you've already
   `register-tool!`'d each tool into the kontor registry; the
   dvergr-handler dispatches by name through the registry."
  [conn tool-specs]
  (into {}
        (for [spec tool-specs]
          [(:name spec) (dvergr-handler conn spec)])))
