(ns kontor.gate
  "The kernel's transactor gate — the single entry point for kernel
   writes that need to pass through `validate-and-apply` (sealing +
   legal-hold + period-lock + state-machine + sum-to-zero) AND the
   datalog invariants registry.

   This namespace exists to dissolve the circular dependency that
   used to require five sub-validator namespaces (`bitemporal`,
   `period`, `legal-hold`, `status-machine`, `posting`) to call back
   into `kontor.validation/transact-with-validation` via
   `requiring-resolve` (T-2 in note 160). The split is:

   - **`kontor.gate`** (this ns) — minimal: holds the gate API +
     registers a hook that `kontor.validation` populates at load
     time. Depends only on `datahike.api` + `kontor.invariant`.
   - **`kontor.validation`** — composes the gate from the five
     sub-validators (it still requires them all). At load time it
     calls `kontor.gate/register-validate-and-apply!` to wire its
     `validate-and-apply` into the gate.
   - **Sub-validators** (`bitemporal`, `period`, `legal-hold`,
     `status-machine`, `posting`) — require `kontor.gate` directly
     for their `!` wrappers. No more `requiring-resolve` calls; no
     cycle.

   For the gate to function, `kontor.validation` MUST be loaded at
   least once before the first `transact-with-validation` call.
   `kontor.core` requires it eagerly for this reason — any consumer
   that uses `kontor.core/install-schema!` or `create-test-db` gets
   the gate populated transitively.

   Direct callers of sub-validator `!` wrappers without going
   through `kontor.core` should ensure `kontor.validation` is on
   their require list."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            ;; ADR-150. Safe to require here: `kontor.actor` depends only on
            ;; `datahike.api` + the pure `kontor.actor.ref`, so it does not
            ;; reach back into the gate and no cycle forms.
            [kontor.actor :as actor]
            [kontor.invariant :as inv]))

;; ============================================================================
;; Registered gate fn (populated by kontor.validation at load time)
;; ============================================================================

(def ^:private validate-and-apply-fn
  "Atom holding the `validate-and-apply` function `kontor.validation`
   registers at load time via `register-validate-and-apply!`.

   Inverts the dependency so this namespace (which is required by
   the five sub-validators) doesn't itself require
   `kontor.validation` (which requires those same sub-validators) —
   the cycle that motivated the original `requiring-resolve`
   workaround."
  (atom nil))

(defn register-validate-and-apply!
  "Called by `kontor.validation` at load time to register its
   composed `validate-and-apply` function into this gate. Idempotent;
   re-registering replaces the prior value (useful for REPL
   workflows where `kontor.validation` is reloaded)."
  [f]
  (reset! validate-and-apply-fn f))

;; ============================================================================
;; Dangling string tempids in ref positions (ADR-124)
;; ============================================================================
;;
;; datahike (like datascript/datomic) reads a STRING in a `:db.type/ref`
;; position as a TEMPID. That is a deliberate, useful feature — it is how
;; `{:db/id "count" …}` links to `{:kontor.x/row "count"}` inside one
;; tx-data (see kontor.inventory.count, kontor.hr.payroll, …).
;;
;; It is also a silent-corruption hazard the moment a consumer writes an
;; IDENTIFIER where a ref belongs:
;;
;;     (book/sell! conn {:debit-account "Assets:AR" …})
;;
;; "Assets:AR" is not declared as a `:db/id` anywhere in that tx-data, so
;; datahike mints a BRAND NEW entity for it — one with no attributes at
;; all — and posts the money into it. The entry still sums to zero, the
;; sealing/period/state validators still pass, the transaction still
;; reports `:posted`, and the consumer's balance query on their real
;; account reads 0. Money vanishes into an entity that does not exist.
;;
;; The distinction between the legitimate and the corrupt case is exact
;; and cheap to check: a string in a ref position is a tempid ONLY IF the
;; same tx-data DECLARES it — as a `:db/id`, or in the entity position of
;; a `[:db/add …]` / `[:db/retract …]` form. Anything else is a dangling
;; reference to an entity that will never exist, and no kernel write ever
;; wants one. So the gate refuses it for EVERY builder at once, rather
;; than each of the ~200 `*-tx-data` builders having to defend itself.

(defn- ref-attr?
  [schema a]
  (= :db.type/ref (:db/valueType (get schema a))))

