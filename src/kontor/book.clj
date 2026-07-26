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

     :debit-account   — account ref                 (required). Accepts:
                        bare string \"Assets:AR\" (means
                        `:kontor.account/path` — the account's UNIQUE
                        identity attribute; NOT `:kontor.account/code`,
                        which is not unique per ADR-119), an explicit
                        lookup-ref `[:kontor.account/path \"Assets:AR\"]`,
                        or an eid. A bare string is promoted to the
                        lookup-ref and resolved STRICTLY: naming an
                        account that does not exist throws
                        `:kontor.book/unresolved-ref` (ADR-124) — it is
                        never read as a tempid.
     :credit-account  — account ref, same forms      (required)
     :amount          — number, coerced to BigDecimal (required)
     :commodity       — commodity ref               (required). Accepts:
                        bare keyword `:EUR`, short string `\"EUR\"`,
                        lookup-ref `[:kontor.commodity/symbol \"EUR\"]`, or
                        an eid. Bare keyword/string are auto-promoted
                        to the lookup-ref.
     :effective-date  — #inst, the bitemporal valid-time
                        (required for `entry-tx-data`; `entry!` and
                         the verbs default it to now)
     :journal         — journal ref                 (required for
                        `entry-tx-data`; the verbs resolve it from the
                        verb's journal type). Accepts a bare string
                        \"SALE\" (means `:kontor.journal/code`, which IS
                        `:db.unique/identity`), a lookup-ref, or an eid;
                        same strict-resolution rule as the accounts.
     :narration       — string                      (optional)
     :partner         — partner ref                 (optional)
     :external-id     — string                      (optional)
     :settles         — coll of transaction refs    (optional)
                        `:kontor.transaction/settles` — the invoice(s) this
                        entry clears. REQUIRED for the AR/AP open-item
                        subledger to agree with the GL control account:
                        without the link the GL receivable goes to zero
                        while the subledger still reports the invoice
                        fully open, and
                        `kontor.banking.reconciliation/ar-tie-out`
                        reports the whole invoice as drift (ADR-124).
     :entity          — entity ref (ADR-031)        (optional)
                        Stamped on every posting as :kontor.posting/entity;
                        required for per-entity trial-balance / BS /
                        GuV filters to scope correctly.
     :actor           — WHO is booking this (ADR-150)  (optional by
                        default; MANDATORY once the consumer calls
                        `kontor.actor/require-actor-on-posted!`).
                        Accepts an eid, a lookup-ref, or the bare
                        `:kontor.actor/uid` string — `\"sarah\"` resolves
                        the registered actor and is REFUSED if there is
                        none. Lands on :kontor.transaction/posted-by
                        (GoBD's *Bearbeiter*) + :kontor.audit/create-uid
                        (what ADR-038's :no-self-approval compares a later
                        approver against) + :kontor.audit/write-uid.

   Option keys are STRICT: an unrecognised key throws
   `:kontor.book/unknown-option` rather than being silently dropped
   (ADR-124 — same discipline as
   `kontor.reporting.report/check-options!` on the read side; see
   `kontor.book.build/entry-option-keys` for the accepted set).

   Option keys are STRICT: an unrecognised key throws
   `:kontor.book/unknown-option` rather than being silently dropped
   (ADR-124 — same discipline as
   `kontor.reporting.report/check-options!` on the read side; see
   `kontor.book.build/entry-option-keys` for the accepted set).

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
            [kontor.money :as money]
            [kontor.numbering :as numbering]
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

;; ----------------------------------------------------------------------------
;; Ref resolution — ADR-124
;; ----------------------------------------------------------------------------
;;
;; `kontor.book.build` coerces a bare string into the right lookup-ref
;; (`\"Assets:AR\"` → `[:kontor.account/path \"Assets:AR\"]`), which is what
;; stops datahike from reading it as a tempid and minting an empty phantom
;; entity. But a lookup-ref that names a nonexistent account is still a
;; consumer error, and datahike's own message for it (`Nothing found for
;; entity id …`) surfaces from deep inside the transactor with no hint of
;; which verb slot was wrong. Since `kontor.book` is the documented
;; \"start here\" facade, it checks its own refs up front and says what to
;; write instead.

(def ^:private ref-slot-hints
  "Verb slot → the identity attribute a bare string means there."
  {:debit-account  :kontor.account/path
   :credit-account :kontor.account/path
   :account        :kontor.account/path
   :commodity      :kontor.commodity/symbol
   :journal        :kontor.journal/code})

(defn- check-ref!
  "Assert that `v` (already coerced to a ref by `kontor.book.build`)
   resolves to an existing entity in `db`. `slot` names the verb option
   for the error message."
  [db slot v]
  (when (some? v)
    (let [eid (try
                (cond
                  ;; lookup-ref — resolve by datalog rather than d/entity so a
                  ;; miss is a nil, not a logged transactor error.
                  (vector? v)  (d/q '[:find ?e . :in $ ?a ?val :where [?e ?a ?val]]
                                    db (first v) (second v))
                  ;; eid — an integer always "resolves" syntactically; the real
                  ;; question is whether any datom carries it. This is what
                  ;; catches a phantom eid left over from an earlier bad write.
                  (integer? v) (when (seq (d/datoms db :eavt v)) v)
                  (keyword? v) (d/q '[:find ?e . :in $ ?k :where [?e :db/ident ?k]] db v)
                  ;; entity map / anything else — let the transactor judge it.
                  :else        v)
                (catch Exception _ nil))]
      (when-not eid
        (let [id-attr (get ref-slot-hints slot)]
          (throw (ex-info
                  (str "kontor.book: " slot " " (pr-str v) " does not resolve to an "
                       "existing entity"
                       (when (and id-attr (vector? v) (= id-attr (first v)))
                         (str " — no entity has " id-attr " " (pr-str (second v))))
                       ". A bare string in this slot means "
                       (pr-str id-attr) "; create the entity first, or pass a "
                       "lookup-ref " (pr-str [id-attr "<value>"]) " / an entity id "
                       "that exists. (ADR-124)")
                  {:type  :kontor.book/unresolved-ref
                   :slot  slot
                   :value v
                   :identity-attribute id-attr})))))))

(defn- assert-refs-resolve!
  "Check every account / commodity / journal ref an entry names against
   `db` BEFORE tx-data is emitted, so the consumer gets a slot-named
   error instead of a transactor-level lookup miss.

   Only refs the caller supplied are checked; `nil` slots are left to
   `build-input`'s own required-field errors, which are more specific."
  [db {:keys [debit-account credit-account commodity journal postings] :as _opts}]
  (check-ref! db :journal (build/->journal-ref journal))
  (check-ref! db :commodity (build/->commodity-ref commodity))
  (if (seq postings)
    (doseq [p postings]
      (check-ref! db :account (build/->account-ref (:account p)))
      (when-let [c (:commodity p)]
        (check-ref! db :commodity (build/->commodity-ref c))))
    (do (check-ref! db :debit-account (build/->account-ref debit-account))
        (check-ref! db :credit-account (build/->account-ref credit-account)))))

(def ^:private journal-type-fallbacks
  "Journal types that may stand in for a requested type when the book holds
   NONE of the requested one. `:cash` and `:bank` are both settlement
   journals — see `resolve-journal`'s docstring. Deliberately one-directional:
   a `:bank` request does not fall back to `:cash`, because the verbs only
   ever request `:cash`, and a book with a `:bank` journal and no `:cash`
   journal has exactly one settlement journal to mean."
  {:cash [:bank]})

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
   explicitly. (note 197 — cash-journal-ambiguous P1.)

   ## `:cash` falls back to `:bank` (ADR-124)

   The settlement verbs (`receive!` / `pay!` / `receive-payment!` /
   `pay-bill!` / `distribute-dividend!`) bake in `:kontor.journal/type
   :cash`. But `:cash` and `:bank` are both settlement journal types in
   the kernel enum, and the distinction between them is about the SOURCE
   DOCUMENT (a till receipt vs a bank statement), not about the shape of
   the entry. A consumer whose chart models settlements as a single
   `:bank` journal — an entirely reasonable modelling choice, and the one
   a bank-statement-driven workflow leads to — could not call
   `receive-payment!` at all: it failed with \"no :journal of type :cash\",
   a clear message about a distinction the consumer had no reason to
   anticipate.

   So a settlement verb passes `fallback-types`, tried in order only when
   the preferred type yields NOTHING. A book that has a `:cash` journal
   behaves exactly as before — the fallback never engages — so this
   cannot silently reroute an existing consumer's entries."
  ([db journal-type] (resolve-journal db journal-type nil))
  ([db journal-type prefer-code]
   (let [fallback-types (get journal-type-fallbacks journal-type)
         of-type (fn [t] (d/q '[:find [?j ...]
                                :in $ ?t
                                :where [?j :kontor.journal/type ?t]]
                              db t))
         [journal-type js] (or (first (keep (fn [t]
                                              (let [js (of-type t)]
                                                (when (seq js) [t js])))
                                            (cons journal-type fallback-types)))
                               [journal-type []])]
     (cond
       (= 1 (count js)) (first js)
       (empty? js)
       (throw (ex-info (str "kontor.book: no :journal of type " journal-type
                            (when (seq fallback-types)
                              (str " (nor " (pr-str (vec fallback-types)) ")"))
                            " in the db — create one, or pass :journal explicitly")
                       {:journal-type journal-type :fallback-types fallback-types}))
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

   Orchestrations routed through `kontor.workflow.process/run-process`
   (`consolidate!`, `kontor.hr.payroll/run-payroll!`, …) return whatever
   `run-process`'s commit fn returns — by default the single tx-report for
   the ONE transaction every step's fragment is concatenated into, so the
   same `:db-after` / `:tempids` keys apply. (The earlier wording here
   pointed at a `vat-return/file!` that does not exist and a `:reports` key
   that `run-process` does not return; note 199 W4.)"
  ([conn opts] (entry! conn opts {}))
  ([conn opts extra-post-opts]
   (let [opts' (cond-> (dissoc opts :journal-code-hint)
                 (and (nil? (:journal opts)) (:journal-type opts))
                 (assoc :journal (resolve-journal (d/db conn) (:journal-type opts)
                                                  (:journal-code-hint opts)))

                 (nil? (:effective-date opts))
                 (assoc :effective-date (java.util.Date.)))]
     ;; ADR-124 — refuse a ref that does not resolve BEFORE any tx-data is
     ;; emitted, with the verb slot named. Without this a bare string in an
     ;; account slot was read by datahike as a tempid and the money went
     ;; into an empty phantom entity, still balanced, still :posted.
     (assert-refs-resolve! (d/db conn) opts')
     (posting/post-transaction! conn
                                (build-input opts')
                                (merge (post-opts opts') extra-post-opts)))))

;; ============================================================================
;; Reversal — the generic GL reverse builder (ADR-152)
;; ============================================================================
;;
;; Before ADR-152 the only way to reverse anything in kontor was the
;; document-specific `kontor.document.invoice/cancel!`, which hard-codes the
;; reversal's effective-date to `now` — so a January invoice discovered in
;; March could not be reversed INTO January, which is the case that actually
;; arises (you close a period, then find the error). A raw `entry!` GL entry
;; had no reverse path at all: the caller hand-rolled leg negation, the
;; `:reverses` link and the sealing dance. `:kontor.transaction/reverses` has
;; existed since ADR-007; what was missing was the builder.
;;
;; Odoo's equivalent is `account.move._reverse_moves`
;; (`addons/account/models/account_move.py:5430`).

(defn- ->eid
  "Unwrap a pulled ref (`{:db/id n}`) to its eid; pass scalars through."
  [v]
  (if (map? v) (:db/id v) v))

;; ----------------------------------------------------------------------------
;; The reversal's posting mirror, derived from `posting-option-keys` (ADR-170)
;; ----------------------------------------------------------------------------
;;
;; This used to be a hand-kept pull spec plus a hand-kept `cond->`, i.e. the
;; same allowlist-rebuild the read side already fixed
;; (`kontor.reporting.option-contract-test`). It failed the same way, four
;; times, with a different key each time: an attribute the front door could
;; write that the reversal silently dropped. Two of the drops were P0s.
;;
;;   - `:period-tag` — the reversal landed untagged, i.e. in `:normal`. The
;;     adjustment period never netted to zero while the normal period was
;;     polluted; worse, `kontor.compliance.period/closed-periods-covering`
;;     matches a lock on `(= tag period-tag)`, so a reversal re-tagged
;;     `:normal` was NOT matched by a seal on `:adjustment-13` — reversing an
;;     entry OUT of a hard-sealed adjustment period was accepted.
;;   - `:analytic-distributions` — an entry on an account carrying
;;     `:kontor.account/required-analytic-plans` was IRREVERSIBLE: the gate's
;;     `kontor.analytic/assert-required-analytic-plans!` refused the
;;     undistributed reversal. Since ADR-007 makes a correction a reversal
;;     plus a re-posting and never an in-place edit, such an entry could
;;     never be corrected at all. On an account that is NOT analytic-required
;;     the same drop was silent instead: the GL reversed, the analytic ledger
;;     did not, and every cost-centre report kept the original allocation
;;     forever.
;;   - `:narration` / `:display-type` were the inverse — FETCHED by the pull
;;     spec and re-emitted by neither branch, so a `:tax` or `:section` leg
;;     came back re-stamped `:product` (which changes both the
;;     `kontor.governance/balance-violations` grouping and
;;     `kontor.tax.tax-posting-builder`'s).
;;
;; So the mirror is now DERIVED from `build/posting-option-keys` rather than
;; kept parallel to it, and the two are reconciled at namespace load. A key
;; the front door admits but the reversal cannot carry is a load error, not a
;; silent data loss discovered by an auditor.

(def ^:private reversible-posting-options
  "For every key in `kontor.book.build/posting-option-keys`: `:pull`, the
   fragment spliced into the reversal's pull spec, and `:read`, the fn that
   turns a pulled original posting back into that option's value (nil = the
   original did not carry it, so the reversal does not either).

   Only `:amount` is sign-flipped. In particular an analytic percent is NOT:
   it is a share of the posting's OWN amount, which is already negated, so
   negating both would put the allocation back on the positive side — and
   `kontor.posting.validate` would refuse it outright, since a percent must
   lie in [0,100]."
  {:account   {:pull :kontor.posting/account
               :read #(->eid (:kontor.posting/account %))}
   :amount    {:pull :kontor.posting/amount
               :read #(some-> (:kontor.posting/amount %) money/negate-amount)}
   :commodity {:pull :kontor.posting/commodity
               :read #(->eid (:kontor.posting/commodity %))}
   :partner   {:pull :kontor.posting/partner
               :read #(->eid (:kontor.posting/partner %))}
   :entity    {:pull :kontor.posting/entity
               :read #(->eid (:kontor.posting/entity %))}
   :ledger    {:pull :kontor.posting/ledger
               :read #(->eid (:kontor.posting/ledger %))}
   :narration {:pull :kontor.posting/narration
               :read :kontor.posting/narration}
   :display-type {:pull :kontor.posting/display-type
                  :read :kontor.posting/display-type}
   :period-tag   {:pull :kontor.posting/period-tag
                  :read :kontor.posting/period-tag}
   :dimensions
   {:pull {:kontor.posting/dimensions [:kontor.posting-dimension/axis
                                       :kontor.posting-dimension/value]}
    :read (fn [p]
            (when (seq (:kontor.posting/dimensions p))
              (reduce (fn [m d]
                        (update m (:kontor.posting-dimension/axis d)
                                (fnil conj []) (:kontor.posting-dimension/value d)))
                      {}
                      (:kontor.posting/dimensions p))))}
   :analytic-distributions
   ;; NEW distribution entities, never the original's. The distribution
   ;; carries a CARDINALITY-ONE back-ref `:kontor.analytic-distribution/posting`
   ;; whose `:db/doc` invites reverse traversal, so one entity cannot describe
   ;; two postings: shared, the reversal's allocation would either be
   ;; attributed to the original posting or would silently re-point the
   ;; original's. Sharing would also make the two documents share mutable
   ;; state, which ADR-007 forbids — the reversal is its own sealed entry. The
   ;; mirror copies plan / account / percent verbatim; the sign lives on the
   ;; posting amount, so the cost centre nets to zero.
   {:pull {:kontor.posting/analytic-distributions
           [{:kontor.analytic-distribution/plan [:db/id]}
            {:kontor.analytic-distribution/account [:db/id]}
            :kontor.analytic-distribution/percent]}
    :read (fn [p]
            (when (seq (:kontor.posting/analytic-distributions p))
              (mapv (fn [d]
                      (cond-> {}
                        (:kontor.analytic-distribution/plan d)
                        (assoc :plan (->eid (:kontor.analytic-distribution/plan d)))
                        (:kontor.analytic-distribution/account d)
                        (assoc :account (->eid (:kontor.analytic-distribution/account d)))
                        (some? (:kontor.analytic-distribution/percent d))
                        (assoc :percent (:kontor.analytic-distribution/percent d))))
                    (:kontor.posting/analytic-distributions p))))}})

;; Load-time reconciliation. The only way this fires is a kontor developer
;; adding a posting option without teaching the reversal to carry it — the
;; exact edit that shipped four silent data-loss bugs. Failing at require
;; time makes that impossible to miss.
(when-not (= (set (keys reversible-posting-options)) build/posting-option-keys)
  (throw (ex-info (str "kontor.book: the reversal mirror and "
                       "kontor.book.build/posting-option-keys have diverged. "
                       "Every posting option the facade accepts must be "
                       "re-emittable by reverse-tx-data, or a reversal "
                       "silently drops it (ADR-170). Missing from the "
                       "mirror: "
                       (pr-str (vec (sort-by str (remove
                                                  (set (keys reversible-posting-options))
                                                  build/posting-option-keys))))
                       "; unknown to posting-option-keys: "
                       (pr-str (vec (sort-by str (remove
                                                  build/posting-option-keys
                                                  (keys reversible-posting-options)))))
                       ".")
                  {:type :kontor.book/reversal-contract-divergence})))

(def ^:private reversible-posting-pull
  "The pull spec, derived from [[reversible-posting-options]] so it cannot
   drift from the set of options the reversal re-emits."
  (mapv :pull (map val (sort-by (comp str key) reversible-posting-options))))

(defn- reverse-posting
  "Mirror one pulled original posting into a `:postings` entry for the
   reversal."
  [pulled]
  (reduce-kv (fn [m k {:keys [read]}]
               (let [v (read pulled)]
                 (cond-> m (some? v) (assoc k v))))
             {}
             reversible-posting-options))

(def reverse-option-keys
  "Every option [[reverse-tx-data]] understands. Strict for the same reason
   `kontor.book.build/entry-option-keys` is (ADR-124): a mistyped
   `:reversal-date` used to be dropped in a `select-keys` and the reversal
   quietly landed on TODAY — in a different period than the one asked for,
   which is the single thing this builder exists to control."
  #{:transaction :reversal-date :narration :external-id :journal :period-tag
    :actor :partner :entity :posted-at :vt-from :vt-to})

(defn resolve-transaction
  "Entity id of the transaction `spec` denotes — an eid, a lookup-ref, or a
   bare `:kontor.transaction/external-id` string. nil when it resolves to
   nothing."
  [db spec]
  (cond
    (and (integer? spec) (pos? spec)) (when (seq (d/datoms db :eavt spec)) spec)
    (vector? spec) (try (:db/id (d/entity db spec))
                        (catch Exception _ nil))
    (string? spec) (d/q '[:find ?t . :in $ ?x
                          :where [?t :kontor.transaction/external-id ?x]]
                        db spec)
    :else nil))

(defn reversal-of
  "The transaction that reverses `spec`, or nil. `(some? (reversal-of db t))`
   is the \"has this been reversed?\" predicate."
  [db spec]
  (when-let [eid (resolve-transaction db spec)]
    (d/q '[:find ?r . :in $ ?o :where [?r :kontor.transaction/reverses ?o]] db eid)))

(defn- with-reverses-link
  "Splice `:kontor.transaction/reverses` onto the transaction entity map of
   an already-built tx-data vector. Kept separate because `build-input`'s
   friendly option set deliberately carries no raw kernel attrs, and the
   transaction map is identifiable by the one attr every entry has."
  [tx-data orig]
  (mapv (fn [form]
          (if (and (map? form) (contains? form :kontor.transaction/journal))
            (assoc form :kontor.transaction/reverses orig)
            form))
        tx-data))

(defn reverse-tx-data
  "Pure-over-`db` tx-data for a reversing entry: every leg of the original
   with the sign flipped, in the same journal, dated `:reversal-date`, linked
   back by `:kontor.transaction/reverses`. Per ADR-007 a correction is a
   reversal plus a re-posting, never an in-place edit — this is the builder
   that makes that the cheap path.

   Options:
     :transaction    — the original (eid / lookup-ref / external-id string).
                       REQUIRED.
     :reversal-date  — `:kontor.transaction/effective-date` of the reversal.
                       Defaults to NOW, matching `entry!`. Pass it
                       explicitly whenever the reversal belongs in a
                       specific period — that is the whole reason this
                       option exists, and `invoice/cancel!` not having it is
                       the defect ADR-152 closes.
     :narration      — defaults to \"reversal of <external-id or eid>\".
     :external-id    — optional. Deliberately NOT derived from the
                       original's (no \"-REV\" suffix): a reversal is its own
                       legal document and, in a journal with ADR-151
                       allocation on, receives its own gapless number.
     :journal        — defaults to the original's journal.
     :period-tag     — ADR-014 adjustment-period routing. DEFAULTS TO THE
                       ORIGINAL'S, per leg. Pass it to place the reversal in
                       a different period layer; `:period-tag :normal` (or
                       `nil`) moves it into the normal layer explicitly.

                       Inheriting is the default because a period tag is
                       half of an entry's period coordinate, coequal with
                       the effective-date — this builder already inherits
                       the journal and lets `:reversal-date` place the entry
                       in time, and the tag is the other axis of the same
                       address. Dropping it (which is what happened before
                       ADR-170) leaves the adjustment period never netting
                       to zero while the normal period carries an amount
                       that was never booked there, and — because
                       `kontor.compliance.period/closed-periods-covering`
                       matches a lock on `(= tag period-tag)` — the only
                       operation the drop actually ENABLED was booking into
                       a sealed adjustment period.

                       The legitimate counter-case (an error in period 13
                       discovered after period 13 closed belongs in the next
                       open period) is served by saying so: `:reversal-date`
                       moves it in time and `:period-tag` moves it in layer.
                       Both are explicit acts by an operator who knows which
                       book they are correcting, and both are still refused
                       by a lock on the TARGET period — which is the point.
     :actor          — who is reversing (ADR-150).
     :posted-at / :vt-from / :vt-to — as for `entry!`.

   An option key this set does not name is an ERROR
   (`:kontor.book/unknown-option`), not a silent drop — see
   [[reverse-option-keys]].

   Refuses, rather than producing something subtly wrong:
     - an unknown transaction                (`:kontor.book/unknown-transaction`)
     - a transaction that is not posted      (`:kontor.book/not-posted`) — a
       draft is not reversed, it is corrected or discarded
     - one already reversed                  (`:kontor.book/already-reversed`) —
       double-reversing silently re-books the original amount, which reads
       as a duplicate sale in every report
     - one with no postings                  (`:kontor.book/no-postings`)"
  [db {:keys [transaction reversal-date narration external-id journal] :as opts}]
  (build/check-keys! opts reverse-option-keys "reverse option")
  (let [orig (or (resolve-transaction db transaction)
                 (throw (ex-info (str "kontor.book/reverse: no transaction found for "
                                      (pr-str transaction))
                                 {:type :kontor.book/unknown-transaction
                                  :transaction transaction})))
        hdr  (d/pull db [:kontor.transaction/posted-at
                         :kontor.transaction/external-id
                         :kontor.transaction/effective-date
                         {:kontor.transaction/journal [:db/id]}]
                     orig)]
    (when-not (:kontor.transaction/posted-at hdr)
      (throw (ex-info (str "kontor.book/reverse: transaction " orig " is not posted — "
                           "a draft is corrected or discarded, not reversed")
                      {:type :kontor.book/not-posted :transaction orig})))
    (when-let [r (reversal-of db orig)]
      (throw (ex-info (str "kontor.book/reverse: transaction " orig
                           " has already been reversed by " r
                           " — reversing twice re-books the original amount, which "
                           "reads as a duplicate entry in every report")
                      {:type :kontor.book/already-reversed
                       :transaction orig :reversal r})))
    (let [ps (mapv #(d/pull db reversible-posting-pull %)
                   (sort (d/q '[:find [?p ...] :in $ ?t
                                :where [?p :kontor.posting/transaction ?t]]
                              db orig)))]
      (when (empty? ps)
        (throw (ex-info (str "kontor.book/reverse: transaction " orig " has no postings")
                        {:type :kontor.book/no-postings :transaction orig})))
      (with-reverses-link
        (entry-tx-data
         (merge
          (select-keys opts [:posted-at :vt-from :vt-to :actor :partner :entity])
          {:journal        (or journal (->eid (:kontor.transaction/journal hdr)))
           :effective-date (or reversal-date (java.util.Date.))
           :narration      (or narration
                               (str "reversal of "
                                    (or (:kontor.transaction/external-id hdr) orig)))
           :postings
           ;; `(contains? opts :period-tag)` and not `(:period-tag opts)`:
           ;; `:period-tag :normal` / `nil` must be able to say "move this
           ;; correction into the normal layer", which is indistinguishable
           ;; from "not passed" under a truthiness test.
           (let [override? (contains? opts :period-tag)
                 tag       (:period-tag opts)]
             (mapv (fn [p]
                     (cond-> (reverse-posting p)
                       (and override? tag)       (assoc :period-tag tag)
                       (and override? (nil? tag)) (dissoc :period-tag)))
                   ps))}
          (when external-id {:external-id external-id})))
        orig))))

(defn reverse!
  "Reverse a posted transaction: sign-flip every leg, date the reversal
   where you want it, link `:kontor.transaction/reverses`, commit through
   the gate. See [[reverse-tx-data]] for options and refusals.

   The reversal is its own sealed transaction — the original is untouched,
   which is what ADR-007 requires and what an auditor expects to see. After
   it commits, the account balances of the original net to zero as of the
   reversal date, while a bitemporal read as of the day before still shows
   the original standing (`kontor.reporting.balance`, ADR-048).

   Returns the tx-report."
  [conn opts]
  (gate/transact-with-validation conn (reverse-tx-data (d/db conn) opts)))

;; ============================================================================
;; Gapless legal numbering (ADR-151) — re-exported for discoverability
;; ============================================================================

(def sequence-gaps
  "Re-export of `kontor.numbering/sequence-gaps` — holes in a journal's
   allocated legal-number series, per reset bucket (ADR-151). Here as well
   as in `kontor.numbering` because `kontor.book` is the front door a
   consumer reads first, and \"is my invoice series intact?\" is a question
   they ask on day one."
  numbering/sequence-gaps)

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
        built (try (assert-refs-resolve! (d/db conn) opts')   ; ADR-124
                   {:input (build-input opts')}
                   (catch clojure.lang.ExceptionInfo e
                     {:diag {:severity :error
                             :code     (or (:type (ex-data e)) :book/malformed-entry)
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
     :debit-account   \"Equity:Retained-Earnings\"
     :credit-account  \"Liabilities:Dividends-Payable\"

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
     :debit-account   \"Liabilities:Dividends-Payable\"
     :credit-account  \"Assets:Bank\"

   The shareholder records the receipt separately on their books
   via `receive!` (Dr Bank, Cr Income:Dividends) — the investment-
   income regime in `kontor-l10n-<cc>` then taxes it (DE
   Abgeltungsteuer, US qualified-dividend, FR PFU, JP 20.315 %, …).

   Note 107 §2.6."
  ([conn opts] (distribute-dividend! conn opts {}))
  ([conn opts extra] (entry! conn (assoc opts :journal-type :cash :journal-code-hint "CD") extra)))
