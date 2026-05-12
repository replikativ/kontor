# Research note 15 — Stage L (kontor-collections) synthesis

Three research-before agents ran in parallel per the CLAUDE.md per-stage rhythm:

1. **OFBiz reference study** — Apache OFBiz collections / dunning data model at file:line depth.
2. **Market-pain delta** — G2/Capterra/TrustRadius reviews of NetSuite Dunning, SAP FSCM-COL, Quadient/YayPay, HighRadius, Versapay, Esker, BlackLine, Sidetrade; insideARM, GDPR + Reg-F commentary.
3. **Internal gap analysis** — what the kontor substrate already provides; what's missing; ambiguities.

This note synthesizes the three. Not a design doc — design lives in ADR-043.

## TL;DR

- **OFBiz has no collections module.** Zero hits in entities/services/seeds/JIRA for "dunning", "overdue", "collections" (excluding `java.util.Collection`), "creditHold". The closest surface is one overdue-invoice search (`getInvoicePaymentInfoListByDueDateOffset`) + a static `BillingAccount.accountLimit` field that no service enforces as a hold. After ~20 years of OSS history this is decisive: the OFBiz community treated collections as out-of-scope. **kontor-collections is designing into a vacuum** — competitors carry the design vocabulary, not OSS predecessors.
- **kontor's substrate is unusually ready.** `balance.clj` + `ledger.clj` + `reconciliation.clj` are already bitemporal AR plumbing. `status_machine.clj` + `audit_doc.clj` + `side_effect.clj` already half-build the collection-action log. ADR-038 approval-policy + ADR-040 jurisdiction primitives compose. ADR-039 partner-credit-limit was forward-compat'd for this stage.
- **One hard kernel gap: partial-payment.** `reconciliation.clj:38-47` honestly flags it. Without a `:payment-application` primitive the aging is fully-open or fully-closed; collections cannot function. **Lives in kernel**, not collections — Stage M revrec and Stage N subscription will need the same primitive.
- **35 market-pain points, 10 cluster on substrate-natural fixes.** Dispute-auto-suppresses-dunning, unapplied-cash-gates-dunning, PTP as first-class entity, replayable allocation, per-customer term-relative aging, pause-with-reason-code, bitemporal credit utilization, bitemporal dispute, frequency-cap as policy predicate, unbounded escalation levels — every one of these is a structural advantage of kontor's bitemporal + sum-to-zero + state-machine-as-data design over the named competitors.

## What OFBiz teaches us by absence

The OFBiz finding is the most interesting research output. The team that built one of the most complete OSS ERPs explicitly avoided collections. Hypothesis: **collections is the layer where ERP-as-shared-product meets country-by-country compliance + collector-judgment-call workflows**. The shared substrate doesn't carry far. We model the *substrate* (state machines, audit-doc, policy predicates), not the *playbook* (which letter on day 30 vs day 45 — that's tenant policy + l10n).

This shapes ADR-043: the companion ships **mechanisms** (entities, queries, state machines, policy hooks), **not workflows** (no built-in "30/60/90 reminder cadence"). Tenants compose their cadence via `:dunning-policy/levels` (a sorted vector). l10n modules provide jurisdiction-specific reference policies (DE Mahnverfahren has specific timers, EU late-payment directive mandates ECB+8% interest).

## Market-pain top 10 (substrate-aligned)

Each pain is structurally easier in kontor than in the named competitor because of an existing ADR primitive.

