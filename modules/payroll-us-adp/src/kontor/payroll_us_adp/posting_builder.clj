(ns kontor.payroll-us-adp.posting-builder
  "UsPayrollPostingBuilder — materialize ADP-derived `PayrollFacts`
   into balanced GL postings.

   ## Per-component routing (note 83 §5)

   Each `PayrollFacts/:components` entry carries `:role` (from the
   wage-type-map rule). We route each role to a consumer-supplied GL
   account via the `:accounts` map passed to `build-postings`. The
   `:accounts` map keys are the wage-type-map's `:account-key` values
   (`:wages-expense`, `:ee-fed-withheld`, `:net-pay-payable`, …).

   ## Parallel-ledger split (ADR-021, note 83 §6.3)

   Each component carries `:ledgers` — the set of ledger frameworks
   the posting should land on. For C3 we emit one posting per
   (component, ledger) pair so:
     - book-only items (PTO accrual delta, 401(k) match before §404(a)(6)
       grace period closes) only hit `:us-gaap`.
     - cash-basis wage items hit both `:us-gaap` and `:us-tax`.

   The consumer passes `{:us-gaap <ledger-eid>  :us-tax <ledger-eid>}`
   into the call; we emit one posting per matching key.

   ## Multi-state allocation (note 83 §4)

   STRONGLY recommended: per-state allocation lives on
   `:kontor.posting/analytic-distributions` via an `:analytic-plan/code
   \"state\"` (consumer-installed at install time), NOT on
   `:kontor.posting/entity`. A US LLC with employees in 15 states is ONE
   legal entity — `:kontor.posting/entity` is reserved for true multi-LLC /
   cross-border scenarios.

   Per-state allocation uses the kernel's existing analytic-
   distribution machinery (ADR-022): each wage posting gets a
   distribution `{:plan [:analytic-plan/code \"state\"] :account [:analytic-account/path \"state:CA\"] :percent 100M}` (or split
   distributions for hybrid employees).

   ## Sum-to-zero (ADR-021 + ADR-031)

   The substrate enforces sum-to-zero per (ledger, commodity) — and
   per (entity, ledger, commodity) in multi-entity mode. Our posting
   set must balance per-ledger. We compute the balancing leg per
   ledger by summing every other posting; this naturally turns ADP's
   already-balanced GLI into a self-balancing posting set within each
   ledger.

   ## What the builder does NOT do

   - Bundle a CoA: the consumer supplies the account refs.
   - Compute taxes: ADP did that.
   - Open / close pay-periods: the orchestrator does that.
   - W-2 reconciliation: that lives in `kontor.payroll-us-adp.w2-recon`.

   See doc/decisions.md ADR-077 + doc/research/83-us-adp-gli-research-before.md §5–§6."
  (:require [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- abs-bd ^BigDecimal [^BigDecimal x] (.abs x))
(defn- neg-bd ^BigDecimal [^BigDecimal x] (.negate x))

(defn- ledger-membership
  "Which ledgers should this component post to? Returns a non-empty
   subset of the consumer-supplied ledgers map's keys.

   Two modes:
     - Single-ledger (legacy, `{:default <eid>}`): the component lands
       on the single ledger regardless of its declared `:ledgers` set.
       This is what `kontor.hr.payroll/run-payroll!` passes today when
       the consumer uses the simple `:ledger` opt.
     - Multi-ledger (`{:us-gaap <eid> :us-tax <eid> …}`): respect the
       component's `:ledgers` set; book-only entries (PTO accrual,
       401(k) match) appear ONLY on the matching key."
  [component ledgers-map]
  (let [requested (or (:ledgers component)
                      (some-> component :rule :ledgers))]
    (cond
      ;; Single-ledger mode: route everything to that one ledger.
      (and (= 1 (count ledgers-map)) (contains? ledgers-map :default))
      (vec (keys ledgers-map))

      ;; Multi-ledger mode with a declared :ledgers set: intersect.
      (set? requested)
      (vec (filter ledgers-map requested))

      ;; Multi-ledger mode without a declared :ledgers set: every
      ;; configured ledger.
      :else
      (vec (keys ledgers-map)))))

(defn- state-distribution
  "Build an analytic-distribution map for per-state allocation, or nil
   if the component lacks a `:state` tag. The consumer is responsible
   for installing the `:analytic-plan/code \"state\"` plan and the
   per-state `:analytic-account` rows at bootstrap time (see
   `kontor.payroll-us-adp.core/install-state-analytic-plan!`).

   Returns a vector of distribution maps (one when a single state, or
   N when the consumer passes a fractional `state-allocations` map
   carrying `{state-code → percent}`)."
  [component state-allocations]
  (let [state (:state component)]
    (cond
      ;; Hybrid / multi-state allocation override.
      (and state (map? state-allocations) (seq state-allocations))
      (mapv (fn [[s pct]]
              {:analytic-distribution/plan [:analytic-plan/code "state"]
               :analytic-distribution/account
               [:analytic-account/path (str "state:" (name s))]
               :analytic-distribution/percent (bigdec pct)})
            state-allocations)

      state
      [{:analytic-distribution/plan [:analytic-plan/code "state"]
        :analytic-distribution/account
        [:analytic-account/path (str "state:" (name state))]
        :analytic-distribution/percent 100M}]

      :else nil)))

(defn- resolve-account
  "Look up the GL account for a component via the consumer's
   `:accounts` map. Prefers the component's `:account-key` (set from
   the wage-type-map rule); falls back to `:role`; finally
   `:accounts/wages-default` when present. Throws when none resolves
   — kontor never silently drops a posting."
  [component accounts]
  (let [key1 (:account-key component)
        key2 (:role component)
        acct (or (when key1 (get accounts key1))
                 (when key2 (get accounts key2))
                 (get accounts :wages-default)
                 (get accounts :accounts/wages-default))]
    (when-not acct
      (throw (ex-info "UsPayrollPostingBuilder: no :accounts entry for role"
                      {:account-key key1
                       :role key2
                       :available-keys (keys accounts)
                       :hint "Add an entry to the :accounts map keyed by the wage-type-map :account-key (e.g. :wages-expense, :ee-fed-withheld)."})))
    acct))

