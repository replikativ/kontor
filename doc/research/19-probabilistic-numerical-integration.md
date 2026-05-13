# 19 — Probabilistic / numerical / simulation integration

Research note exploring how `kontor` composes with the user's sibling Clojure projects (simmis, stratum, raster, spindel, anglican) to enable simulation-capable, probabilistic, computationally rich business modeling. Output is a design map, not a commitment to code; concrete ADRs follow if and when the design is locked.

Date: 2026-05-13. Sources: local repos under `/home/christian-weilbach/Development/`, vendor docs (Anaplan, Vena, Cube, Mosaic, AnyLogic, Pyro, Gurobi), regulatory papers (Basel II.5, FRTB, Solvency II), recent academic literature on RL for credit policy and dynamic pricing.

## 1. Capability map (sibling-repo intake for kontor)

Each entry: what kontor can consume, the seam it plugs into, and license caveats. Repos read in full READMEs + CLAUDE.md + selected source.

### 1.1 simmis (`/home/christian-weilbach/Development/simmis`, EPL-2.0)

**Provides.** A categorical schema substrate where `Object`s (types like `S/Person`, `S/Marriage`), `Morphism`s (typed relations with source/destination), `Functor`s (type → component mapping), and `Categories` are all first-class queryable data. Runtime schema evolution: add new types and properties without code changes. Built on datahike + kabel + UIx. The whole shape is "category-theoretic CRM/PIM."

**What kontor consumes.**
- **Master-data dimension store.** kontor's `:partner` / `:project` / `:cost-center` / `:product` namespaces sit naturally as simmis `Object`s with `Morphism`s pointing at one another. Showcase 4 (multi-entity intercompany + cost-center + approval-policy, committed `9483595`) already names the entities; simmis would supply the runtime-extensible "Notion-like" admin surface for them. Kontor stays headless (ADR-010); simmis is one of the consumer UIs.
- **Scenario-bound entities.** A planning workspace is a simmis `Category` populated with `:Scenario`, `:Assumption`, `:Driver` objects, each linked back to kontor accounts via typed morphisms (`Driver/output-account → kontor account`). The categorical layer enforces that an assumption driving "DE revenue" must point at an account whose `:account/entity` is a DE entity — exactly the kind of constraint Salesforce's `Validation Rules` capture but declaratively (research note 10, item 2 — "the Salesforce admin-tier visual rule editor as the largest remaining gap (simmis opportunity)").
- **Same DB instance.** ADR-002 already permits namespace cohabitation in one datahike DB (`:account/* :partner/* :invoice/* …`). Adding `:object/* :morphism/* :category/*` is the same pattern — every attribute is namespaced. No second datahike instance needed.

**Seam.** simmis lives one layer above kontor. kontor doesn't depend on simmis. simmis depends on kontor when an `Object` instance corresponds to a real account / partner.

**License.** EPL-2.0 — compatible with kontor's EPL-1.0. Bidirectional integration is safe.

### 1.2 stratum (`/home/christian-weilbach/Development/stratum`, Apache-2.0)

**Provides.** SIMD-accelerated columnar SQL engine for the JVM. Every table is a branchable copy-on-write value (`st/fork ds` is O(1), structural sharing). Persists to konserve (the same key-value abstraction datahike uses), supports time-travel (`st/load store name {:as-of commit-uuid}`), and already plugs into datahike as a secondary index (`PersistentColumnIndex` with the same `IAuditable` shape — `src/stratum/audit.clj` documents the protocol as "intentionally identical to datahike's"). Tablecloth/`tech.ml.dataset` interop is bidirectional and zero-copy when array-backed. Includes built-in `CREATE MODEL` / `ANOMALY_SCORE` isolation-forest analytics, full window functions, ASOF JOIN, and statistical aggregates (`STDDEV`, `VARIANCE`, `CORR`, `MEDIAN`, `APPROX_QUANTILE`).

**What kontor consumes.**
- **The simulation working set.** Pulling all postings against a 12-month forecast horizon for 50,000 customers, partitioning by aging bucket, and running matrix arithmetic over the result is what stratum was built for. We materialize a kontor query result (`kontor.ledger/postings-against-account` × many accounts → flat seq of maps) into a `StratumDataset` and operate on it columnarly.
- **Scenario branching at the table level.** `st/fork` mirrors what datahike's `branch!` does at the EAVT layer. A "scenario branch" combines them: fork the datahike DB (record-level what-if), fork the stratum dataset (analytical what-if), evolve them independently, present them side-by-side via `kontor.balance/account-balance` with `:as-of-tx` taking the branched commit. The "expensive part" of a 1M-row forecast lives in stratum, not in datahike.
- **ASOF JOIN for valuation snapshots.** Kontor's bitemporal queries already need "what was the FX rate on the valid-date of each posting." That's the canonical ASOF JOIN shape (`l.posted-at >= r.rate-effective-at`). Stratum's parallel two-pointer merge is what makes this fast.
- **Window functions for trial-balance-by-period.** `SUM(amount) OVER (PARTITION BY account ORDER BY tx-date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` produces running balances natively; matches `kontor.trial/trial-balance` shape.

