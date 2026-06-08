# kontor-people-record

Career history + performance reviews + promotions for `kontor-hr`,
consent-gated against ADR-094's `:consent/*` schema. The forensically-
correct "track-record" overlay.

## What it does

A "career record" — positions held, performance reviews, promotions —
is the kind of data EU AI Act Art. 6(3) + GDPR Art. 6 demand
fine-grained legal-basis discipline for. ADR-094 lays out the
substrate posture; this companion is the minimal v0
implementation:

- **`:position-held`** — career history. `record-position!` writes a
  consent-gated row with `:title`, `:level`, `:start-date`,
  `:end-date`, optional `:manager-employment` link. Bitemporal:
  `:tx/valid-from = :start-date` so `(d/valid-at db t)` answers
  "what was Jane's title on date T?".
- **`:performance-review`** — formal documented review events.
  `record-review!` writes `:period-start`, `:period-end`,
  `:outcome` (keyword), optional `:supporting-doc` ref to
  `:audit-doc`, `:calibrated-at` instant. `:tx/valid-from =
  :calibrated-at` (the date the review was finalised + became
  authoritative).
- **`:promotion`** — advancement events. `record-promotion!`
  writes `:from-position`, `:to-position`, `:effective-date`,
  optional `:comp-change` ref to a `:compensation` envelope (so
  the promotion's pay change links to the actual envelope
  supersession in `kontor-hr`).
- **Consent gate.** Every write through this namespace calls
  `kontor.hr.consent/active-at?` for the affected `:person` +
  the constant scope `:hr-track-record` at the write timestamp.
  No active consent → `:consent/missing` ex-info raised, write
  refused. This is the consumer-side enforcement of ADR-094's
  substrate posture: the kernel never enforces consent (ADR-094
  §6), this companion does.
- **DSAR bundler** (`dsar-bundle`). Collects every track-record
  entity touching a `:person` — `:positions`, `:reviews`,
  `:promotions` — for a DSAR pipeline to merge with the kernel
  + HR walks (`kontor.dsar/collect` + `kontor.hr.dsar/collect-
  for-person`).

## When to use it

- Recording an employee's career progression with audit-grade
  legal-basis discipline
- Performance review systems that need to demonstrate
  consent-or-equivalent-lawful-basis at write time
- DSAR (data subject access request) builders that need to
  include career history + reviews + promotions

When NOT to use it:
- Headcount / org-chart visualisation — that's UI, consumer-side
- Activity monitoring (keystrokes, screen, productivity scoring)
  — deliberately a separate companion, not in this one
- Emotion / engagement scoring — project refusal posture
  (ADR-094 §6)
- Automated promotion / termination recommendations — Art. 22
  refusal posture
- Payroll-side compensation envelope writes → `kontor-hr`'s
  `set-compensation!` / `supersede-compensation!`

## Load-bearing ADRs

- [ADR-094](../../doc/decisions.md) — Employee-monitoring posture
  §3.5: the track-record substrate. §6: the refusal posture
  (no `:ai-act-incompatible` enforcement, no scoring, no
  automated termination recommendations) — which is why this
  module is consumer-side enforcement of consent rather than a
  kernel-side enforcer
- [ADR-075](../../doc/decisions.md) — Stage R substrate: the
  `:person` / `:employment` / `:compensation` shape this module
  rides on
- [ADR-052](../../doc/decisions.md) — `kontor.dsar/collect`
  walker; `dsar-bundle` is the per-person bundle a consumer
  composes with the kernel walk
- [ADR-068](../../doc/decisions.md) — `*-tx-data` pure builders +
  `!` wrappers routing through `kontor.validation/transact-
  with-validation`

## Key namespaces

- `kontor.people-record.schema` — `:position-held/*`,
  `:performance-review/*`, `:promotion/*` + `install!`
- `kontor.people-record.core` — `record-position!`,
  `record-review!`, `record-promotion!` (each with paired
  `*-tx-data`) + `check-consent!` gate (private) + `dsar-bundle`

## Minimal example

```clojure
(require '[kontor.core                  :as k]
         '[kontor.hr.core               :as hr]
         '[kontor.hr.consent            :as consent]
         '[kontor.people-record.core    :as track]
         '[kontor.people-record.schema  :as track-schema])

(def conn (k/create-test-db))
(hr/install! conn)
(track-schema/install! conn)
;; ... + seed :person, :employment, :audit-doc for the consent

;; Step 1 — record consent (without this, every track-record
;; write throws :consent/missing)
(consent/grant!
  conn {:code "CONS-alice-tr-2026"
        :subject [:person/external-id "alice@acme.de"]
        :scope :hr-track-record
        :legal-basis :consent
        :supporting-doc <consent-form-doc-eid>
        :granted-at #inst "2026-06-01"})

;; Step 2 — record a position held
(track/record-position!
  conn {:code "POS-alice-001"
        :person [:person/external-id "alice@acme.de"]
        :employment [:employment/code "EMP-DE-001"]
        :title "Senior Engineer"
        :level :senior
        :start-date #inst "2026-06-01"})

;; Step 3 — record a performance review
(track/record-review!
  conn {:code "REV-alice-2026-Q4"
        :person [:person/external-id "alice@acme.de"]
        :reviewer-employment [:employment/code "EMP-MGR-001"]
        :period-start #inst "2026-07-01"
        :period-end   #inst "2026-12-31"
        :outcome :exceeds-expectations
        :supporting-doc <review-doc-eid>
        :calibrated-at #inst "2027-01-15"})

;; Step 4 — record a promotion (with comp-change link to the new
;; :compensation envelope written via kontor.hr.compensation/
;; supersede-compensation!)
(track/record-promotion!
  conn {:code "PROM-alice-002"
        :person [:person/external-id "alice@acme.de"]
        :from-position <pos-001-eid>
        :to-position <pos-002-eid>
        :effective-date #inst "2027-02-01"
        :comp-change <new-comp-envelope-eid>
        :supporting-doc <promotion-letter-doc-eid>})

;; Step 5 — DSAR bundle (consumer merges with kernel + HR walks)
(track/dsar-bundle (datahike.api/db conn) <alice-person-eid>)
;; => {:positions [...] :reviews [...] :promotions [...]}
```

## What it does NOT do

- **No activity monitoring.** Keystroke / screen / webcam capture
  is deliberately a separate (unshipped) companion.
  This module is forensically-correct *recorded events* only.
- **No automated scoring.** No engagement / productivity /
  emotion / sentiment scoring (ADR-094 §6 refusal posture).
- **No automated promotion / termination recommendations.** GDPR
  Art. 22 + EU AI Act Art. 6 territory; project refuses to
  canonicalize.
- **No consent enforcement at the kernel layer.** Consent
  checking is consumer-side because ADR-094 §6 keeps the kernel
  neutral; this module IS the consumer-side enforcement for the
  `:hr-track-record` scope.
- **No retroactive consent application.** A track-record entry
  written before consent was granted is NOT lawful in retrospect
  — `record-*!` simply refuses the write at the time. Consent
  must be in force when the write happens.
- **No automated supersession of `:position-held` rows.** Closing
  one position + opening the next on promotion is a consumer-
  side composition (consumer can compose the two `*-tx-data`
  builders + the `record-promotion-tx-data` into one
  `kontor.process` step list).

## Tests

`modules/people-record/test/kontor/people_record/lifecycle_test.clj`
— single file covering install, position / review / promotion
writes, the consent gate refusal path, and the DSAR bundle shape.

## License

Apache 2.0.