;; ============================================================================
;; build-postings
;; ============================================================================

(defn- component-posting-amounts
  "Determine the GL-side amount for a component on a given ledger.

   GL sign convention:
     wage-expense / employer-tax-expense    → Dr (+)
     liability accruals (withholding, etc.) → Cr (-)
     net-pay liability                      → Cr (-)

   ADP's GLI is already presented in GL-perspective amounts (debits
   positive / credits negative). The PayrollFacts components were
   transformed in compute.clj to use the employee-perspective sign
   convention (+ = earned by employee, - = withheld). Here we map
   back to GL-perspective for the GL postings."
  [component]
  (let [role (:role component)
        amt  (:amount component)]
    (cond
      ;; Employer-side expenses — always Dr.
      (:employer-side? component) (abs-bd amt)

      ;; Wage-expense (Dr). compute.clj kept :amount positive.
      (#{:wage-expense :pto-paid} role) (abs-bd amt)

      ;; Withholdings / deductions → Cr (negative GL amount).
      ;; compute.clj already wrote :amount as negative for these.
      :else amt)))

(defn- one-component-postings
  "Emit posting maps for a component, one per ledger it belongs to."
  [{:keys [component accounts ledgers-map commodity state-allocations]}]
  (let [ledgers (ledger-membership component ledgers-map)
        gl-amt  (component-posting-amounts component)
        acct    (resolve-account component accounts)
        dists   (state-distribution component state-allocations)
        narration (str (:role component) " — " (or (:description component) ""))]
    (for [ledger-key ledgers
          :let [ledger-eid (get ledgers-map ledger-key)]
          :when ledger-eid]
      (cond-> {:kontor.posting/account acct
               :kontor.posting/amount gl-amt
               :kontor.posting/commodity commodity
               :kontor.posting/narration narration
               :kontor.posting/ledger ledger-eid}
        (seq dists) (assoc :kontor.posting/analytic-distributions dists)))))