(defn actor-uid-attr?
  "True for the `…-uid` family — `:kontor.audit/create-uid`,
   `:kontor.status-history/changed-by-uid`,
   `:kontor.payment-application/applied-by-uid`,
   `:kontor.dispute/opened-by-uid`, `:kontor.receipt/inspector-uid`, … —
   which this check deliberately does NOT guard.

   All of them are `:db.type/ref`, and all of them point at a USER. But
   **kontor models no user entity**: `:kontor.audit/{create-uid,write-uid}`
   is an audit-trail seam and the consumer app owns its own users
   (ADR-002 cohabitation). So there is no identity attribute the kernel
   could name in a \"did you mean …?\" hint, and the de-facto convention
   across the shipped suite is an OPAQUE ACTOR STRING (`\"test\"`,
   `\"sarah\"`, `\"actor-1\"` — 296 call sites).

   Those strings DO mint phantom entities, so the audit trail records a
   pointer that resolves to nothing. That is a real finding, but fixing it
   is a separate decision from the money-loss fix this check exists for:
   either kontor grows a user entity for consumers to point at, or the
   `-uid` attributes become `:db.type/string`. Both need their own ADR.
   Until then, refusing them here would break every consumer that follows
   the existing convention, so the check stays scoped to the refs that
   carry accounting identity. Note 199 W10."
  [a]
  (str/ends-with? (name a) "-uid"))

(defn- lookup-ref?
  "`[:kontor.account/path \"Assets:AR\"]` — a 2-vector whose head is a
   keyword. This is datahike's own shape test, and it MUST be applied
   before treating a vector value as a cardinality-many collection:
   a lookup-ref's second slot is a string that is emphatically not a
   tempid."
  [v]
  (and (vector? v) (= 2 (count v)) (keyword? (first v))))