**Seam.** stratum becomes an optional analytical companion. Kernel queries return Clojure maps as today; an opt-in `kontor.analytics` namespace converts these into `StratumDataset`s for heavy work. Kernel stays single-dep on datahike.

**License.** Apache-2.0 — compatible with EPL-1.0 in both directions. Integration is safe.

### 1.3 raster (`/home/christian-weilbach/Development/raster`, MIT)

**Provides.** `deftm` + parallel combinators (`par/map`, `par/reduce`, `par/scan`) that compile to JVM bytecode (SIMD via Java Vector API), OpenCL, Vulkan SPIR-V, or Intel Level Zero — from the same Clojure source. Auto-differentiation (forward Dual + reverse mode). `raster.sci.distributions` (Normal, Beta, Gamma, Poisson, …), `raster.sci.stats` (t-test, KS, correlation), `raster.sci.optim` (L-BFGS, Nelder-Mead, Newton, gradient descent), `raster.ode` (Euler, RK4, DP5, Tsit5; SDE via Euler-Maruyama), `raster.linalg` (LU, Cholesky, SVD, Krylov methods), `raster.dl` (NN layers, Adam, einops). ABM module is GPU-compiled. Performance: competitive with Julia DiffEq and JAX CPU on the benchmarks in the README.

**What kontor consumes.**
- **The numerical kernel for trajectory math.** Given N sampled paths × T months × K accounts, the per-trajectory arithmetic (running balance, FX conversion, recognition curves) compiles to a single JVM method with zero heap allocations via `compile-aot`. This is what makes 10,000-path Monte Carlo on a laptop feasible in Clojure.
- **Probability primitives.** When an Anglican query asks for `(sample (normal mu sigma))`, the underlying density evaluation can route to `raster.sci.distributions` if we want to share one canonical PDF/CDF/quantile implementation across the substrate. Today Anglican has its own; that's fine — but if we want gradients of the log-likelihood (for HMC / SVI), raster's reverse-mode AD does this and Anglican's doesn't natively.
- **ODE/SDE for continuous-time models.** Inventory-replenishment models, exponential-decay collection curves, FX drift (geometric Brownian motion) are all SDEs. Raster solves them with adaptive timesteps and AD-friendly state.
- **Optimization layer.** Calibrating priors from historical data is L-BFGS over a negative-log-likelihood; raster ships it. Optimal-control problems (when should we issue a dunning letter? — see §4) reduce to nonlinear programming, which raster's optim module solves directly or via gradient-aware sampling.

**Seam.** raster is the "what JAX is to Python ML" layer. kontor pipes posting time-series into `(Array double)` (a kontor-side adapter — three lines), raster operates on it, results flow back.

**License.** MIT — compatible. Optional companion artifact.

### 1.4 spindel (`/home/christian-weilbach/Development/spindel`, Apache-2.0)

**Provides.** Cached reactive spins with automatic dependency tracking, mutable signals with delta tracking, copy-on-write runtime forking (O(1)) with overlay backends, fork-safe atoms, glitch-free FRP via topological ordering. The CPS transformation (`partial-cps`) is the same machinery Anglican's `cloroutine_*` files build on (CLAUDE.md: "cloroutine library provides a more general CPS transform … Anglican uses explicit CPS transformation in trap.clj for sample/observe calls"). Spindel's `engine.context` is the canonical runtime; serialize/deserialize lets you snapshot a whole reactive graph.

**What kontor consumes.**
- **The recomputation engine for scenarios.** When an analyst changes an assumption (collection-probability prior), spindel marks the dependent spins dirty and re-executes only the path that depends on it. This is exactly the FP&A iteration loop ("adjust driver, watch the cash chart update") that Anaplan, Vena, and Adaptive sell as their core value prop — except theirs is row-oriented spreadsheets and ours is a deterministic dependency graph.
- **Scenario forking primitive.** `(ctx/fork-context ctx-main)` gives an O(1) isolated execution branch. Compose with datahike's `branch!` and stratum's `st/fork`: one user action ("create scenario 'aggressive-collections'") produces three coordinated forks (datahike branch + stratum branch + spindel context), which collectively are the "scenario branch primitive" called out in §5.
- **Streaming postings as a signal.** A long-running inference job (online Bayesian updating of collection probabilities) attaches to a `signal` whose value is the latest tx-id processed. Each new posting fires a delta; the inference spin updates posteriors incrementally. This is the same machinery simmis uses for live UI updates.
- **CPS interop with Anglican.** Both use CPS transformation. Spindel's effect system (`await`, `track`, `yield`) is extensible by registering effect handlers (`effects/register-effect-by-symbol!`); Anglican's `sample` / `observe` are CPS effects in everything but name. A future cleanup is to let an Anglican query run *inside* a spindel spin so `sample` participates in the reactive graph and you can probe a posterior at arbitrary points without re-running.

