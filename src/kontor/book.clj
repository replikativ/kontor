(ns kontor.book
  "Verb facade — the small, named on-ramp to kontor (Stage 1 of
   research note 99; ADR-095).

   kontor's business writes are ~200 `*-tx-data` builders + `!`
   wrappers (ADR-068). `kontor.book` is *organizing sugar* over the
   most common of them: a handful of well-named verbs — `receive`,
   `pay`, `sell`, `buy`, `receive-payment`, `pay-bill`, `transfer`,
   `adjust` — that each build a balanced, sealed transaction without
   the caller hand-assembling the `{:transaction … :postings …}` map.

   This is NOT a new layer. A verb is a thin wrapper over
   `kontor.posting/post-transaction!`. No new schema, no new entity,
   no stored `:event`. \"Events\" are the dispatch operations kontor
   already provides; these verbs just give the common ones a clean
   front door. See note 97 (the critical reading) for why this is a
   facade, not a framework, and note 99 for the staged plan.

   ## The one builder, ADR-068

   `entry-tx-data` is the single pure builder (composable into a
   `kontor.workflow.process` step list); `entry!` routes it through the
   validation gate. The named verbs (`sell!`, `pay!`, …) are
   `!`-side conveniences over `entry!` — they bake in the journal
   *type* and carry a teaching docstring; they do not add a separate
   builder, because there is only one business write here (post a
   balanced transaction).

   ## Signature

   One options map. For a normal two-leg entry:

     :debit-account   — account ref or lookup-ref   (required)
     :credit-account  — account ref or lookup-ref   (required)
     :amount          — number, coerced to BigDecimal (required)
     :commodity       — commodity ref               (required). Accepts:
                        bare keyword `:EUR`, short string `\"EUR\"`,
                        lookup-ref `[:kontor.commodity/symbol \"EUR\"]`, or
                        an eid. Bare keyword/string are auto-promoted
                        to the lookup-ref.
     :effective-date  — #inst, the bitemporal valid-time
                        (required for `entry-tx-data`; `entry!` and
                         the verbs default it to now)
     :journal         — journal ref / lookup-ref / external-id
                        (required for `entry-tx-data`; the verbs
                         resolve it from the verb's journal type)
     :narration       — string                      (optional)
     :partner         — partner ref                 (optional)
     :external-id     — string                      (optional)
     :entity          — entity ref (ADR-031)        (optional)
                        Stamped on every posting as :kontor.posting/entity;
                        required for per-entity trial-balance / BS /
                        GuV filters to scope correctly.

   For a multi-leg / judgment entry, instead of debit/credit/amount
   pass `:postings` — a vector of `{:account … :amount … :commodity …}`
   maps (`:commodity` falls back to the top-level `:commodity`). The
   amounts must sum to zero per (ledger, commodity); positive is a
   debit. `adjust!` is the named verb for this. A posting map may also
   carry:
   - `:entity`     overrides the entry-level one — intercompany pattern
   - `:partner`    overrides the entry-level one — multi-counterparty
                   pattern (e.g. dividend declaration to several
                   shareholders, where each leg carries its own
                   `:kontor.posting/partner`). Note 160 §I-15.
   - `:dimensions` `{axis value}` map of ADR-097 classification tags
                   (cost-centre, project, segment); the report engine
                   `marginalize`s over any such axis (ADR-096).

   The builder opts `:posted-at` / `:vt-from` / `:vt-to` pass through
   to `post-transaction-tx-data`.

   ## Debits and credits

   The signature is `:debit-account` / `:credit-account` — kontor's
   facade audience is Clojure developers, not end users (ADR-010 —
   no UI), and a two-leg entry has exactly two slots. Each verb's
   docstring teaches the convention (`sell`: debit the receivable or
   cash, credit revenue). Per note 97 §2, `:kontor.posting/amount` is a
   signed BigDecimal — positive = debit — so the facade simply
   negates the amount on the credit leg; sum-to-zero (`Ker(σ)`)
   holds by construction."
  (:require [datahike.api :as d]
            [kontor.gate :as gate]
            [kontor.posting :as posting]))

;; ============================================================================
;; Internals
;; ============================================================================

(defn- ->bigdec
  [x]
  (cond
    (instance? BigDecimal x) x
    (nil? x)                 nil
    :else                    (bigdec x)))

(defn- ->commodity-ref
  "Coerce a consumer-friendly `:commodity` value to a datahike
   reference. Note 160 §I-2: bare keyword `:EUR` or short string `\"EUR\"`
   are the natural ways to write a commodity in a verb call; both get
   auto-promoted to the canonical `[:kontor.commodity/symbol \"EUR\"]`
   lookup-ref. Pre-existing eid (Long) or explicit lookup-ref (vector)
   passes through unchanged."
  [c]
  (cond
    (nil? c)              nil
    (vector? c)           c                       ; already a lookup-ref
    (number? c)           c                       ; already an eid
    (keyword? c)          [:kontor.commodity/symbol (name c)]
    (string? c)           [:kontor.commodity/symbol c]
    :else                 c))