(defn- collect-refs
  "One walk over `tx-data`, returning
     {:declared #{string-tempid …} :used {attr #{string …}}}
   `:declared` = strings this tx-data introduces as tempids (a `:db/id`
   at any nesting depth, or the entity slot of a list form).
   `:used`     = every string found in an attribute-value slot, keyed by
   attribute, so the schema only has to be consulted for those.

   Nothing is judged here — [[dangling-string-refs]] applies the schema
   and the [[actor-uid-attr?]] scope."
  [tx-data]
  (let [declared (volatile! #{})
        used     (volatile! {})
        use!     (fn [a v] (vswap! used update a (fnil conj #{}) v))]
    (letfn [(walk-map [m]
              (when (string? (:db/id m)) (vswap! declared conj (:db/id m)))
              (doseq [[a v] (dissoc m :db/id)]
                (cond
                  (string? v)     (use! a v)
                  (lookup-ref? v) nil                   ; not a tempid
                  (map? v)        (walk-map v)
                  (sequential? v) (doseq [x v]
                                    (cond (string? x)     (use! a x)
                                          (lookup-ref? x) nil
                                          (map? x)        (walk-map x))))))
            (walk-form [form]
              (cond
                (map? form) (walk-map form)
                (sequential? form)
                (let [[op e a & vs] form]
                  ;; A string in the ENTITY slot of any list form is a tempid
                  ;; declaration (`[:db/add \"p0\" …]`); be permissive there so
                  ;; unfamiliar ops (tx-fns, :db/purge, …) never false-positive.
                  (when (and (keyword? op) (string? e))
                    (vswap! declared conj e))
                  ;; Only the ops whose trailing slots are genuinely VALUES get
                  ;; their values checked. :db/cas carries two (old, new).
                  (when (#{:db/add :db/retract :db/cas} op)
                    (doseq [v vs :when (string? v)] (use! a v))))))]
      (doseq [form tx-data] (walk-form form)))
    {:declared @declared :used @used}))

(defn dangling-string-refs
  "Every `[attr string]` pair in `tx-data` that sits in a `:db.type/ref`
   slot without the string being declared as a tempid in the same
   tx-data. Empty vector when the tx-data is clean. Pure; exposed for
   testing and for callers that want to inspect rather than throw."
  [db tx-data]
  (let [{:keys [declared used]} (collect-refs tx-data)
        suspects (into {} (keep (fn [[a vs]]
                                  (let [vs' (into #{} (remove declared) vs)]
                                    (when (seq vs') [a vs']))))
                       used)]
    (if (empty? suspects)
      []
      (let [schema (or (:schema db) (d/schema db))]
        (vec (for [[a vs] suspects
                   :when  (and (ref-attr? schema a)
                               (not (actor-uid-attr? a)))
                   v      (sort vs)]
               [a v]))))))

(defn- lookup-ref-hint
  "The idiomatic lookup-ref for the identity attribute of `a`'s target,
   for the error message. Falls back to a generic phrasing."
  [a v]
  (let [id-attr (case a
                  (:kontor.posting/account
                   :kontor.account/parent
                   :kontor.journal/default-account) :kontor.account/path
                  (:kontor.posting/commodity
                   :kontor.account/commodity)       :kontor.commodity/symbol
                  :kontor.transaction/journal       :kontor.journal/code
                  (:kontor.posting/partner
                   :kontor.transaction/partner)     :kontor.partner/external-id
                  (:kontor.posting/entity
                   :kontor.fiscal-unit/parent-entity) :kontor.entity/code
                  :kontor.posting/ledger            :kontor.ledger/code
                  nil)]
    (if id-attr
      (str "[" id-attr " " (pr-str v) "]")
      (str "[<identity-attribute> " (pr-str v) "]"))))

(defn assert-no-dangling-string-refs!
  "Throw `:kontor.gate/dangling-string-ref` when `tx-data` puts a bare
   string in a `:db.type/ref` slot without declaring it as a tempid.

   See the section comment above for why this is a gate-level check and
   not a per-builder one."
  [db tx-data]
  (when-let [bad (seq (dangling-string-refs db tx-data))]
    (throw (ex-info
            (str "kontor.gate: bare string in a reference position — "
                 (str/join
                  "; "
                  (for [[a v] bad]
                    (str (pr-str v) " under " a
                         " (did you mean the lookup-ref " (lookup-ref-hint a v) "?)")))
                 ". datahike reads a string in a ref slot as a TEMPID, so this "
                 "write would have created an EMPTY entity and posted against "
                 "it — silently, and still balanced. Pass a lookup-ref, an "
                 "entity id, or declare the string as a :db/id in this same "
                 "tx-data. (ADR-124)")
            {:type :kontor.gate/dangling-string-ref
             :refs (mapv (fn [[a v]] {:attribute a :value v}) bad)}))))

;; ============================================================================
;; Gate API — the entry point sub-validators (and end-user code) call
;; ============================================================================

(defn transact-with-validation
  "Run the kernel's data-driven datalog invariants against `tx-data`,
   then transact through `[:db.fn/call validate-and-apply tx-data]`
   so the transactor-side structural validators (sealing, legal-hold,
   period-lock, state-machine, sum-to-zero) compose with the user's
   write.

   Throws `ex-info` on any failed check:
     - a bare string in a ref position raises
       `:type :kontor.gate/dangling-string-ref` (ADR-124)
     - state invariants raise `:type :invariant/invariant-mismatch`
     - sealing raises `:type :sealing/silent-retract-of-posted`
     - legal-hold raises `:type :legal-hold/blocked-destructive-write`
     - period-lock raises `:type :period/locked`
     - state-machine raises `:type :state-machine/forbidden-transition`
     - sum-to-zero raises `:type :validation/sum-to-zero`

   CAVEAT on those `:type`s: only the first two run EAGERLY here and so
   reach the caller with their `ex-data` intact. The rest are composed
   in-transactor via `[:db.fn/call validate-and-apply …]`, and datahike's
   tx-fn wrapping FLATTENS the `ex-info` into the message string — so
   `(ex-data e)` is `nil` and the `:type` is only greppable out of
   `(ex-message e)`. Consumers that branch on the failure kind must
   currently regex the message. Note 199 W6.

   Returns the resulting tx-report on success **on the JVM**. On
   ClojureScript, datahike has no synchronous `transact`, so this commits
   via `transact!` and returns its async result (a promise-channel /
   whatever the cljs datahike build yields) — the same convention as
   calling `d/transact!` directly. The validation is identical on both:
   the invariant pass runs eagerly, then the structural validators compose
   in-transactor via `[:db.fn/call validate-and-apply …]`. This is what
   lets a browser commit a gate-validated entry with the same code the
   server uses (exercised by `kontor.posting-write-cljs-test`).

   Throws a helpful error if `kontor.validation` has not been loaded
   yet (the atom is nil) — typically means the caller's classpath
   misses `kontor.validation` (which is bundled in the kernel jar
   but may be missing from a manually-trimmed deps set)."
  [conn tx-data]
  (let [f @validate-and-apply-fn]
    (when-not f
      (throw (ex-info (str "kontor.gate: no validate-and-apply registered. "
                           "Require `kontor.validation` before calling "
                           "transact-with-validation. The kernel's "
                           "`kontor.core` requires it for you on standard "
                           "consumer paths.")
                      {:error :gate/not-registered})))
    ;; ADR-150 — normalise actor references BEFORE the two pre-checks below.
    ;; Both of them resolve refs against the db (`assert-invariants` builds a
    ;; speculative db from the tx-data), so an as-yet-unprovisioned
    ;; `[:kontor.actor/uid "bob"]` would raise datahike's raw
    ;; `:entity-id/missing` here — before the normaliser inside
    ;; `validate-and-apply` ever got the chance to turn it into a real
    ;; actor. Running it at both points is deliberate and safe: the pass is
    ;; idempotent (its output declares its tempids, which
    ;; `collect-uid-strings` then excludes), and the transactor-side call is
    ;; what covers pg-datahike's `:tx-wrap`, which never enters this fn.
    (let [tx-data (actor/resolve-uid-refs (d/db conn) tx-data)]
      (assert-no-dangling-string-refs! (d/db conn) tx-data)
      (inv/assert-invariants conn tx-data)
      ;; datahike's synchronous `transact` is JVM-only; cljs must use the
      ;; async `transact!`. The tx-fn wrap + validation are identical.
      #?(:clj  (d/transact conn [[:db.fn/call f tx-data]])
         :cljs (d/transact! conn [[:db.fn/call f tx-data]])))))

;; ============================================================================
;; Dry-run — the "web-form check" half (research note 190)
;; ============================================================================

(defn- ex->diagnostic
  [e]
  (let [d (ex-data e)]
    {:severity :error
     ;; kontor invariants carry `:type`; datahike substrate errors
     ;; (lookup-ref miss, schema) carry `:error` — fall back so the
     ;; diagnostic code is never nil.
     :code     (or (:type d) (:error d))
     :message  (ex-message e)
     :data     (dissoc d :tx-data)}))

(defn validate-candidate
  "Non-committing dry-run of the full gate against `tx-data`: runs the SAME
   datalog invariants AND transactor-side structural validators (sealing,
   legal-hold, period-lock, state-machine, sum-to-zero) that
   `transact-with-validation` enforces at commit — but returns structured
   diagnostics instead of throwing, and never persists.

   Because it reuses the *same* predicate functions as the gate, live
   form-validation feedback and the authoritative commit cannot drift (the
   Odoo onchange↔constrains discipline; research note 190). Intended to be
   called server-side (e.g. over distributed-scope) on each form edit, with
   the pure balance half (`kontor.posting/validate`) run client-side.

   Returns `{:ok? boolean :diagnostics [{:severity :code :message :data} …]}`.
   Both phases run independently so multiple issues surface together."
  [conn tx-data]
  (let [f @validate-and-apply-fn]
    (when-not f
      (throw (ex-info "kontor.gate: no validate-and-apply registered (require kontor.validation)."
                      {:error :gate/not-registered})))
    (let [tx-data (try (actor/resolve-uid-refs (d/db conn) tx-data)
                       ;; strict mode refuses an unregistered actor; report it
                       ;; as a diagnostic like any other, and keep checking the
                       ;; rest of the form rather than bailing out.
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) _
                         tx-data))
          diags (-> []
                    (into (try (actor/resolve-uid-refs (d/db conn) tx-data) nil
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)])))
                    (into (try (assert-no-dangling-string-refs! (d/db conn) tx-data) nil
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)])))
                    (into (try (inv/assert-invariants conn tx-data) nil
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)])))
                    (into (try (f @conn tx-data) nil        ; run validators against the live db; no commit
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)]))))]
      {:ok?         (empty? diags)
       :diagnostics (vec diags)})))
