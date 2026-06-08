# kontor-hr

HR + payroll substrate for `kontor` — `:person` + `:employment` +
`:compensation` + `:department` + `:pay-period` + `:payroll-run` +
`:consent/*`, all running through ADR-067 `kontor.process` and the
ADR-075 `PayrollProvider` trio.

## What it does

Payroll across 11 jurisdictions plus HR primitives the kernel needed
to host. The substrate ships the orchestration shape; per-country
adapters (`modules/payroll-{de-datev,us-adp,ca,fr,au,br,mx,in,jp,cn,
at}`) bring the engine-export parsers + filing emitters.

- **`:person`** — global, employment-independent identity (ADR-075).
  Distinct from `:partner` (kontor-partner's `:person` is a CRM
  shape; this one carries HR discipline). One `:person` may have N
  concurrent `:employment` rows across different employing entities.
  `:person/kind ∈ #{:employee :contingent :applicant :retiree
  :board-member :intern}`.
- **`:employment`** — the multi-employer-style relationship (`:person` ↔
  `:entity`). Carries `:work-time-fraction` (a part-time fraction
  in [0, 1]), `:work-relationship-kind`, `:exempt-flag` (US FLSA),
  `:fulltime-flag`, `:manager` (ref to another `:employment`, NOT
  `:person`), `:department`, `:contract-doc` ref. ADR-031
  multi-entity scope; one person → N employments. Re-hire = new
  `:employment` row at a later `:start-date`; the prior stays
  `:terminated` as audit.
- **`:compensation`** — separated entity. Has N
  `:compensation-component` rows (a multi-cardinality bag — base
  salary, allowances, bonuses, employer-side benefit cost). `set-
  compensation!` writes a new envelope; `supersede-compensation!`
  records an effective-dated supersession so the prior envelope is
  preserved bitemporally; `current-compensation` resolves the
  in-force envelope at `:as-of`.
- **`:department`** — hierarchical (`:department/parent`) +
  re-parentable.
- **`:pay-period`** — the recurring period frame (weekly,
  bi-weekly, semi-monthly, monthly). Owns its own status machine
  (`:pay-period/state ∈ #{:open :computing :computed :paid :closed}`).
- **`:payroll-run`** — the per-(pay-period, provider) execution
  record, with `:control-total-gross` / `:control-total-net` for
  reconciliation.
- **`PayrollProvider` trio** (`kontor.payroll-provider`, ADR-075):
    - `PayrollComputeProvider/compute-payroll` → vector of
      `PayrollFacts` (per employment: gross / net / components)
    - `PayrollPostingBuilder/build-postings` → vector of posting
      maps wrapped into one `:transaction` via
      `kontor.posting/build-transaction-tx-data`
    - `PayrollEmitProvider/emit-payroll-events` → vector of
      `:audit-doc` rows (the regulator filings, the engine
      receipt, etc.)
- **`run-payroll!` orchestrator** (`kontor.hr.payroll`). One atomic
  `kontor.process` tx: compute → build postings → emit events →
  create `:payroll-run`. The `check-facts` invariant verifies each
  `PayrollFacts` map: gross = Σ positive employee-side components,
  net = gross + Σ negative deductions (1-cent rounding slack;
  engines may round differently than accumulated sums); employer-
  side components have their own posting legs but do NOT
  participate in gross/net.
- **`:consent/*`** (`kontor.hr.consent`, ADR-094 — added in
  commit `a247fb0`). Per-(subject, scope) consent records keyed
  by `:audit-doc/category`. Bitemporal: `(d/valid-at db T)`
  answers "what was the legal basis at T?". Withdrawal does NOT
  retroactively invalidate prior processing (processing under
  prior consent remains lawful for the window it was in force —
  the regulator-aligned semantic). Kontor never *enforces*
  consent — the slot is descriptive; consumer policy layers
  (kontor-people-record, MCP agent tools, DSAR builders) read
  `:consent` before deciding. Per ADR-094 §6, the project
  refuses to ship a kernel-side enforcer for the `:ai-act-
  incompatible` legal-basis marker.
- **DSAR extension** (`kontor.hr.dsar`). Registers an extension
  collector with `kontor.dsar` so the kernel-canonical
  `kontor.dsar/collect` walk reaches HR — given a `:partner` with
  `:partner/person` set, walks the linked `:person` + its
  `:employment`s + `:compensation`s + `:payroll-run`s +
  contract docs. Bitemporal via `:as-of-tx`.
- **Two-axis `:audit-doc/category`** (legal-doctrine ×
  subject-matter) + `:retention-policy/category` are kernel
  attrs added by ADR-075 — everything else lives in this module.

## When to use it

- Tracking employees + their employments / compensations / managers
- Multi-entity payroll (a single `:person` with employment in DE
  + US subsidiaries)
- Consent capture for HR-PII processing (ADR-094)
- Running payroll through any of the 11 per-country adapters
- DSAR builders for "everything HR holds about person X"

When NOT to use it:
- Vendor / customer master data → `kontor-partner`
- Performance reviews / career history / promotions →
  `kontor-people-record` (consent-gated overlay on `kontor-hr`)
- Per-country payroll engine integration → `modules/payroll-<cc>/`
- US sales tax → not HR

## Load-bearing ADRs

- [ADR-075](../../doc/decisions.md) — Stage R substrate:
  `:person` / `:employment` / `:compensation` schema +
  multi-cardinality `:compensation-component` + `:pay-period` +
  `:payroll-run` + the `PayrollProvider` trio + two-axis
  `:audit-doc/category` (kernel-side) +
  `:retention-policy/category` (kernel-side)