| # | Pain | kontor substrate hook |
|---|---|---|
| 17 | Dispute open but dunning runs anyway (SAP / NetSuite) | Bitemporal predicate `:dispute/state :open AND :dispute/scope = invoice` — read at `:as-of-valid`, no manual pause needed |
| 23 | Unapplied-cash purgatory (universal #1 cash-app pain) | Sum-to-zero (ADR-021) + `:payment/state :unapplied` + dunning eligibility query excludes invoices with pending unapplied-cash for the partner |
| 11 | PTP as sticky-note in free-text field (Quadient / Versapay) | `:payment-promise` as full-status-machine entity; sweeper (ADR-041) flips `:kept? false` and re-opens case on missed promise date |
| 22 | No replayable payment allocation (universal) | datahike tx-time gives free allocation undo; `:payment-application/strategy` keyword on the tx |
| 1 | Hardcoded global aging buckets (NetSuite per-customer fail) | `aging.clj` extended with `:method {:due-date :invoice-date :statement-date}` + per-customer payment-term offset; computed at query time |
| 7 | Pause-with-reason is free-text or missing (NetSuite) | `:dunning-pause/reason-code` enum + `:dunning-pause/expires-at` + audit-doc |
| 19 | Stale credit data blocks live orders (D365 / Bectran) | Bitemporal credit-utilization query reads from current postings; never a cached snapshot |
| 15 | Disputes aren't bitemporal (SAP FSCM dispute case data overwrites) | kontor's bitemporal default + `:dispute` as a normal entity solves this |
| 34 | No call-frequency cap enforcement (Reg-F violation risk) | `:dunning-event` count query over rolling window; policy enforces *before* emission |
| 6 | Capped escalation levels (NetSuite: 15) | `:dunning-policy/levels` is a sorted vector — no cap |

## Internal substrate inventory

What's already in place (file:line per the gap analysis):

- `src/kontor/balance.clj` — `account-balance` with bitemporal `:as-of-tx` × `:as-of-valid`
- `src/kontor/ledger.clj` — `postings-against`, `running-balance`, `:ledger` parallel-ledger entity (ADR-021)
- `src/kontor/reconciliation.clj:124-194` — `open-receivables-by-tx` (gross AR minus `:transaction/settles` offsets)
- `src/kontor/aging.clj` — `default-buckets`, `aging-rows`, `aging-summary-by-bucket`, `aging-by-partner` (hardcoded to `:transaction/due-date`)
- `src/kontor/invoice.clj` — kernel `create!` / `send!` / `mark-paid!` / `cancel!` / `flip-paid-on-settlement`
- `src/kontor/status_machine.clj` — generic state-machine + approval-policy (ADR-034 + ADR-038)
- `src/kontor/audit_doc.clj` + schema (ADR-038)
- `src/kontor/side_effect.clj` — `:side-effect-intent` for outgoing communication
- `src/kontor/schema.clj:406-446` — `:partner/credit-limit`, `:partner/credit-status` (ADR-039)
- `src/kontor/payment_term.clj` — `compute-due-date`, `apply-term`

What's not:
- `:payment-application` (partial-payment) — the blocker
- `:collection-case`, `:payment-promise`, `:dispute`, `:credit-hold` overlay — companion-local
- Aging-method opt + per-customer term-relative buckets — extend `aging.clj`
- `:dunning-policy`, `:dunning-event`, `:dunning-pause` — companion-local
- Frequency-cap predicate — companion-local

## Design calls surfaced + user decisions (2026-05-12)

1. **Stage L = kontor-collections first, kontor-asset second** (roadmap reordered accordingly).
2. **`:payment-application` ships in the kernel** (revrec + subscription need it; collections is the loudest customer, not the only one).
3. **Credit-hold = default scalar + per-entity overlay** (single-entity tenants see no complexity; multi-entity gets correctness; mirrors `:status-transition/applies-to-org` pattern).
4. **Dunning letters = typed `:audit-doc`** (no first-class entity; `(status-history, side-effect-intent, audit-doc)` triple is canonical).

## Cross-companion risk (forward-compat)

- **`:invoice/match-status` is procurement-only.** Collections must use a separate facet `:invoice/collections-status` to avoid semantic collision with the 3-way-match state machine.
- **Stage M revrec.** Will move portions of `:invoice` gross to `:sales-revenue-deferred`. Collections must compute open AR from postings (`open-receivables-by-tx`), not `:invoice/total-gross`. Substrate already protects this.
- **Stage N subscription.** Subscription invoices need a grace-period exemption from dunning until the retry cycle. Internal-gap recommendation: `:invoice/schedule` ref → `:schedule` (ADR-032). Collections checks for ref presence.
- **ADR-031 multi-entity.** Cases must be `:collection-case/entity`-scoped. Aging-by-partner queries must filter on entity.

## Out-of-scope for v1

Per the market-pain delta:
- Predictive-payment-date ML (sibling `kontor-ml` companion)
- SMS / WhatsApp / voice dunning channels (channel adapters in consumer apps)
- Debt sale to third-party agencies (separate compliance regime; sibling companion)
- Inline customer-portal UI (ADR-010 prohibits UI in kernel/companion)
- Country-specific late-fee rate tables (l10n modules)
- Embedded letter-template WYSIWYG designer (consumer-side)

## Sources

OFBiz reference (negative finding):
- `apache/ofbiz-framework` trunk: `applications/datamodel/entitydef/accounting-entitymodel.xml`, `applications/datamodel/data/seed/AccountingSeedData.xml`, `applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy`, `applications/accounting/widget/ar/ArMenus.xml`

Market-pain (most-cited):
- G2: Quadient AR / HighRadius / Sidetrade / Versapay reviews
- HighRadius blog: "Top 5 NetSuite Dunning Challenges"
- insideARM: PTP follow-up policies
- TCN: Regulation F guide; Oxford Academic: GDPR & informal debt collection

Internal gap (key file refs):
- `src/kontor/{balance,ledger,reconciliation,aging,invoice,status_machine,audit_doc,side_effect,payment_term,schema}.clj`
- `doc/decisions.md` ADRs 030, 034, 038, 039, 040, 041

Date: 2026-05-12.