**Seam.** spindel is the "incremental view maintenance" / "differentiable spreadsheet" layer. Sits between kontor (source of truth) and the UI (consumer). kontor stays unaware of it.

**License.** Apache-2.0 — compatible.

### 1.5 anglican (`/home/christian-weilbach/Development/anglican`, GPL-3.0 — see §6)

**Provides.** Mature probabilistic programming system in Clojure. `defquery` blocks with `sample` / `observe` / `predict` special forms. Inference algorithms: importance sampling, Lightweight Metropolis-Hastings (LMH), Sequential Monte Carlo (SMC, `pfilter`), Particle Gibbs (`pgibbs`), Particle Cascade (`pcascade`), iPMCMC, almost everything in the textbook. Newer code (per CLAUDE.md and recent commits) adds a `Measure` abstraction, missionary/cloroutine integration, and HMC.

**What kontor consumes.**
- **The inference engine.** Any business model that says "given observed historical data, infer posterior over unknown driver / probability / latency" is exactly what Anglican solves. Conjugate cases (Beta-Binomial for collection rates) could be done by hand, but Anglican lets us write the model declaratively and try multiple inference algorithms without rewriting the model.
- **The model DSL.** A Clojure DSL with `sample` and `observe` is more pleasant than handwritten MCMC kernels. The user can write the same model on paper and in code (Pyro and Stan offer the same proposition outside the JVM — Anglican brings it to a JVM-Clojure shop).
- **Particle Gibbs over time-series.** AR/PG-style algorithms are the right fit for state-space models (e.g., latent demand → observed orders → recognized revenue). The published examples cover state-space.

**Seam.** Anglican is the inference companion artifact. Probabilistic models live in their own namespaces (`kontor-forecast.cashflow`, `kontor-forecast.demand`) and read kontor data via existing query helpers. Anglican does not get pulled into the kernel.

**License — important.** **GPL-3.0.** Linking Anglican into a kontor-derived application makes the application GPL-3.0 (FSF interpretation). This means an Anglican-using companion artifact (`kontor-probabilistic` or similar) is itself GPL-3.0, and **anyone consuming it must accept GPL-3.0 terms** — same situation as `kontor-l10n-de` if SKR03/SKR04 is sourced from Tryton (ADR-006). The kernel stays EPL-1.0 because it does *not* link Anglican; the probabilistic companion does.

