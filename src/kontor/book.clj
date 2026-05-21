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
   `kontor.process` step list); `entry!` routes it through the
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
     :commodity       — commodity ref               (required)
     :effective-date  — #inst, the bitemporal valid-time
                        (required for `entry-tx-data`; `entry!` and
                         the verbs default it to now)
     :journal         — journal ref / lookup-ref / external-id
                        (required for `entry-tx-data`; the verbs
                         resolve it from the verb's journal type)
     :narration       — string                      (optional)
     :partner         — partner ref                 (optional)
     :external-id     — string                      (optional)

   For a multi-leg / judgment entry, instead of debit/credit/amount
   pass `:postings` — a vector of `{:account … :amount … :commodity …}`
   maps (`:commodity` falls back to the top-level `:commodity`). The
   amounts must sum to zero per (ledger, commodity); positive is a
   debit. `adjust!` is the named verb for this.

   The builder opts `:posted-at` / `:vt-from` / `:vt-to` pass through
   to `post-transaction-tx-data`.

   ## Debits and credits

   The signature is `:debit-account` / `:credit-account` — kontor's
   facade audience is Clojure developers, not end users (ADR-010 —
   no UI), and a two-leg entry has exactly two slots. Each verb's
   docstring teaches the convention (`sell`: debit the receivable or
   cash, credit revenue). Per note 97 §2, `:posting/amount` is a
   signed BigDecimal — positive = debit — so the facade simply
   negates the amount on the credit leg; sum-to-zero (`Ker(σ)`)
   holds by construction."
  (:require [datahike.api :as d]
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

(defn- ->posting
  "Map a friendly `{:account :amount :commodity?}` posting (used in
   `:postings` for multi-leg entries) into the kernel `:posting/*`
   shape, defaulting commodity to the entry-level one."
  [default-commodity {:keys [account amount commodity] :as p}]
  (when (nil? account)
    (throw (ex-info "kontor.book: each :postings entry needs :account" {:posting p})))
  (when (nil? amount)
    (throw (ex-info "kontor.book: each :postings entry needs :amount" {:posting p})))
  (let [c (or commodity default-commodity)]
    (when (nil? c)
      (throw (ex-info "kontor.book: posting needs :commodity (or an entry-level :commodity)"
                      {:posting p})))
    {:posting/account   account
     :posting/amount    (->bigdec amount)
     :posting/commodity c}))

(defn- build-input
  "Translate a verb options map into the `{:transaction … :postings …}`
   shape `kontor.posting/post-transaction-tx-data` expects. Pure;
   throws `ex-info` on a missing required field."
  [{:keys [debit-account credit-account amount commodity journal
           effective-date narration partner external-id postings]}]
  (when (nil? journal)
    (throw (ex-info "kontor.book: :journal is required" {})))
  (when (nil? effective-date)
    (throw (ex-info "kontor.book: :effective-date is required" {})))
  (let [ps (cond
             (seq postings)
             (mapv #(->posting commodity %) postings)

             :else
             (let [amt (->bigdec amount)]
               (when (nil? debit-account)
                 (throw (ex-info "kontor.book: :debit-account is required" {})))
               (when (nil? credit-account)
                 (throw (ex-info "kontor.book: :credit-account is required" {})))
               (when (nil? amt)
                 (throw (ex-info "kontor.book: :amount is required" {})))
               (when (nil? commodity)
                 (throw (ex-info "kontor.book: :commodity is required" {})))
               [{:posting/account   debit-account
                 :posting/amount    amt
                 :posting/commodity commodity}
                {:posting/account   credit-account
                 :posting/amount    (- amt)
                 :posting/commodity commodity}]))]
    {:transaction (cond-> {:transaction/journal        journal
                           :transaction/effective-date effective-date}
                    narration   (assoc :transaction/narration narration)
                    partner     (assoc :transaction/partner partner)
                    external-id (assoc :transaction/external-id external-id))
     :postings    ps}))

(defn- post-opts
  "The subset of an options map that is forwarded to
   `post-transaction-tx-data` as builder opts."
  [opts]
  (select-keys opts [:posted-at :vt-from :vt-to]))

(defn- resolve-journal
  "Resolve a journal entity from a `:journal/type` keyword, for the
   `!`-side verb conveniences. Returns the eid when exactly one
   journal of that type exists; throws a clear error otherwise so the
   caller knows to pass `:journal` explicitly."
  [db journal-type]
  (let [js (d/q '[:find [?j ...]
                  :in $ ?t
                  :where [?j :journal/type ?t]]
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
   into a `kontor.process` step list.

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
   `entry!` directly."
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
;; The verbs — `!`-side conveniences over `entry!`
;; ============================================================================
;;
;; Each verb bakes in a `:journal/type`. The journal is resolved from
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
   (Stage 4's `kontor-commitment` will add commitment-aware
   settlement; for now this is the plain ledger move.) Journal type
   `:cash`."
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