(defn- ->dimension-value
  "Coerce a friendly dimension value to the schema's string."
  [v]
  (cond
    (string? v)  v
    (keyword? v) (name v)
    :else        (str v)))

(defn- ->dimensions
  "Map a friendly `{axis value-or-coll}` dimensions map into a vector
   of `:posting-dimension` entity maps (ADR-097). A collection value
   expands to one dimension per element."
  [dimensions]
  (vec (for [[axis v] dimensions
             one      (if (coll? v) v [v])]
         {:kontor.posting-dimension/axis  axis
          :kontor.posting-dimension/value (->dimension-value one)})))

(defn- ->posting
  "Map a friendly `{:account :amount :commodity? :entity? :partner?
   :dimensions?}` posting (used in `:postings` for multi-leg entries)
   into the kernel `:kontor.posting/*` shape, defaulting commodity + entity +
   partner to the entry-level ones. Per-posting overrides:
   - `:entity`  — ADR-031 intercompany pattern (per-entity sum-to-zero)
   - `:partner` — per-leg counterparty (e.g. multi-shareholder dividend
                  declaration; note 160 §I-15)."
  [default-commodity default-entity default-partner
   {:keys [account amount commodity entity partner dimensions] :as p}]
  (when (nil? account)
    (throw (ex-info "kontor.book: each :postings entry needs :account" {:posting p})))
  (when (nil? amount)
    (throw (ex-info "kontor.book: each :postings entry needs :amount" {:posting p})))
  (let [c  (->commodity-ref (or commodity default-commodity))
        e  (or entity    default-entity)
        pa (or partner   default-partner)]
    (when (nil? c)
      (throw (ex-info "kontor.book: posting needs :commodity (or an entry-level :commodity)"
                      {:posting p})))
    (cond-> {:kontor.posting/account   account
             :kontor.posting/amount    (->bigdec amount)
             :kontor.posting/commodity c}
      e                (assoc :kontor.posting/entity e)
      pa               (assoc :kontor.posting/partner pa)
      (seq dimensions) (assoc :kontor.posting/dimensions (->dimensions dimensions)))))

(defn- build-input
  "Translate a verb options map into the `{:transaction … :postings …}`
   shape `kontor.posting/post-transaction-tx-data` expects. Pure;
   throws `ex-info` on a missing required field.

   `:entity` (optional, ADR-031) is stamped on every posting via
   `:kontor.posting/entity` — required for per-entity trial-balance / BS / GuV
   filters to scope correctly. Per-posting `:entity` overrides the
   entry-level one (intercompany)."
  [{:keys [debit-account credit-account amount commodity journal
           effective-date narration partner external-id entity postings]}]
  (when (nil? journal)
    (throw (ex-info "kontor.book: :journal is required" {})))
  (when (nil? effective-date)
    (throw (ex-info "kontor.book: :effective-date is required" {})))
  (let [ps (cond
             (seq postings)
             (mapv #(->posting commodity entity partner %) postings)

             :else
             (let [amt (->bigdec amount)
                   c   (->commodity-ref commodity)]
               (when (nil? debit-account)
                 (throw (ex-info "kontor.book: :debit-account is required" {})))
               (when (nil? credit-account)
                 (throw (ex-info "kontor.book: :credit-account is required" {})))
               (when (nil? amt)
                 (throw (ex-info "kontor.book: :amount is required" {})))
               (when (nil? c)
                 (throw (ex-info "kontor.book: :commodity is required" {})))
               [(cond-> {:kontor.posting/account   debit-account
                         :kontor.posting/amount    amt
                         :kontor.posting/commodity c}
                  entity (assoc :kontor.posting/entity entity))
                (cond-> {:kontor.posting/account   credit-account
                         :kontor.posting/amount    (- amt)
                         :kontor.posting/commodity c}
                  entity (assoc :kontor.posting/entity entity))]))]
    {:transaction (cond-> {:kontor.transaction/journal        journal
                           :kontor.transaction/effective-date effective-date}
                    narration   (assoc :kontor.transaction/narration narration)
                    partner     (assoc :kontor.transaction/partner partner)
                    external-id (assoc :kontor.transaction/external-id external-id))
     :postings    ps}))

(defn- post-opts
  "The subset of an options map that is forwarded to
   `post-transaction-tx-data` as builder opts."
  [opts]
  (select-keys opts [:posted-at :vt-from :vt-to]))

(defn- resolve-journal
  "Resolve a journal entity from a `:kontor.journal/type` keyword, for the
   `!`-side verb conveniences. Returns the eid when exactly one
   journal of that type exists; throws a clear error otherwise so the
   caller knows to pass `:journal` explicitly."
  [db journal-type]
  (let [js (d/q '[:find [?j ...]
                  :in $ ?t
                  :where [?j :kontor.journal/type ?t]]
                db journal-type)]
    (cond
      (= 1 (count js)) (first js)
      (empty? js)
      (throw (ex-info (str "kontor.book: no :journal of type " journal-type
                           " in the db — create one, or pass :journal explicitly")
                      {:journal-type journal-type}))
      :else
      (throw (ex-info (str "kontor.book: " (count js) " journals of type " journal-type
                           " — ambiguous; pass :journal explicitly")
                      {:journal-type journal-type :found js})))))

;; ============================================================================
;; The one builder (ADR-068)
;; ============================================================================

(defn entry-tx-data
  "Pure tx-data builder for a balanced, sealed transaction — the
   single ADR-068 builder behind every `kontor.book` verb. Composable
   into a `kontor.workflow.process` step list.

   Requires `:journal` and `:effective-date` explicitly (it is pure).
   Use `entry!` / the named verbs for the ergonomic path (journal
   resolved by type, `:effective-date` defaulted to now)."
  [opts]
  (posting/post-transaction-tx-data (build-input opts) (post-opts opts)))

(defn entry!
  "Build + seal a balanced transaction, routed through the validation
   gate (ADR-068). The ergonomic core: if `:journal` is absent but
   `:journal-type` is present, the journal is resolved from the db;
   `:effective-date` defaults to now.

   Most callers use a named verb (`sell!`, `pay!`, …) rather than
   `entry!` directly.

   ## Return-value contract

   Single-tx verbs (`entry!`, every named verb, `record-status-change!`,
   etc.) return the underlying datahike tx-report — a map carrying
   `:db-before`, `:db-after`, `:tx-data`, `:tempids`. Consumers commonly
   read `(get-in result [:tempids \"datomic.tx\"])` to find the new
   transaction's eid.

   Multi-tx orchestrations (`consolidate!`, `vat-return/file!`, and
   anything routed through `kontor.workflow.process/run-process`) return a
   process-result map per `kontor.workflow.process` — different shape, carries
   per-step tx-reports under `:reports`. Callers needing the final
   `:db-after` should pull it from the last entry of `:reports`."
  ([conn opts] (entry! conn opts {}))
  ([conn opts extra-post-opts]
   (let [opts' (cond-> opts
                 (and (nil? (:journal opts)) (:journal-type opts))
                 (assoc :journal (resolve-journal (d/db conn) (:journal-type opts)))

                 (nil? (:effective-date opts))
                 (assoc :effective-date (java.util.Date.)))]
     (posting/post-transaction! conn
                                (build-input opts')
                                (merge (post-opts opts') extra-post-opts)))))

;; ============================================================================
;; Non-committing validation — the "web-form check" (research note 190)
;; ============================================================================

(defn- structural->diagnostics
  "Map `kontor.posting.validate/validate`'s `:errors` into the uniform
   diagnostic shape `kontor.gate/validate-candidate` returns, so tier-1
   (pure balance) and tier-2 (db invariants) surface as one list."
  [report]
  (mapv (fn [e]
          {:severity :error
           :code     (:error e)
           :message  (:message e)
           :data     (dissoc e :error :message)})
        (:errors report)))

(defn validate-entry
  "Non-committing dry-run of a `kontor.book` verb entry — the same
   check `entry!` runs at commit, but returning structured diagnostics
   instead of throwing, and never persisting. Two tiers, one predicate
   set (research note 190; the Odoo onchange↔constrains discipline):

   - **tier-1** — pure structure + sum-to-zero balance via
     `kontor.posting.validate/validate` (no db). This half is `.cljc`;
     the browser runs it standalone on every edit for instant feedback.
   - **tier-2** — db invariants + sealing / legal-hold / period-lock /
     state-machine via `kontor.gate/validate-candidate` (needs the db).
     Skipped when tier-1 already failed (the tx-data won't build).

   Resolves `:journal`/`:effective-date` exactly like `entry!`, so the
   candidate mirrors what a commit would attempt. Returns
   `{:ok? boolean :diagnostics [{:severity :code :message :data} …]}`.

   Intended server-side over distributed-scope: the client shows tier-1
   instantly and calls this for tier-2, but only `entry!` (through the
   gate) ever writes — an optimistic UI can never persist a posting the
   gate would reject."
  [conn opts]
  (let [opts' (cond-> opts
                (and (nil? (:journal opts)) (:journal-type opts))
                (assoc :journal (resolve-journal (d/db conn) (:journal-type opts)))

                (nil? (:effective-date opts))
                (assoc :effective-date (java.util.Date.)))
        built (try {:input (build-input opts')}
                   (catch clojure.lang.ExceptionInfo e
                     {:diag {:severity :error
                             :code     :book/malformed-entry
                             :message  (ex-message e)
                             :data     (ex-data e)}}))]
    (if-let [d (:diag built)]
      {:ok? false :diagnostics [d]}
      (let [t1 (structural->diagnostics (posting/validate (:input built)))
            t2 (if (seq t1)
                 []                       ; tx-data won't build while structure is broken
                 (:diagnostics (gate/validate-candidate conn (entry-tx-data opts'))))
            diags (vec (concat t1 t2))]
        {:ok?         (empty? diags)
         :diagnostics diags}))))

;; ============================================================================
;; The verbs — `!`-side conveniences over `entry!`
;; ============================================================================
;;
;; Each verb bakes in a `:kontor.journal/type`. The journal is resolved from
;; the db when `:journal` is not passed explicitly. The signature is
;; uniform (see the ns docstring); only the journal type, the name,
;; and the teaching docstring differ.

(defn receive!
  "Book value flowing IN — a cash sale, interest received, an
   owner contribution. Debit the account the value landed in (an
   asset — cash/bank); credit its source (an income account, or a
   liability/equity account). Journal type `:cash`."
  ([conn opts] (receive! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash) extra)))

(defn pay!
  "Book value flowing OUT in cash — an expense paid, a liability
   settled with cash. Debit what the payment was for (an expense
   account, or the liability being settled); credit the cash/bank
   account. Journal type `:cash`."
  ([conn opts] (pay! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash) extra)))

(defn sell!
  "Book a sale on account (accrual revenue). Debit the receivable
   (or cash, for an immediate-payment sale); credit the revenue
   account. For a sale with tax, pass `:postings` with the explicit
   tax leg until Stage 2's tax provider lands. Journal type `:sale`."
  ([conn opts] (sell! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :sale) extra)))

(defn buy!
  "Book a purchase on account (accrual expense or asset). Debit the
   expense or asset account; credit the payable (or cash, for an
   immediate-payment purchase). Journal type `:purchase`."
  ([conn opts] (buy! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :purchase) extra)))

(defn receive-payment!
  "Book a customer payment settling a receivable. Debit the
   cash/bank account; credit the receivable account being settled.
   Journal type `:cash`."
  ([conn opts] (receive-payment! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash) extra)))

(defn pay-bill!
  "Book a payment settling a payable. Debit the payable account
   being settled; credit the cash/bank account. Journal type
   `:cash`."
  ([conn opts] (pay-bill! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash) extra)))

(defn transfer!
  "Book a move of value between two of your own accounts (e.g. bank
   to petty cash). Debit the destination account; credit the source
   account. Journal type `:general`."
  ([conn opts] (transfer! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :general) extra)))

(defn adjust!
  "Book a free-form, multi-leg entry — a correction, a reclassi-
   fication, a revaluation, an accrual: the judgment entries the
   verb set deliberately does not mechanize (note 97 §8, the
   synthetic residue). Pass `:postings` — a vector of
   `{:account … :amount … :commodity?}` maps; positive amounts are
   debits; they must sum to zero per (ledger, commodity). Journal
   type `:general`."
  ([conn opts] (adjust! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :general) extra)))

;; ============================================================================
;; Equity-distribution verbs — note 107 §2.6 (the corporation→shareholder loop)
;; ============================================================================

(defn declare-dividend!
  "CORPORATION side — book a dividend declaration. Debit Retained
   Earnings; credit Dividends Payable (a liability). Journal type
   `:general` — this is the accrual; the cash payment is a separate
   `distribute-dividend!`.

   Conventional accounts (the consumer wires the chart):
     :debit-account   Equity:Retained-Earnings
     :credit-account  Liabilities:Dividends-Payable

   The shareholder is `:partner` (a `:partner` ref) — the GL stamps
   `:kontor.transaction/partner` so the dividend liability is shareholder-
   traceable.

   Note 107 §2.6."
  ([conn opts] (declare-dividend! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :general) extra)))

(defn distribute-dividend!
  "CORPORATION side — pay an already-declared dividend. Debit
   Dividends Payable (settles the liability); credit Cash/Bank.
   Journal type `:cash`.

   Conventional accounts:
     :debit-account   Liabilities:Dividends-Payable
     :credit-account  Assets:Bank

   The shareholder records the receipt separately on their books
   via `receive!` (Dr Bank, Cr Income:Dividends) — the investment-
   income regime in `kontor-l10n-<cc>` then taxes it (DE
   Abgeltungsteuer, US qualified-dividend, FR PFU, JP 20.315 %, …).

   Note 107 §2.6."
  ([conn opts] (distribute-dividend! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash) extra)))
