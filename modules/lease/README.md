# kontor-lease

IFRS 16 / ASC 842 lessee-side accounting for `kontor`. The `:lease`
contract + per-(lease, ledger) liability books + the modification +
mid-life import paths.

## What it does

A lease in modern IFRS / US GAAP is a balance-sheet event: a
Right-of-Use asset is recognised, a lease liability is measured at
the present value of the remaining payments, and the two unwind on
*schedules* (one per ledger, since IFRS 16 and ASC 842 classify the
same contract differently). `kontor-lease` provides:

- **`:lease` contract entity** with a framework-neutral lifecycle:
  `:draft` → `:active` → `:expired` / `:terminated` / `:purchased`.
  `define-lease!` records the contract facts at `:draft`; `commence!`
  does the balance-sheet recognition (`:draft → :active`). Status
  transitions are gated by `:status-transition` seeds; commencement
  + early termination require a supporting document (ADR-038
  `:requires-supporting-doc`) and termination also enforces
  `:no-self-approval`.
- **`:lease-liability` per-(lease, ledger) books.** A single
  `:lease` may be classified `:finance` on one ledger and `:operating`
  on another — so `:lease-liability/classification` lives on the
  book, not on the contract. Each book owns one ADR-032
  `:schedule` (`:schedule/kind :lease-liability`) that the runner
  fires.