(defn- balancing-postings-by-ledger
  "Per-ledger, compute the sum of explicit postings; if non-zero, emit
   a balancing leg to the consumer-supplied `:net-pay-payable` account
   so the per-(ledger, commodity) sum-to-zero invariant holds.

   This is the kontor-side counterpart to ADP's GLI balancing row
   (note 83 §2.3). The consumer NORMALLY routes `NET PAY` to its own
   `:net-pay-payable` account, in which case the explicit postings
   already balance per ledger and no balancing leg is needed. But in
   the parallel-ledger case (PTO accrual on `:us-gaap` only, 401(k)
   match on `:us-gaap` only) the per-ledger balance can diverge and
   a balancing leg keeps the substrate happy."
  [postings accounts commodity]
  (let [by-ledger (group-by :kontor.posting/ledger postings)]
    (for [[ledger ps] by-ledger
          :let [sum (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                              (.add a ^BigDecimal amount))
                            0M ps)
                non-zero? (not (zero? (.signum sum)))]
          :when non-zero?]
      {:kontor.posting/account (or (:balance-clearing accounts)
                            (:net-pay-payable accounts))
       :kontor.posting/amount (neg-bd sum)
       :kontor.posting/commodity commodity
       :kontor.posting/narration "Per-ledger balancing leg (ADP GLI parallel-ledger split)"
       :kontor.posting/ledger ledger})))

(defn build-payroll-postings
  "Public functional entry — pure (no providers). Returns a vector of
   posting maps ready for `kontor.posting/build-transaction-tx-data`.

   Required keys:
     :facts             — vector of PayrollFacts (from `parse-and-classify`)
     :accounts          — consumer's wage-type → GL account ref map
     :ledgers-map       — {:us-gaap <ledger-eid>  :us-tax <ledger-eid> …}
     :commodity         — :commodity ref (USD typically)

   Optional keys:
     :state-allocations — keyed by employee ref → {state-code → percent}.
                          Allows the consumer to override the parser's
                          per-row state for hybrid / multi-state employees.
                          When absent the per-row `:state` (from
                          reference-3) is used 100%."
  [{:keys [facts accounts ledgers-map commodity state-allocations]}]
  (when-not (seq ledgers-map)
    (throw (ex-info "UsPayrollPostingBuilder: :ledgers-map is required"
                    {:hint "Pass at least {:us-gaap <ledger-eid>} (or {:us-gaap <eid> :us-tax <eid>})"})))
  (let [explicit
        (vec
         (mapcat (fn [fact]
                   (let [emp (:employment fact)
                         per-employee-allocs
                         (when state-allocations (get state-allocations emp))]
                     (mapcat (fn [c]
                               (one-component-postings
                                {:component c
                                 :accounts accounts
                                 :ledgers-map ledgers-map
                                 :commodity commodity
                                 :state-allocations per-employee-allocs}))
                             (:components fact))))
                 facts))
        balancing (vec (balancing-postings-by-ledger explicit accounts commodity))]
    (into explicit balancing)))

;; ============================================================================
;; UsPayrollPostingBuilder record
;; ============================================================================

(defrecord UsPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ facts {:keys [accounts ledger ledgers-map state-allocations]}]
    (let [commodity (:commodity opts)
          ;; Allow either {:ledgers-map {:us-gaap eid …}} OR a legacy
          ;; single-ledger {:ledger <eid>}. The single-ledger case
          ;; routes everything onto that one ledger and ignores any
          ;; per-component :ledgers sets.
          lmap (cond
                 (seq ledgers-map) ledgers-map
                 ledger {:default ledger}
                 :else
                 (throw (ex-info "UsPayrollPostingBuilder: pass :ledgers-map or :ledger"
                                 {})))]
      (when-not commodity
        (throw (ex-info "UsPayrollPostingBuilder constructed without :commodity in opts"
                        {})))
      (build-payroll-postings
       {:facts facts
        :accounts accounts
        :ledgers-map lmap
        :commodity commodity
        :state-allocations state-allocations}))))