If we want a permissive alternative, the JVM probabilistic landscape has:
- **Bayadera** (EPL — same author's earlier work) — GPU MCMC over Neanderthal. Smaller surface than Anglican but EPL-clean.
- **Reimplement against raster.** Raster already has distributions, AD, and optim. An MH/HMC/SMC layer on top of raster (a "tiny Anglican-shaped DSL") would be MIT/EPL-clean and would unify the numerical kernel — same `Dual` numbers feed inference and optimization.

This is an open question (§7).

## 2. The integrated story — one page

**Philosophy.** Six immutable substrates compose into a Clojure-native, simulation-capable business OS that is functional, persistent, branchable, deterministic-given-inputs, bitemporal as the input substrate, and probabilistic as the output overlay.

- **Bitemporal as input.** Every kontor read takes `(:as-of-tx, :as-of-valid)`. ADR-008 makes this the universal substrate. *Every* analytical question is "given what the books knew at TT, what was true at VT" — and that is exactly the right input to a forward-looking model.
- **Persistent values throughout.** datahike, stratum, spindel, and konserve all share copy-on-write semantics: a fork is O(1), structural sharing keeps it cheap, snapshots are content-addressed. A "scenario" is a coordinated triple-fork: `(datahike-branch, stratum-branch, spindel-context)`, all derived from one upstream commit by hash. Time travel and what-if are the same primitive.
- **Deterministic-given-inputs.** Once the random seed and the input commits are pinned, the entire simulation is reproducible. Audit trails for projections become possible — exactly what bank regulators (FRTB internal-models approach, Solvency II ORSA) demand and what Anaplan/Vena cannot offer because their cells mutate ([Basel II.5 review](https://www.bis.org/publ/bcbs148.pdf)).
- **Probabilistic as output overlay.** The GL is hard fact. Forecasts, what-ifs, anomaly scores, MDP policies are *projections* of the GL — they don't write back to it unless explicitly committed (which then becomes an auditable posted tx, e.g., booking a hedging position). The substrate enforces this asymmetry by typing: kontor returns `Money`; raster returns `(Array double)`; the conversion is one place to put a unit-of-account boundary.
- **Branchable as a first-class action.** "Create scenario 'aggressive collections'" is one user gesture that produces three forks. "Compare scenarios" is a vector of `(snapshot-commit, scenario-commit)` pairs feeding a single stratum query. "Adopt scenario" is a datahike `merge-db` (ADR-003 / research-note 02) — i.e., the assumption changes become themselves auditable postings.

**What this looks like.** kontor is the trusted ledger. simmis is the schema/admin/dimension surface. stratum is the analytical engine. raster is the numerical kernel. spindel is the reactive recomputation engine. anglican (or its raster-native sibling) is the inference engine. They all share commodity primitives — content-addressed hashing (konserve), persistent collections (Clojure), CPS (partial-cps), copy-on-write — so composition is incidental rather than glue-heavy.

## 3. Worked example — 12-month rolling cash-flow forecast

**Scenario.** "Given the books as of today (tx-time 2026-05-13), partitioned by partner and aging bucket, project the cash balance fan-chart for the next 12 valid-months. Re-run when the analyst tweaks a collection-probability prior."

### 3.1 Data flow

```
kontor.balance / kontor.aging        — actual postings up to today
        ↓ (Clojure maps)
kontor.analytics/load-into-stratum   — columnar materialization
        ↓ (StratumDataset)
prior-data (aging buckets × cohorts) — input to anglican model
        ↓
anglican.smc / anglican.lmh          — posterior over collection-rate latency
        ↓ (N posterior samples)
raster.par/map + .par/reduce         — trajectory arithmetic on (N × T) array
        ↓
stratum window-function aggregate    — percentile bands by month
        ↓
spindel signal output → UI fan chart — reactive on assumption changes
```

### 3.2 Pseudocode (with concrete fn names)

```clojure
(ns kontor-forecast.cashflow
  (:require [kontor.balance :as bal]
            [kontor.aging :as aging]
            [kontor.ledger :as ledger]
            [kontor.analytics :as kana]              ; NEW — §5
            [stratum.api :as st]
            [raster.par :as par]
            [raster.sci.distributions :as rdist]
            [anglican.emit :refer [defquery]]
            [anglican.runtime :refer [sample observe normal beta gamma]]
            [anglican.core :as ainf]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

;; ---- 1.  Pull the input substrate from kontor (bitemporal as input) ----
(def as-of {:as-of-tx    #inst "2026-05-13"
            :as-of-valid #inst "2026-05-13"})

(defn open-receivables-by-cohort [conn]
  ;; Group open AR by (partner, aging-bucket) using existing helpers.
  (->> (aging/aged-open-receivables conn as-of)
       (group-by (juxt :partner :aging-bucket))))

(defn historical-collection-events [conn lookback-days]
  ;; For each closed invoice, the (issue-date, paid-date, amount) triple.
  ;; Walks reconciliation tx-meta; uses existing `ledger/postings-against-account`.
  (ledger/closed-receivables conn (assoc as-of :lookback-days lookback-days)))

;; ---- 2.  Materialize into stratum (columnar working set) ----
(def history-ds
  (kana/postings->stratum (historical-collection-events conn 730)
                          :cols [:partner :bucket :issue-date :paid-date :amount]))

;; ---- 3.  Define the probabilistic model (anglican) ----
(defquery collection-latency-model
  [history]                                          ; per-bucket aggregate
  (let [;; Per-bucket priors on (mean collection latency, payment-probability)
        latency-mu    (sample (gamma 5.0 1.0))      ; days
        latency-sd    (sample (gamma 2.0 1.0))
        pay-prob      (sample (beta 8.0 2.0))]      ; ~0.8 prior
    (doseq [{:keys [observed-latency observed-paid?]} history]
      (when observed-paid?
        (observe (normal latency-mu latency-sd) observed-latency))
      (observe (flip pay-prob) observed-paid?))
    {:latency-mu latency-mu :latency-sd latency-sd :pay-prob pay-prob}))

(defn posterior-by-bucket [history-ds]
  ;; SMC with N particles; returns N posterior parameter tuples per bucket.
  (into {}
        (for [bucket [:0-30 :31-60 :61-90 :90+]
              :let [rows (st/q "SELECT * FROM t WHERE bucket=?" {:bucket bucket}
                               {"t" history-ds})]]
          [bucket (ainf/doquery :smc collection-latency-model [rows]
                                :number-of-particles 1000)])))

;; ---- 4.  Sample N trajectories (raster — the numerical kernel) ----
(defn sample-trajectory
  "One trajectory: simulate which receivables resolve when, return cash by month."
  [posterior-samples open-ar fx-drift season-curve]
  (par/map [t 12]
    (par/reduce + 0.0 [i (alength open-ar)]
      (let [{:keys [partner bucket amount due-date]} (aget open-ar i)
            params   (sample-one (get posterior-samples bucket))
            t-paid   (sample-collection-time params due-date)
            paid?    (= t (month-of t-paid))]
        (if paid?
          (* amount (fx-drift-at fx-drift t))
          0.0)))))

(defn run-mc [posterior open-ar n-trajectories]
  ;; Returns (n-trajectories × 12) double matrix.
  (par/map [n n-trajectories]
    (sample-trajectory posterior open-ar (sample-fx-drift) season-curve)))

;; ---- 5.  Aggregate to percentile fan chart (stratum window/quantile) ----
(defn fan-chart [trajectory-matrix]
  (let [ds (st/from-arrays {:month (long-array (mapcat #(range 12) (range)))
                            :cash  (double-array (flatten trajectory-matrix))})]
    (st/q {:from ds
           :group [:month]
           :agg   [[[:approx-quantile :cash 0.05] :p5]
                   [[:approx-quantile :cash 0.25] :p25]
                   [[:approx-quantile :cash 0.5]  :p50]
                   [[:approx-quantile :cash 0.75] :p75]
                   [[:approx-quantile :cash 0.95] :p95]]
           :order [[:month :asc]]})))

;; ---- 6.  Reactive layer (spindel) — re-run on assumption change ----
(def n-trajectories-sig (sig/signal 1000))
(def pay-prob-prior-sig (sig/signal {:alpha 8.0 :beta 2.0}))

(def cashflow-forecast
  (spin
    (let [{:keys [new]} (track pay-prob-prior-sig)
          n             (:new (track n-trajectories-sig))
          posterior     (posterior-by-bucket history-ds)   ; cached
          open-ar       (open-receivables-by-cohort conn)
          trajectories  (run-mc posterior open-ar n)]
      (fan-chart trajectories))))
```

### 3.3 Seams that already exist vs. need to be added

| Seam | Exists in kontor | Needs to be added |
|---|---|---|
| `kontor.balance/account-balance` with `:as-of-valid` / `:as-of-tx` | ✓ ADR-008, `balance.clj:89` | — |
| `kontor.aging/aged-open-receivables` (per-partner aging bucket) | ✓ `aging.clj` | extend to return cohort/bucket as flat seq |
| `kontor.ledger/closed-receivables` with lookback | partial — `ledger/postings-against-account` returns flat seq | new helper joining payment-application tx-meta |
| `kontor.analytics/postings->stratum` (Clojure-map → StratumDataset) | ✗ | new — `kontor-analytics` companion artifact |
| Probabilistic model (Anglican) | ✗ kernel doesn't ship | new — `kontor-forecast` companion (GPL-3.0 if Anglican) |
| Trajectory arithmetic (raster) | ✗ | new — companion module |
| Fan-chart aggregation (stratum quantile) | ✓ `APPROX_QUANTILE` in stratum SQL | adapter for `Money`-flavored columns |
| Reactive recomputation (spindel) | ✗ | UI-side concern — lives in beleg/simmis |
| Scenario branching primitive (triple-fork) | partial — datahike `branch!` exists | unify with stratum + spindel — see §5 |

## 4. Other workflows this opens up

### 4.1 Demand forecasting (revenue, churn, FX)

Hierarchical Bayesian model on monthly revenue per (entity, product-category, market). Priors borrow strength across categories; observed revenue (kontor postings against revenue accounts, partitioned by `:posting/cost-center` or `:posting/project` per ADR-022) updates the posteriors. SMC handles the non-conjugate trajectory shape. Output: predictive distribution of next-quarter revenue, with bands. Tied to ASC 606 / IFRS 15 deferred revenue (research note 10) — projected revenue × recognition curve gives the projected income statement, not just the cash chart.

### 4.2 AR-collection optimization as a Markov decision process

State = `(invoice, days-overdue, partner-history, escalation-step)`. Actions = `{:no-op, :soft-reminder, :hard-letter, :phone, :legal}`. Reward = expected collected amount minus cost-of-action minus damage-to-relationship-prior. The 1972 Bierman-Hausman *Management Science* paper [Selecting Optimal Credit Control Policies](https://pubsonline.informs.org/doi/10.1287/mnsc.18.10.B519) is the canonical reference; 2024–2025 RL literature (Q-learning, DQN, offline RL) applies it to dynamic pricing and credit policy ([Springer 2025 offline RL for network pricing](https://link.springer.com/article/10.1007/s00291-025-00821-2)). The substrate is ready: kontor already has aging buckets (`aging.clj`), promise-to-pay state machines (Stage L research note 15), and reconciliation telemetry. Adding an MDP solver (value iteration on a small state space; raster supplies the linear algebra) gives an *optimal collections policy* per customer cohort. This is exactly what HighRadius / Sidetrade / Quadient charge enterprise licenses for.

### 4.3 Inventory replenishment as discrete-event simulation

Kontor models the financial side of inventory (cost-of-goods, valuation methods); the operational side (replenishment cycle, lead-time, stockout) lives one layer up. A discrete-event sim with arrivals/departures (raster ODE/SDE for continuous-time approximations; a Clojure-native event-queue for true DES — spindel's `gen-aseq` is a good shape) generates an inventory trajectory; each event that *triggers* a posting (receipt → DR Inventory CR GR/IR; issue → DR COGS CR Inventory) feeds back into kontor via `posting/build-transaction`. The simulation is a probability distribution over future posting streams — exactly what AnyLogic does ([AnyLogic supply chain modeling](https://www.anylogic.com/supply-chains/)) but in-process and without the impedance mismatch of "export → simulate → import."

### 4.4 FX hedging strategy backtest

Branch the datahike DB at a past commit (say, 12 months ago). Replay actual postings month-by-month, layering each candidate hedging strategy as an alternative branch. The FX rate path is the input ("what if EUR/USD had behaved like sample-path-N drawn from a calibrated SDE?"). For each candidate strategy × each FX path, compute the realized P&L. Stratum's branchable datasets make the strategy alternatives cheap; raster's SDE solver supplies the FX paths. Aggregate to find the Sharpe-optimal hedge ratio. Same machinery generalizes to interest-rate hedging, commodity hedging, treasury policy.

### 4.5 M&A scenario analysis

Two kontor instances (acquirer + target) → simulate post-acquisition consolidation in a third branched DB. Multi-entity primitives (Stage J + ADR-031) already exist. The simulation overlays cost-synergy assumptions (drawn from priors), revenue-cannibalization, integration cost timelines. Output: NPV distribution under different deal structures. Comparable to Wall-Street IB DCF models, except the analyst doesn't author a 2,000-row Excel — they write a 30-line Anglican query against the actual books.

### 4.6 Audit-anomaly detection via Bayesian model

For each posting, compute a posterior probability under a model trained on prior posting patterns (account pair frequency, amount distribution conditional on (account, partner, period), tx weekday/hour distribution). Low-probability postings flagged for review. The classic Benford's-law check is a degenerate case. Stratum already ships isolation-forest analytics built-in (`CREATE MODEL ... ANOMALY_SCORE('model')`) — pair that with a Bayesian model for tx-level scoring and you have a continuous auditor's-eye on the GL. Differs from Odoo / Tryton / SAP, where audit is a manual or rules-based affair (research note 13).

## 5. What's missing from kontor today (concrete seams to add)

These are *not* a commitment to add — only a list of the cleanest extensions that the workflows above would benefit from. Each maps to a candidate ADR if/when the work happens.

### 5.1 `kontor.analytics` — columnar materialization companion

A new artifact (`kontor-analytics`) that converts kontor query results into stratum datasets. Single function family: `(postings->stratum results)`, `(balance-history->stratum conn account-eids opts)`, `(trial-balance->stratum conn opts)`. Lives outside the kernel; depends on stratum + kontor. License: EPL-1.0 (matches kontor; stratum is Apache-2.0 so this is fine).

### 5.2 Scenario-branch primitive — coordinated triple-fork

One function: `(create-scenario! conn scenario-name)` returns `{:datahike-branch ..., :stratum-branch ..., :spindel-context ...}` — all three forked from the current root, all three tagged with the same `scenario/uuid`. Three corresponding helpers: `(switch-scenario ctx)`, `(merge-scenario! ctx target)`, `(discard-scenario! ctx)`. Could be a `kontor-scenario` artifact, or a thin section of `kontor-analytics`. ADR candidate; would supersede ad-hoc branching in stage-N work.

### 5.3 "What-if posting" entity (uncommitted)

A new `:posting/state` value `:hypothetical` (peer to `:draft`, `:posted`, `:cancelled`). `:hypothetical` postings participate in projections but are excluded from official trial balance / financial statements. The sealing middleware (`sealing.clj`) refuses to seal a `:hypothetical`; a separate `(materialize! ph-postings)` flow promotes them to `:draft` if the scenario is adopted. This is the operational primitive for what FP&A vendors call "driver-based modeling" — except ours is type-safe and auditable.

### 5.4 Streaming postings interface for online inference

A new helper `(stream-postings conn opts)` returns a spindel `signal` that fires every new posting (or every new commit). Backed by `(d/listen conn :inference-stream ...)`. Lets an Anglican query do online Bayesian updating without re-running from scratch. Pairs with §5.5.

### 5.5 Money ↔ raster bridge

`(money-series->array ms)` and `(array->money-series arr commodity)`. Trivial wrappers; non-trivial decisions: how to handle `nil` (no posting that day) — zero-fill vs `NaN` vs an explicit mask. Should match stratum's null semantics for consistency.

### 5.6 (Optional) Permissive probabilistic kernel

A small "Anglican-shaped" DSL on raster (already has distributions, AD, optim — needs MCMC kernels). EPL-1.0 or MIT, avoiding the GPL-3.0 issue. Open question — see §7.

## 6. License + dependency considerations

The kernel is EPL-1.0 single-dep on datahike (ADR-001). Any addition must respect that.

| Sibling | License | Posture | Notes |
|---|---|---|---|
| simmis | EPL-2.0 | Companion artifact above kernel | EPL-2.0 ↔ EPL-1.0 compatible; both file-scope copyleft. |
| stratum | Apache-2.0 | Optional companion (`kontor-analytics`) | Permissive; safe to depend on from EPL artifacts. |
| raster | MIT | Optional companion (`kontor-forecast` numerical core) | MIT is the most permissive; safe everywhere. |
| spindel | Apache-2.0 | Consumer-side (UI/reactive layer) | Lives in beleg/simmis, not kontor itself. |
| anglican | **GPL-3.0** | Optional companion (`kontor-forecast`) — **the artifact itself becomes GPL-3.0** | Mirrors ADR-006 for `kontor-l10n-de` if sourced from Tryton. Consumers must opt in. |

**Verdict.**

- The kernel takes no new dependency. ADR-001 holds.
- `kontor-analytics` (stratum bridge): EPL-1.0, optional, low risk.
- `kontor-forecast` (Anglican-based): **GPL-3.0**, optional. Document the license boundary loudly. Mirror ADR-006's pattern: per-companion license, transparent at the artifact boundary.
- `kontor-forecast` (raster-based): **MIT or EPL-1.0** if we re-implement MCMC over raster. Open question.
- `kontor-scenario` primitive: EPL-1.0, optional.

**Pattern.** Companion artifacts are independent JARs with their own deps.edn and license. Consumers compose. The kernel never imports an inference engine, a SQL engine, a numerical kernel, or a reactive engine — it imports nothing but datahike.

## 7. Open questions / design choices needing user input

1. **Anglican vs. roll-our-own-on-raster.** Anglican is mature, well-documented, has a published inference algorithm catalogue, and a Wood-group lineage. The cost is GPL-3.0 contagion in any artifact that links it. Rolling a smaller MCMC layer on raster (which already has distributions + AD + optim) would be permissive, share more code with the rest of the substrate, and stay in the user's idiom (`deftm`, parallel combinators). Cost: a few weeks of work for a smaller surface. **Decision needed before any `kontor-forecast` artifact is started.**

2. **simmis as scenario-modeling UI.** Should the scenario-management UI (assumptions, drivers, sensitivity sliders, fan-chart presentation) be built in simmis (categorical, browser-based) or as a kontor-side data-only artifact? simmis's "schema-as-data" lines up beautifully with "assumption-as-data" — but it adds another moving part. The user's call.

3. **Scenario primitive scope.** Is "scenario" a triple (datahike + stratum + spindel) or quadruple (+ konserve store)? If konserve is in the loop, scenarios become full filesystem-isolatable, distributable artifacts (hand a scenario zip to a board member). If not, scenarios are in-process. Both have merit; pick one for the first iteration.

4. **Posting `:hypothetical` state — kernel or companion?** This is a substrate-level decision. Adding `:hypothetical` to `posting.clj` is a kernel change with a tiny surface but big semantic implication (what's the trial balance contract under hypotheticals?). The alternative is keep kernel pure and model what-ifs as a separate `:forecast-posting` namespace owned by a companion artifact. Cleaner separation but more boilerplate at the seams.

5. **Streaming postings — sync or async.** spindel's `signal` is reactive but it doesn't fit naturally into a long-lived `core.async` channel pattern. datahike's `d/listen` is a callback. Picking one shape (signal vs channel vs flow) shapes how online inference and streaming forecast updates compose with the rest of the system. spindel's `gen-aseq` + pub/sub mult/pub may be the right "join point" — but should be validated with one concrete online-inference example first.

6. **Determinism guarantees for audit.** If a forecast is part of an audit submission (FRTB internal-models approach, Solvency II ORSA, IFRS 9 ECL), we need provable reproducibility. Pinning seeds + input commits is necessary. Is it sufficient? raster's GPU path is non-deterministic-by-default (kernel scheduling). For audit-grade forecasts we may need to force the SIMD/scalar path. Worth a short experiment.

7. **Where does the scenario tree merge with the GL?** When a what-if becomes a real plan ("commit the budget"), do the hypothetical postings *become* real `:draft` postings via a `(materialize-scenario! scenario)` step, or does the scenario evolve into a parallel "budget GL" that's reported alongside (not merged into) actuals? This is the difference between Anaplan's "what-if commits back" model and Adaptive's "budget is a separate cube" model. Both are valid; the choice shapes the data model.

## Cited sources

### Sibling repos (local)

- `/home/christian-weilbach/Development/simmis/README.md` and `docs/`
- `/home/christian-weilbach/Development/stratum/README.md` and `doc/dataset.md`, `doc/storage-and-indices.md`
- `/home/christian-weilbach/Development/raster/README.md`, `CLAUDE.md`
- `/home/christian-weilbach/Development/spindel/README.md`, `CLAUDE.md`
- `/home/christian-weilbach/Development/anglican/README.md`, `CLAUDE.md`, `doc/intro.md`

### Internal references

- `kontor/doc/decisions.md` ADR-001 (EPL-1.0 + single-dep), ADR-002 (cohabitation), ADR-006 (per-country licensing), ADR-008 (bitemporal queries), ADR-010 (no UI), ADR-022 (analytic dimensions), ADR-031 (multi-entity), ADR-032 (schedule primitive), ADR-039 (credit-limit primitives)
- `kontor/src/kontor/balance.clj:89` (account-balance with bitemporal opts)
- `kontor/src/kontor/aging.clj` (aged-open-receivables)
- `kontor/doc/research/10-business-os-companion-projects.md` (Salesforce admin-tier rule editor gap → simmis opportunity)
- `kontor/doc/research/15-stage-l-collections-research.md` (collections substrate readiness)

### External references

- [Anaplan vs. Adaptive vs. Planful vs. Vena vs. Datarails vs. Cube (Cube Software, 2026)](https://www.cubesoftware.com/blog/anaplan-vs-adaptive-vs-planful-vs-vena-vs-datarails-vs-cube)
- [Best FP&A Software Solutions, Tools & Platforms for 2026 (Vena)](https://www.venasolutions.com/blog/best-fpa-software-tools)
- [Choosing Enterprise Cash Flow Forecasting Software (Farseer, 2026)](https://www.farseer.com/blog/cash-flow-forecasting-software/)
- [Financial forecasting with probabilistic programming and Pyro (Alex Honchar, Medium)](https://alexhonchar.medium.com/financial-forecasting-with-probabilistic-programming-and-pyro-db68ab1a1dba)
- [Pyro (Uber AI / Broad Institute) — pyro.ai](https://pyro.ai/)
- [Turing.jl: A General-Purpose Probabilistic Programming Language (ACM TPML)](https://dl.acm.org/doi/10.1145/3711897)
- [Probabilistic programming — Wikipedia](https://en.wikipedia.org/wiki/Probabilistic_programming)
- [AnyLogic Operations and Supply Chain Simulation](https://www.anylogic.com/resources/books/operations-and-supply-chain-simulation-with-anylogic/)
- [AnyLogic Supply Chain Simulation Software](https://www.anylogic.com/supply-chains/)
- [Discrete-Event Modeling — AnyLogic](https://www.anylogic.com/use-of-simulation/discrete-event-simulation/)
- [Revisions to the Basel II market risk framework (BIS, BCBS-148)](https://www.bis.org/publ/bcbs148.pdf)
- [Liquidity stress tests for banks — range of practices (BIS FSI, 2024)](https://www.bis.org/fsi/publ/insights59.pdf)
- [Why is the FRTB Expected Shortfall Calculation Designed as It Is? (Bank Policy Institute)](https://bpi.com/why-is-the-frtb-expected-shortfall-calculation-designed-as-it-is/)
- [Designing Effective Macroprudential Stress Tests (IMF WP/15/146)](https://www.imf.org/external/pubs/ft/wp/2015/wp15146.pdf)
- [Use of VaR Techniques for Solvency II, Basel II and III (ResearchGate)](https://www.researchgate.net/publication/300504959_Use_of_Value-at-Risk_VaR_Techniques_for_Solvency_II_Basel_II_and_III)
- [A Markov Decision Model for Selecting Optimal Credit Control Policies (Bierman & Hausman, Management Science 1972)](https://pubsonline.informs.org/doi/10.1287/mnsc.18.10.B519)
- [A Reinforcement Learning Approach to Dynamic Pricing (IJSAT 2025)](https://www.ijsat.org/papers/2025/4/9558.pdf)
- [Multi-Agent Reinforcement Learning for Dynamic Pricing in Supply Chains (arXiv 2025)](https://arxiv.org/html/2507.02698v1)
- [Improving network dynamic pricing policies through offline reinforcement learning (OR Spectrum 2025)](https://link.springer.com/article/10.1007/s00291-025-00821-2)
- [Deep Reinforcement Learning for Dynamic Pricing and Ordering Policies in Perishable Inventory Management (MDPI Applied Sciences 2025)](https://www.mdpi.com/2076-3417/15/5/2421)
- [Gurobi — Linear Programming in Operations Research](https://www.gurobi.com/resources/linear-programming-in-operations-research-with-gurobi/)
- [Google OR-Tools](https://developers.google.com/optimization/)
- [MIP Solvers Unleashed: A Beginner's Guide to PuLP, CPLEX, Gurobi, OR-Tools, Pyomo (OR Bit, Medium)](https://medium.com/operations-research-bit/mip-solvers-unleashed-a-beginners-guide-to-pulp-cplex-gurobi-google-or-tools-and-pyomo-0150d4bd3999)
