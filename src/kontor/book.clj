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
            [kontor.book.build :as build]
            [kontor.gate :as gate]
            [kontor.posting :as posting]))

;; ============================================================================
;; Internals
;; ============================================================================

;; The pure builder half — value coercion + build-input + entry-tx-data —
;; moved to kontor.book.build (.cljc) so the browser materializes verb
;; tx-data client-side (rung 1, note 192). build-input + post-opts are
;; re-exported (private) for the conn-bound verbs below.

(def ^:private build-input build/build-input)
(def ^:private post-opts build/post-opts)

(defn- resolve-journal
  "Resolve a journal entity from a `:kontor.journal/type` keyword, for the
   `!`-side verb conveniences. Returns the eid when exactly one journal of
   that type exists.

   The `:cash` type is routinely NOT unique: every kontor preset seeds a
   Cash Receipts journal (code \"CR\") and a Cash Disbursements journal
   (code \"CD\"), both `:kontor.journal/type :cash` — the textbook split of
   cash inflows from outflows. So a cash verb also passes the DIRECTION it
   encodes as `prefer-code` (\"CR\" for inflows: receive/receive-payment;
   \"CD\" for outflows: pay/pay-bill/distribute-dividend), and when several
   journals share the type we narrow by `:kontor.journal/code` to that one.
   A consumer whose preset codes its cash journals differently still gets
   the informative ambiguity error telling them to pass `:journal`
   explicitly. (note 197 — cash-journal-ambiguous P1.)"
  ([db journal-type] (resolve-journal db journal-type nil))
  ([db journal-type prefer-code]
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
       (let [narrowed (when prefer-code
                        (d/q '[:find [?j ...]
                               :in $ ?t ?c
                               :where
                               [?j :kontor.journal/type ?t]
                               [?j :kontor.journal/code ?c]]
                             db journal-type prefer-code))]
         (if (= 1 (count narrowed))
           (first narrowed)
           (throw (ex-info (str "kontor.book: " (count js) " journals of type " journal-type
                                (when prefer-code
                                  (str " and " (count narrowed) " coded \"" prefer-code "\""))
                                " — ambiguous; pass :journal explicitly")
                           {:journal-type journal-type :found js :prefer-code prefer-code}))))))))

;; ============================================================================
;; The one builder (ADR-068)
;; ============================================================================

(def entry-tx-data
  "Pure tx-data builder for a balanced, sealed transaction — re-exported
   from kontor.book.build (.cljc). The single ADR-068 builder behind every
   kontor.book verb; composable into a kontor.workflow.process step list.
   Requires :journal + :effective-date explicitly (it is pure). Use entry! /
   the named verbs for the ergonomic path (journal resolved by type,
   effective-date defaulted to now)."
  build/entry-tx-data)

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
   (let [opts' (cond-> (dissoc opts :journal-code-hint)
                 (and (nil? (:journal opts)) (:journal-type opts))
                 (assoc :journal (resolve-journal (d/db conn) (:journal-type opts)
                                                  (:journal-code-hint opts)))

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
  (let [opts' (cond-> (dissoc opts :journal-code-hint)
                (and (nil? (:journal opts)) (:journal-type opts))
                (assoc :journal (resolve-journal (d/db conn) (:journal-type opts)
                                                 (:journal-code-hint opts)))

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
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CR") extra)))

(defn pay!
  "Book value flowing OUT in cash — an expense paid, a liability
   settled with cash. Debit what the payment was for (an expense
   account, or the liability being settled); credit the cash/bank
   account. Journal type `:cash`."
  ([conn opts] (pay! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CD") extra)))

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
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CR") extra)))

(defn pay-bill!
  "Book a payment settling a payable. Debit the payable account
   being settled; credit the cash/bank account. Journal type
   `:cash`."
  ([conn opts] (pay-bill! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CD") extra)))

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
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CD") extra)))
