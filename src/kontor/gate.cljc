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
  (:require [datahike.api :as d]
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
;; Gate API — the entry point sub-validators (and end-user code) call
;; ============================================================================

(defn transact-with-validation
  "Run the kernel's data-driven datalog invariants against `tx-data`,
   then transact through `[:db.fn/call validate-and-apply tx-data]`
   so the transactor-side structural validators (sealing, legal-hold,
   period-lock, state-machine, sum-to-zero) compose with the user's
   write.

   Throws `ex-info` on any failed check:
     - state invariants raise `:type :invariant/invariant-mismatch`
     - sealing raises `:type :sealing/silent-retract-of-posted`
     - legal-hold raises `:type :legal-hold/blocked-destructive-write`
     - period-lock raises `:type :period/locked`
     - state-machine raises `:type :state-machine/forbidden-transition`
     - sum-to-zero raises `:type :validation/sum-to-zero`

   Returns the resulting tx-report on success.

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
    (inv/assert-invariants conn tx-data)
    (d/transact conn [[:db.fn/call f tx-data]])))

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
    (let [diags (-> []
                    (into (try (inv/assert-invariants conn tx-data) nil
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)])))
                    (into (try (f @conn tx-data) nil        ; run validators against the live db; no commit
                               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                 [(ex->diagnostic e)]))))]
      {:ok?         (empty? diags)
       :diagnostics (vec diags)})))