- [ADR-094](../../doc/decisions.md) — Employee-monitoring posture:
  `:consent/*` schema + the substrate-neutral discipline (no
  kernel-side enforcement of `:ai-act-incompatible` markers)
- [ADR-031](../../doc/decisions.md) — Multi-entity scope: one
  `:person` with N `:employment`s across N `:entity`s
- [ADR-067](../../doc/decisions.md) — `kontor.process` orchestration
  (`run-payroll!` is the canonical multi-step composition)
- [ADR-068](../../doc/decisions.md) — `*-tx-data` builder + `!`
  wrapper through `kontor.validation/transact-with-validation`
- [ADR-052](../../doc/decisions.md) — kernel `kontor.dsar/collect`
  walk; this module extends it via `register-extension-collector!`

## Key namespaces

- `kontor.hr.schema` — `:person/*`, `:employment/*`,
  `:compensation/*`, `:compensation-component/*`,
  `:department/*`, `:pay-period/*`, `:payroll-run/*`,
  `:consent/*` + status-transition seeds + approval-policy seeds
  + (own) `install!`
- `kontor.hr.core` — `install!` (the canonical entry — calls
  `schema/install!` + registers the DSAR extension collector) +
  convenience resolvers (`person-by-external-id`,
  `employment-by-code`, `pay-period-by-code`)
- `kontor.hr.person` — `create-person!` + `*-tx-data`
- `kontor.hr.employment` — `hire!`, `terminate!`,
  `sum-work-time-fraction` + `*-tx-data` builders
- `kontor.hr.compensation` — `set-compensation!`,
  `supersede-compensation!`, `current-compensation`,
  `components-of`, `employment-current-wage`
- `kontor.hr.department` — `create-department!` + `*-tx-data`
- `kontor.hr.pay-period` — `create-pay-period!` + `*-tx-data`
- `kontor.hr.payroll` — `run-payroll!` orchestrator +
  `check-facts` sum invariant + `create-payroll-run-tx-data`
- `kontor.hr.consent` — `grant!`, `withdraw!`, `supersede!` +
  `active-at?` predicate + `for-subject` query + the bitemporal
  semantic (withdrawal doesn't retro-invalidate)
- `kontor.hr.dsar` — `collect-for-person`, `collect-employee`;
  registered as a `kontor.dsar` extension by `core/install!`

## Minimal example

```clojure
(require '[kontor.core            :as k]
         '[kontor.hr.core         :as hr]
         '[kontor.hr.person       :as person]
         '[kontor.hr.employment   :as employment]
         '[kontor.hr.compensation :as comp])

(def conn (k/create-test-db))
(hr/install! conn)        ; one-stop installer
;; ... + seed :entity, journals, accounts as needed

;; Step 1 — create a person
(person/create-person!
  conn {:external-id "alice@acme.de"
        :given-name "Alice"
        :family-name "Schmidt"
        :birth-date #inst "1990-03-15"
        :kind :employee})

;; Step 2 — hire (creates :employment + optionally attaches contract)
(employment/hire!
  conn {:code "EMP-DE-001"
        :person [:person/external-id "alice@acme.de"]
        :entity [:entity/code "acme-de"]
        :start-date #inst "2026-06-01"
        :job-title "Senior Engineer"
        :work-time-fraction 1M
        :exempt-flag false
        :contract-doc <contract-audit-doc-eid>})

;; Step 3 — set compensation envelope
(comp/set-compensation!
  conn {:employment [:employment/code "EMP-DE-001"]
        :effective-from #inst "2026-06-01"
        :commodity [:commodity/symbol "EUR"]
        :components [{:kind :base-salary :amount 8000M :frequency :monthly}
                     {:kind :meal-allowance :amount 150M :frequency :monthly}]})

;; Step 4 — payroll run (consumer provides the per-country provider trio)
(require '[kontor.hr.payroll :as payroll]
         '[kontor.payroll.de.datev :as datev])    ; or :us.adp / :ca / etc.

(payroll/run-payroll!
  conn {:code "RUN-2026-06"
        :pay-period [:pay-period/code "2026-06"]
        :entity [:entity/code "acme-de"]
        :compute-provider (datev/compute-provider {...})
        :posting-builder  (datev/posting-builder  {...})
        :emit-provider    (datev/emit-provider    {...})
        :journal [:journal/code "PAYROLL"]})
```

## What it does NOT do

- **No payroll computation.** `PayrollComputeProvider` is the
  protocol; per-country adapters bring the engine. The substrate
  bundles no rate tables, no jurisdiction CoA, no vendor
  credentials (ADR-005 / ADR-071 / ADR-075).
- **No `:ai-act-incompatible` enforcement.** ADR-094 §6 — the
  kernel refuses to ship an enforcer. Consumer policy layers
  decide what to do when a consent records that legal basis.
- **No automated performance reviews / promotions / career
  history.** That's `kontor-people-record`, which consent-gates
  itself off `kontor.hr.consent`.
- **No identity / SSO / authentication.** `:person/external-id`
  is the join key; identity provider integration is consumer-
  side.
- **No CRM / org-chart visualization / time-tracking.** Substrate
  ships the data shape; UI lives in consumers (ADR-010).
- **No retroactive consent invalidation.** A withdrawal stops
  future processing under that basis but does NOT make prior
  processing unlawful — the regulator-aligned semantic.

## Tests

`modules/hr/test/kontor/hr/`:

- `hr_test.clj` — install, person + employment + compensation
  lifecycles, the payroll orchestrator + `check-facts`
  invariant, DSAR walk
- `consent_test.clj` — grant / withdraw / supersede + the
  bitemporal `active-at?` semantic + state transitions

## License

Apache 2.0.