- **`LeaseProvider` protocol** (`kontor.lease.lease-provider`) — the
  liability unwind. Ships `EffectiveInterestProvider` as the built-in
  (`:effective-interest`); registered via `provider-for`. PURE. Only
  the UN-FIRED tail is planned — already-fired periods are read back
  from the GL (`liability/posted-period-legs`), so the subledger nets
  the ledger rather than re-deriving it from current contract data
  (note 198 HIGH-5, the mirror of the ROU plug's `fired-amounts`).
- **Liability tie-out** (`kontor.lease.report/reconcile-liability`) —
  the detective control: subledger vs. GL control account, per book or
  per (ledger, account). Same shape as
  `kontor.inventory.report/valuation-tie-out` —
  `{:subledger :gl :difference :ok?}`.
- **Operating-lease ROU plug** (`kontor.lease.rou-provider`) — a
  `DepreciationProvider` registered as `:lease-rou-plug` that an
  `:operating` book uses for its ROU `:asset-depreciation` book. The
  ROU amortisation is the *plug* that, together with the
  effective-interest interest leg, sums to a single straight-line
  lease cost per period (ASC 842-20-25-6). A `:finance` book reuses
  the kontor-asset `:straight-line` provider directly.
- **GL posting builders** (`kontor.lease.posting`) — `plan-lease-
  recognition` (day-one entry), `plan-lease-payment` (per-period
  `Dr interest + Dr liability / Cr cash`), `plan-adjustment` (per-
  book modification adjustment), `plan-fx-retranslation` (closing-
  rate monetary-item retranslation; rate is consumer-supplied — no FX
  engine bundled).
- **Modifications + remeasurements + terminations** (`kontor.lease.
  modification`) — `remeasure!` (IFRS 16.39-43 / ASC 842 reassessment),
  `partial-terminate!` (proportional-approach scope decrease, IFRS
  16.46(b)), `terminate!` (full early termination with penalty),
  `purchase!` (purchase-option exercise — ROU continues as an owned
  asset per IFRS 16.67). Each commits as ONE atomic `kontor.process`
  through the validation gate.
- **Mid-life portfolio import** (`runner/import-lease!`, ADR-069) —
  onboard a lease already mid-term from a prior system, carrying
  forward the existing balance-sheet amounts rather than re-deriving
  them. The consumer pre-populates the `:lease/imported?` audit
  denorms; the transactor verifies them.
- **Period close** (`runner/run-lease!`) — fires due liability
  occurrences and runs the sibling ROU depreciation book through
  `kontor.asset.runner/run-depreciation!` in lockstep. A divergence
  guard refuses to run if a prior partial failure left the two
  schedules out of sync.
- **Disclosure-support deltas** (ADR-070) — the modification +
  termination transactors persist aggregated roll-forward deltas on
  the `:lease-modification` event so the IFRS 16 / ASC 842
  disclosure roll-forward is a trivial read, not a re-derivation.
  A `:lease-liability/discount-rate-audit-doc` ref carries the
  discount-rate's audit trail.

## When to use it

- Lessee-side IFRS 16 leases (real estate, vehicles, equipment)
- Lessee-side ASC 842 leases (finance + operating; the operating-
  lease single-cost plug is the headline)
- Mid-life portfolio migrations (`import-lease!`) when adopting
  kontor over an existing lease book

When NOT to use it:
- Lessor-side accounting — not in scope
- Owned-asset depreciation → `kontor-asset` (which kontor-lease
  re-uses for the ROU asset itself — they are designed as siblings)
- Short-term / low-value exemptions still get a primitive:
  `register-exempt-lease!` + `plan-exempt-lease-charge` straight-line
  the expense without creating a `:lease` entity

## Load-bearing ADRs

- [ADR-062](../../doc/decisions.md) — `:lease` contract + lifecycle +
  short-term / low-value exemption path
- [ADR-063](../../doc/decisions.md) — `LeaseProvider` + `:lease-
  liability` per-(lease, ledger) books + `commence!` recognition +
  `run-lease!` period close
- [ADR-064](../../doc/decisions.md) — modifications, remeasurements,
  terminations, purchase + FX retranslation
- [ADR-069](../../doc/decisions.md) — mid-life portfolio import
  (`import-lease!`) + the `:lease/imported?` audit denorms
- [ADR-070](../../doc/decisions.md) — disclosure-support deltas +
  discount-rate audit-doc

## Key namespaces

- `kontor.lease.schema` — `:lease/*`, `:lease-liability/*`,
  `:lease-modification/*` + `status-transition-seeds` +
  `approval-policy-seeds` + `install!`
- `kontor.lease.core` — `define-lease!` (contract recording at
  `:draft`) + `register-exempt-lease!` + `plan-exempt-lease-charge` +
  `present-value` (annuity PV) + lease resolution helpers
  (`by-code`, `resolve-lease`, `pull-lease`)
- `kontor.lease.liability` — `:lease-liability` book lifecycle
  (`open-liability-book!`, `revise-liability-book!`) + book
  resolution + `book-plan-inputs` (the flat map LeaseProvider impls
  consume)
- `kontor.lease.lease-provider` — `LeaseProvider` protocol +
  `EffectiveInterestProvider` built-in + `provider-for` registry +
  `plan-for-book` + `outstanding-liability`
- `kontor.lease.rou-provider` — `:lease-rou-plug`
  `DepreciationProvider` for operating-lease ROU books
- `kontor.lease.posting` — `plan-lease-recognition`,
  `plan-lease-payment`, `plan-adjustment`, `plan-fx-retranslation`
  (pure builders; sum-to-zero per (ledger, commodity) enforced via
  `kontor.posting/build-transaction`)
- `kontor.lease.modification` — `remeasure!`, `partial-terminate!`,
  `terminate!`, `purchase!`
- `kontor.lease.report` — `reconcile-liability`, `reconcile-lease`,
  `attributed-transactions`
- `kontor.lease.runner` — `commence!`, `import-lease!`, `run-lease!`

## Minimal example

```clojure
(require '[kontor.asset.schema   :as asset-schema]
         '[kontor.core           :as k]
         '[kontor.lease.core     :as lease]
         '[kontor.lease.runner   :as lease-runner]
         '[kontor.lease.schema   :as lease-schema])

(def conn (k/create-test-db))
(asset-schema/install! conn)
(lease-schema/install! conn)
;; ... + seed commodity, ledger, accounts, journal, partner (lessor),
;; asset-class, origin-document, actor (:create/uid)

;; Step 1 — record the lease at :draft (contract facts only)
(lease/define-lease!
  conn {:code "LEASE-OFFICE-1"
        :name "Munich HQ floor 3"
        :lessor [:partner/external-id "lessor-acme"]
        :asset-class [:asset-class/code "real-estate"]
        :commencement-date #inst "2026-01-01"
        :term-months 60
        :payment-amount 10000.00M
        :payment-frequency :monthly
        :payment-timing :in-arrears
        :commodity [:commodity/symbol "EUR"]
        :discount-rate 0.05M
        :origin-document <doc-eid>
        :changed-by-uid <recorder-uid>})

;; Step 2 — balance-sheet recognition (commits ONE atomic process)
;; Opens a :lease-liability book + ROU :asset-depreciation book per
;; ledger; posts the day-one Dr ROU / Cr liability entry; drives
;; :lease/status :draft → :active.
(lease-runner/commence!
  conn {:lease "LEASE-OFFICE-1"
        :journal [:journal/code "GEN"]
        :changed-by-uid <approver-uid>
        :rou-asset-account       [:account/code "0250"]
        :rou-accumulated-account [:account/code "0259"]
        :books [{:ledger [:ledger/code "ifrs"]
                 :classification :finance
                 :liability-account [:account/code "1740"]
                 :interest-account  [:account/code "7300"]
                 :rou-expense-account [:account/code "6220"]}]})

;; Step 3 — period close (fires due payments + the sibling ROU dep
;; book in lockstep)
(lease-runner/run-lease!
  conn {:lease "LEASE-OFFICE-1"
        :ledger [:ledger/code "ifrs"]
        :journal [:journal/code "GEN"]
        :cash-account [:account/code "1200"]
        :as-of #inst "2026-12-31"})
```

## What it does NOT do

- **No FX-rate engine.** `plan-fx-retranslation` retranslates the
  liability at the closing rate the consumer supplies. Use
  `kontor.fx-rate-provider` (ADR-072) for sourcing — kontor-lease
  bundles no rates. It is a builder only: nothing in kontor-lease
  wires it, and a consumer that adopts it owns two traps the builder
  cannot close — it moves the GL without moving
  `:lease-liability/opening-liability`, and in provider mode the
  gain/loss is a REPORTING-commodity number tagged with the book
  commodity. Both are spelled out in the `kontor.lease.posting` ns
  docstring (note 198 MED-1).
- **No lessor-side accounting.** Sales-type, direct-financing, and
  operating-lessor are out of scope.
- **No automatic discount-rate determination.** The IBR /
  implicit-rate decision is consumer-side; `:lease/discount-rate` is
  an input and its audit trail lives on
  `:lease-liability/discount-rate-audit-doc` (ADR-070).
- **No componentisation.** A multi-component lease is consumer-side
  decomposition into N `:lease`s.
- **No scheduler.** The runner ships functions; *who* calls them (a
  close-period step, a cron, a Process composition) is the
  consumer's concern (ADR-032). `commence!` / `run-lease!` each do
  several `d/transact`s; they are NOT one atomic outer tx — only
  the per-period payments are atomic.

## Tests

`modules/lease/test/kontor/lease/`:

- `lease_test.clj` — `define-lease!` + the exempt path
- `modification_test.clj` — `remeasure!`, `partial-terminate!`,
  `terminate!`, `purchase!`
- `runner_test.clj` — `commence!`, `run-lease!`, `import-lease!`,
  the lockstep divergence guard

## License

Apache 2.0.
