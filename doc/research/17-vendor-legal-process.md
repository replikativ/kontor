# Research note 17 — Vendor legal / compliance landscape + kontor primitive sketch

How enterprise systems handle **legal documents, contract obligations, e-signature, privilege, retention, DSAR, M&A, counsel engagement, and GRC** — and what (if anything) `kontor` should grow as kernel primitives versus defer to consumer apps.

This note is a research input, not a design doc. Anything that turns into kontor primitives will be promoted to an ADR.

## TL;DR

1. The legal/compliance surface is **enormous and fragmented**: each vendor category (ERPs, CLM, e-sign, cap-table, board portal, counsel-engagement, e-discovery, VDR) carries one slice. None of the big ERPs (SAP S/4, NetSuite, Oracle Fusion, Odoo) own the whole story end-to-end. SAP ships ILM + Records Management + Ariba CLM as three separately licensed pieces ([SAP ILM](https://www.sap.com/products/technology-platform/information-lifecycle-management.html), [SAP S/4HANA Enterprise Contract Management](https://www.sap.com/assetdetail/2024/11/6a0d2cc3-e27e-0010-bca6-c68f7e60039b.html)). NetSuite ships SuiteApprovals + PI Removal — and the audit-trail/right-to-erasure tension is explicit ([Houseblend NetSuite GDPR](https://www.houseblend.io/articles/netsuite-gdpr-compliance)). Odoo's GDPR module is a wizard that masks PII and writes a `privacy.log` row — that's it (`addons/privacy_lookup/models/privacy_log.py`).
2. **Three architectural shapes recur**:
   - **Document-as-row** with a content hash + storage-uri (kontor's `:audit-doc` already has this, ADR-038).
   - **Obligation as a sweepable schedule** (renewal dates, SLA deadlines, indemnity-cap accruals) — kontor's `:schedule` (ADR-032) + time-based transitions (ADR-041) compose.
   - **Privilege / retention / hold as policy predicate** that gates queries and side-effects — no kontor analogue yet; new primitive surface.
3. **kontor's substrate is unusually well-positioned**. Bitemporal queries solve "what did the privilege label look like at the date of the subpoena." Sum-to-zero + `:audit-doc` solve "every consent change is auditor-ready." `:status-transition` + `:approval-policy` solve "this contract execution required two-counsel sign-off." The deltas to close are mostly *vocabulary* (privilege tags, retention codes, hold reasons) and *one or two new entities* (`:retention-policy`, `:legal-hold`, `:dsar-request`, `:counsel-matter`).
4. **What clearly belongs in consumers**: redlining UI, signing-pad UI, board-pack PDF assembly, VDR access dashboards, e-discovery review tools, privilege-log wizards. The kernel's job is **data primitives + invariants** ("posted under hold cannot be purged", "PII redaction must cite a retention basis", "renewal-window obligation auto-fires a side-effect intent").
5. **Hard line: do not write a CLM, an e-signature service, an e-discovery review tool, or a cap-table.** Per ADR-010 + ADR-037, kontor is a kernel; CLM/e-sign/cap-table are commodity SaaS with strong incumbents. The kernel ships *the data shapes that let an accounting record reference a contract clause, a privileged document, a retention policy, or a litigation hold* so that consumers can integrate the commodity SaaS without losing audit fidelity.

## 1. Capabilities matrix

How the five vendor categories handle each pain area. Rated:
- **well** — the vendor has a first-class primitive that an auditor would recognize
- **barely** — present but bolted-on, free-text, or per-module duplication
- **missing** — no primitive; customer has to integrate or accept the gap

| Pain area | Big ERP (SAP/Oracle/NetSuite) | Odoo / Tryton (OSS) | CLM (Ironclad/DocuSign-CLM/Juro) | Legaltech adjacents (Carta / Diligent / Brightflag / Relativity) | Boutique / SMB tooling |
|---|---|---|---|---|---|
| **Contract repository** | well (Ariba Contracts, NetSuite CM, Fusion Procurement/Enterprise Contracts) | barely (Odoo Agreements OCA addon, no clause library; Tryton none) | well (clause library + version control + AI extraction) | missing (out of scope) | barely (PandaDoc, ContractSafe) |
| **Redlining + negotiation** | barely (mostly relies on Word round-trip) | missing | well (in-app track-changes, AI suggestions) | missing | barely (Word + email) |
| **E-signature audit trail** | barely (Ariba via DocuSign integration) | barely (`sign` module — basic certificate; no PKI-sealed) | well (DocuSign Certificate of Completion is the industry reference [DocuSign Trust](https://www.docusign.com/trust/security/transaction-data-use)) | missing (Carta wraps DocuSign for grant signing) | barely |
| **Obligation tracking (renewal alerts, SLA deadlines, indemnity caps)** | barely (SAP S/4 "intelligent scheduling and reminder system" per [SAP press release](https://www.sap.com/assetdetail/2024/11/6a0d2cc3-e27e-0010-bca6-c68f7e60039b.html); date fields without state machines) | missing | well (Ironclad Obligation Management) | missing | missing — biggest SMB gap |
| **Privilege tagging (attorney-client, work product)** | missing | missing | missing (CLM is for executed contracts; privilege is a discovery concept) | well (Relativity privilege coding workflow + AI-Privilege) | missing |
| **Retention policy (SOX 7y vs GDPR right-to-erasure)** | well (SAP ILM); barely (NetSuite via PI Removal — anonymizes logs but doesn't delete per [Houseblend GDPR analysis](https://www.houseblend.io/articles/netsuite-gdpr-compliance)) | barely (Odoo `data_recycle` module — archive/unlink based on cron rules) | barely (CLMs retain executed contracts ~7y but no jurisdictional opt) | barely (Relativity has hold-vs-retention) | missing |
| **DSAR fulfillment** | barely (SAP ILM does data discovery but workflow is manual); barely (NetSuite has PI Removal but you still hand-build a portal) | barely (Odoo `privacy_lookup` is a search-and-anonymize wizard; OCA `odoo_gdpr` adds a request portal) | missing | barely (BigID/DataGrail/OneTrust own this; not the ERPs) | missing |
| **Litigation hold / preservation order** | barely (SAP ILM legal hold; NetSuite "you build a saved search") | missing | barely (CLM contract documents only) | well (Logikcull, Onna, OpenText Legal Hold are dedicated products) | missing |
| **M&A diligence room** | missing | missing | barely (Ironclad has limited Q&A; not a VDR) | well (Datasite, Intralinks, Firmex — but [Peony April 2026 transparency research](https://www.peony.ink/blog/virtual-data-room-cost-guide) reports 47% of legacy VDRs publish no pricing; per-page upload fees can run $4-8.5k for a 10k-page deal) | missing |
| **Counsel engagement / matter / e-billing (UTBMS, LEDES)** | barely (Ariba Buyer can route legal-services POs; no UTBMS support) | missing | missing | well (Brightflag, SimpleLegal, Onit Enelyzer — UTBMS line-item review, sometimes AI-assisted per [Brightflag UTBMS-and-LEDES](https://brightflag.com/resources/utbms-and-ledes-codes-ai/)) | missing |
| **Cap-table / 409A / grant audit** | missing | missing | missing | well (Carta, Pulley, Capdesk — but they are "the ledger of legal transactions", not the GL; per [The Startup Law Blog](https://www.thestartuplawblog.com/cap-table-software-carta-pulley-angellist/) they don't validate board authorization, only display it) | barely (Pulley free tier for early-stage) |
| **Board governance (materials, votes, minutes, audit committee)** | missing | missing | missing | well (Diligent Boards, Boardvantage, Nasdaq Boardvantage; [Diligent product page](https://www.diligent.com/products/boards)) | barely (Google Drive + Doodle) |
| **GRC controls + SoD enforcement** | well (SAP GRC Access Control / Process Control; Oracle Risk Management Cloud) | barely (security groups; no continuous control monitoring) | missing | barely (Pathlock, MTC Skopos for SAP; standalone) | missing |
| **Audit trail (who, when, what, why)** | well (SAP system notes; NetSuite system audit trail) | barely (Odoo mail.thread for chatter; Tryton `_history` opt-in) | well (CLM event logs are core) | well (Relativity audit, Diligent Boards activity logs) | barely |
| **Records management (retention rules per record class)** | well (SAP ILM Retention Management; OpenText) | barely (Odoo `data_recycle` cron rules) | missing | barely (records-class concept in Relativity) | missing |

**Read across the matrix.** Three patterns stand out:

1. **Obligation tracking is universally missing or barely present** in the ERP layer; CLMs own it but only for executed contracts. SMBs without a CLM lose 9% of annual revenue to missed obligations per WCC ([Business Software article](https://www.business-software.com/blog/contract-obligation-management-using-clm-to-track-what-you-promised-and-what-they-owe/)).
2. **Privilege + litigation hold are dedicated-tooling concerns** with no ERP analogue. The ERP's role is to *not lose the data when the hold drops* — which means a hard write-side invariant ("you may not purge while under hold"), not a workflow.
3. **DSAR and retention policy collide** with SOX-7-year ([Pathlock SOX retention guide](https://pathlock.com/learn/sox-data-retention-requirements/)) and GDPR-minimization. NetSuite's "anonymize the log but keep the row" pattern is the industry compromise ([Houseblend NetSuite GDPR](https://www.houseblend.io/articles/netsuite-gdpr-compliance)). kontor's `:db/purge`-as-recorded-commit (ADR-007) is the same compromise expressed differently — the chain documents the purge.

## 2. Pain-point list (16 items, ranked by SMB / mid-market relevance)

Each item: severity, one-line remediation hint. P0 = blocks an SMB doing serious business, P1 = bites at scale, P2 = enterprise polish.

1. **Auto-renewal-trap** — 67% of businesses don't track renewal dates per [ExpiryEdge](https://expiryedge.com/blogs/the-hidden-costs-of-forgotten-renewals-how-businesses-lose-money/); 69% of SaaS contracts auto-renew on 30-90 day notice. **P0**. Remediation: `:contract-obligation` with `:obligation/trigger-window` driven by `:schedule` (ADR-032) + `:side-effect-intent` (ADR-041) for "T-90, T-60, T-30 alert" emission.
2. **Contract-to-revrec disconnect** — billing system has the dates, contract repo has the terms; ASC 606 performance-obligation mapping is manual ([Deloitte DART](https://dart.deloitte.com/USDART/home/codification/revenue/asc606-10/roadmap-revenue-recognition/chapter-14-presentation/14-2-contract-liabilities)). **P0** for any SaaS. Remediation: `:legal-doc/ref` on `:transaction` + `:performance-obligation` entity that links contract clause → revrec schedule.
3. **Litigation-hold-vs-purge race** — purge runs as cron; legal hold arrives by email; data already gone before the IT ticket lands. **P0** at any regulated company. Remediation: `:legal-hold` entity + middleware predicate that blocks `:db/purge` on entities matching the hold scope (overrides ADR-007).
4. **DSAR fulfillment is manual + scattered** — $1,400/request, >2 weeks median per [BigID](https://bigid.com/blog/what-is-dsar/); data lives in CRM, billing, support, accounting. **P1** for any EU/CA-customer-facing SMB. Remediation: `:dsar-request` entity + `:dsar-finding` rows per system; the kernel ships the *request log* primitive, not the cross-system search.
5. **Privilege bleed in accounting documents** — a memo from outside counsel attached to an invoice line as a PDF loses its privilege label when transferred into ERP. **P1**. Remediation: `:privilege-tag` on `:audit-doc` (ADR-038); query helpers refuse to render the URI when the requesting user lacks the privilege role.
6. **Obligation flow into ops** — "vendor must give 30 days termination notice" is in the contract PDF but nowhere actionable in the accounting system. **P1**. Remediation: `:contract-obligation` with `:obligation/owner` (internal team) + `:obligation/counterparty` (partner ref).
7. **Audit trail with no "why"** — NetSuite + Odoo log who-and-when but `:reason` is free-text or missing ([Houseblend SuiteApprovals](https://www.houseblend.io/articles/netsuite-custom-approval-workflow)). kontor already fixed this in ADR-038 (codified `:status-history/reason`). **P1** for SOX-regime tenants; **already addressed**.
8. **No segregation-of-duties enforcement for legal acts** — anyone with edit access can mark a contract "executed", file a counsel-engagement matter, or approve a 409A. SAP GRC is the gold standard but priced for enterprise per [MTC Skopos comparison](https://meylan-tc.com/en/blog/sap-grc-vs-skopos-sod-tools/). **P1** at scale. Remediation: extend ADR-038 `:approval-policy` to cover `:contract/execute`, `:cap-table/grant-approve`, `:hold/release` transitions.
9. **Outside-counsel e-billing is manual** — small/mid-market firms get PDF invoices, key them into ERP, lose UTBMS task codes (`L100` Case Assessment, `A104` Review/Analyze, etc.) per [LEDES standards](https://en.wikipedia.org/wiki/Uniform_Task-Based_Management_System). **P1** for any company with >$50k/y outside-counsel spend. Remediation: `:counsel-matter` + `:legal-invoice` (subtype of `:vendor-invoice`) with `:legal-line/utbms-task-code`, `:legal-line/utbms-activity-code` keywords.
10. **Conflict checks live in a different system** — CRM thinks it's an opportunity, legal thinks it's a conflict ([Clio conflict-check guide](https://www.clio.com/blog/conflict-check-how-to/)). **P2** for non-law-firm SMBs; **out-of-scope** for kontor — this lives in `kontor-partner` (ADR-033) plus consumer-side query.
11. **VDR cost + pricing opacity** — Datasite $0.40-0.85/page upload fees; 47% of enterprise VDRs publish no pricing per [Peony research](https://www.peony.ink/blog/virtual-data-room-cost-guide). **P2** for SMBs (intermittent need). **Out-of-scope** — VDR is dedicated SaaS; kontor's role is to expose a read-only audited bundle of "all postings + supporting docs for entity X, as of date Y" that the VDR ingests.
12. **Retention rules colliding with right-to-erasure** — SOX says 7y, GDPR says no-longer-than-necessary. Industry compromise = anonymize-but-keep-the-row ([Pathlock](https://pathlock.com/learn/sox-data-retention-requirements/)). **P1** for EU-facing companies. Remediation: `:retention-policy` entity (per record class, per jurisdiction, with min/max periods); `:db/purge` for the partner's PII fields but the posting row + content hash stays.
13. **Cap-table double-bookkeeping** — Carta is "the legal ledger" but doesn't post to the GL ([Startup Law Blog](https://www.thestartuplawblog.com/cap-table-software-carta-pulley-angellist/)); SBC accrual and dilution belong in two systems. **P1** for any equity-issuing company. Remediation: `:equity-grant` schedule (vesting waterfall as `:schedule`, ADR-032) + AcctgTrans for grant-date FV + period vesting expense. Cap-table-of-record stays at Carta/Pulley.
14. **Board approval traceability** — "the board approved this transaction" is a screenshot of a meeting minute pasted into an email. Diligent Boards solves it at $40-60k/y. **P2** for SMBs. Remediation: typed `:audit-doc :board-resolution` + `:approval-policy` requiring this doc type for transitions like `:cap-table/grant-issue` or `:contract/execute-over-threshold`.
15. **E-signature integrity decays in the ERP** — DocuSign certificate is tamper-evident at the PDF level ([DocuSign Trust](https://www.docusign.com/trust/security/transaction-data-use)); once it's a `bytes` field in NetSuite the chain breaks. **P1**. Remediation: `:audit-doc/content-hash` (already in ADR-038) + `:audit-doc/external-attestation-id` (new optional attr) lets the consumer record the DocuSign envelope ID, leaving the bytes wherever they live.
16. **GDPR consent state has no bitemporal record** — partner said yes today, withdrew tomorrow; what was the consent at the moment of the marketing send? **P1** for EU-facing. Remediation: `:partner/consent` as a status-machine entity (ADR-034), bitemporal queries (ADR-008) give "consent as of (tx-time, valid-time)" for free.

Severity tally: 3× **P0**, 9× **P1**, 4× **P2**.

## 3. Sketch of kontor primitives

The remediations above cluster into **five new entities + one new tag**, all expressible as straightforward additions to the kernel-or-companion split kontor already uses. None should ship unless a roadmap stage explicitly motivates them.

### 3.1 `:legal-doc` — contract / clause / executed-agreement reference (kernel-or-companion?)

Currently `:audit-doc` (ADR-038) covers proof-of-anything attachments. `:legal-doc` is a thicker subtype:

```
:legal-doc/audit-doc           ref → :audit-doc   ; the underlying file
:legal-doc/kind                keyword
                                ; :master-agreement | :sow | :order-form |
                                ; :amendment | :nda | :renewal-notice |
                                ; :termination-letter | :board-resolution |
                                ; :grant-agreement | :stock-purchase
:legal-doc/parties             [ref]  → :partner
:legal-doc/effective-from      instant
:legal-doc/effective-until     instant            ; nil = open-ended
:legal-doc/governing-jurisdiction ref → :country/:state
:legal-doc/external-doc-id     string             ; DocuSign envelope ID, etc.
:legal-doc/parent-doc          ref → :legal-doc   ; amendment chain
:legal-doc/status              keyword            ; status-machine facet
                                ; :draft :negotiating :signed :executed
                                ; :superseded :terminated :expired
```

**Probably companion-level**, in a new `kontor-clm` or `kontor-legal` module — kernel ships `:audit-doc`, companion adds the typed wrapper. ADR-020 (document-type registry) is for *fiscal* documents; legal docs are a parallel registry that consumers configure.

Status machine via ADR-034 vocabulary: `:legal-doc/status` facet, transitions `(draft → negotiating → signed → executed → superseded | terminated | expired)`, with time-based auto-`expired` driven by ADR-041 when `:legal-doc/effective-until` passes.

### 3.2 `:contract-obligation` — sweepable obligation (companion-level)

```
:contract-obligation/legal-doc  ref → :legal-doc
:contract-obligation/kind       keyword
                                ; :renewal-notice | :sla-uptime |
                                ; :payment-terms | :indemnity-cap |
                                ; :exclusivity | :non-compete |
                                ; :data-deletion | :audit-right |
                                ; :report-delivery
:contract-obligation/owner      keyword           ; :us | :counterparty
:contract-obligation/schedule   ref → :schedule   ; ADR-032
:contract-obligation/state      keyword           ; :pending :active :triggered
                                                  ;  :satisfied :breached :waived
:contract-obligation/breach-posting ref → :transaction
                                                  ; the AcctgTrans recording
                                                  ; the penalty / claim that
                                                  ; this breach generates
```

The `:schedule` link is the killer: T-90, T-60, T-30 alerts are just sweep events that fire `:side-effect-intent` (ADR-041). The state machine + audit-doc supporting evidence make breach claims auditable.

A natural place to land this is alongside `kontor-sales` / `kontor-invoice` — order-form obligations bridge to revrec via `:performance-obligation` (a likely Stage M revrec primitive anyway).

### 3.3 `:retention-policy` — record-class retention rule (kernel candidate)

```
:retention-policy/code           keyword :db.unique/identity
                                ; :sox-financial-7y | :gdpr-marketing-2y |
                                ; :hipaa-medical-6y | :de-handelsbuch-10y
:retention-policy/applies-to     [keyword]          ; entity-types in scope
                                                    ; e.g. [:transaction :invoice]
:retention-policy/min-keep-millis long               ; floor (regulator)
:retention-policy/max-keep-millis long               ; ceiling (data minimization)
:retention-policy/jurisdictions  [ref] → :country   ; ADR-023
:retention-policy/legal-basis    keyword
                                ; :sox-section-103 | :gdpr-article-5 |
                                ; :hgb-§257 | :irs-publication-583
```

The kernel ships the *policy* entity + a query helper `eligible-for-purge?(entity, as-of)`; consumers run the cron and call `:db/purge`. ADR-007 (purge-is-a-recorded-commit) already mandates the audit chain. The new primitive answers *which* records are eligible — and what `:status-history/reason` to record.

**Probably kernel** — it's small, it composes with `:db/purge` and ADR-038, and every Stage M+ companion that adds new record classes needs to register its retention defaults.

### 3.4 `:legal-hold` — preservation order overlay (kernel candidate)

```
:legal-hold/code             string :db.unique/identity
:legal-hold/case-name        string
:legal-hold/issued-by        ref → :create/uid       ; usually inside counsel
:legal-hold/issued-at        instant
:legal-hold/released-at      instant                 ; nil = still in force
:legal-hold/release-reason   keyword
:legal-hold/scope            edn-blob                ; query spec
                            ; {:partners [#uuid] :date-from … :date-to …
                            ;  :entity-types [:transaction :invoice :audit-doc]}
:legal-hold/audit-doc        ref → :audit-doc        ; the preservation order PDF
```

Middleware extends ADR-007's purge-validation to refuse a `:db/purge` whose target falls within any open hold's scope. This is the **strongest design lever** in the entire research — the kernel can structurally guarantee "you cannot lose preserved data" rather than relying on a manual cron-pause as NetSuite and Odoo do.

The hold scope is intentionally an EDN query spec, not a structured FK — preservation orders are messy in shape ("all communications with partner X between 2024-Q1 and 2025-Q2"). Datalog can interpret it at purge time.

**Kernel** — the invariant is structural and crosses every companion.

### 3.5 `:dsar-request` — data-subject request log (companion-level)

```
:dsar-request/code            string :db.unique/identity
:dsar-request/data-subject    ref → :partner          ; the requester
:dsar-request/jurisdiction    ref → :country          ; GDPR / CCPA / LGPD / …
:dsar-request/kind            keyword
                              ; :access | :portability |
                              ; :erasure | :rectification |
                              ; :restriction | :objection
:dsar-request/received-at     instant
:dsar-request/deadline        instant                 ; statutory clock
:dsar-request/status          keyword                 ; status-machine facet
                              ; :received :verifying-identity :searching
                              ; :compiling :awaiting-legal-review
                              ; :fulfilled :rejected :extended
:dsar-request/fulfilled-at    instant
:dsar-request/findings        [edn]                   ; per-system search results
:dsar-request/rejection-reason keyword
                              ; :identity-not-verified | :no-data |
                              ; :legal-hold-override | :exempt-records
```

Status machine via ADR-034. Statutory-deadline driver via `:status-transition/auto-after-millis` (ADR-041) — e.g. auto-flag at T-7 days. Fulfillment requires a `:legal-hold` cross-check before any erasure operation (the structural answer to "GDPR erasure vs SOX retention vs litigation hold" — hold beats both, then retention floor wins over erasure, then erasure runs as `:db/purge` of PII fields only).

**Companion-level** in a `kontor-privacy` (or `kontor-compliance`) module — every consumer needs it, but the cross-system search lives in beleg/simmis, not kernel.

### 3.6 `:counsel-matter` + `:legal-invoice` (companion-level, `kontor-counsel`)

UTBMS task codes (`L100…L600` litigation, `A100…A111` activity, `E100…E125` expenses) are public-domain ABA standard ([UTBMS Wikipedia](https://en.wikipedia.org/wiki/Uniform_Task-Based_Management_System)); LEDES is the file format. A `:counsel-matter` is a long-lived case context; a `:legal-invoice` is a `:vendor-invoice` subtype whose lines carry UTBMS codes.

```
:counsel-matter/code           string :db.unique/identity
:counsel-matter/title          string
:counsel-matter/opened-at      instant
:counsel-matter/closed-at      instant
:counsel-matter/responsible    ref → :partner       ; lead counsel firm
:counsel-matter/internal-owner ref → :create/uid    ; GC / VP Legal
:counsel-matter/budget         money
:counsel-matter/practice-area  keyword
                              ; :litigation | :corporate | :ip |
                              ; :employment | :ma | :compliance

:legal-line/utbms-task-code     keyword             ; :l210 :a104 :e108
:legal-line/utbms-activity-code keyword
:legal-line/expense-code        keyword
:legal-line/timekeeper          ref → :partner      ; the named attorney
:legal-line/hours               bigdec
:legal-line/rate                money
:legal-line/matter              ref → :counsel-matter
```

**Companion-level** — `kontor-counsel` (or `kontor-legal-spend`). Reuses `kontor-procurement` (ADR-042) for 3-way-match where applicable; reuses ADR-039 for partner KYC of law firms.

### 3.7 `:privilege-tag` — privileged-document marker (kernel attribute)

A single attribute, not an entity:

```
:audit-doc/privilege          keyword
                              ; :attorney-client | :work-product |
                              ; :joint-defense | :settlement-communication |
                              ; nil  (default — not privileged)
```

The query helper `kontor.audit-doc/uri-for(doc, requesting-uid)` checks the privilege tag against a per-user role and returns either the storage-uri or `:redacted`. The bytes never leave the storage system; only the *reference* is gated.

Kernel-level because the gating belongs in the same layer as `:db/purge` middleware — both are write-side (well, read-side here) invariants over the same data.

## 4. What to defer to consumer apps (beleg, simmis)

A long list — most of the legaltech surface is workflow + UI, not data primitive:

- **Redlining UI / WYSIWYG clause editor** — beleg-side; integrate with Word, Google Docs, or an existing CLM.
- **E-signature UX** — call DocuSign/Adobe Sign; kernel records the envelope ID + completion certificate hash.
- **Privilege-log generator** — query the kernel for `:audit-doc/privilege /= nil` in the discovery scope; render in consumer.
- **DSAR portal + identity verification** — consumer-side intake form, KYC ping, OTP. Kernel just records the request + findings.
- **VDR / Q&A index / watermarking** — pure consumer or third-party SaaS (Datasite, Firmex, or a self-hosted dropbox of audit-doc URIs).
- **Cap-table grant issuance UI + waterfall scenarios** — Carta / Pulley do this well; kontor just gets the AcctgTrans postings for SBC expense.
- **Board pack assembly + e-vote tooling** — Diligent / Boardvantage; kernel only stores the `:audit-doc :board-resolution` reference that matters for approval-policy checks.
- **Outside-counsel matter-management workflow UI** — Brightflag / SimpleLegal style. Kernel stores invoices + matter codes; the prioritization queue + AI invoice review lives elsewhere.
- **E-discovery review platform** — Relativity / Logikcull territory; kernel exposes a *read-only audit bundle* (postings + audit-docs + status-history) to the e-discovery tool's ingestion.
- **Contract clause library + AI extraction** — pure CLM concern.
- **Country-specific records-management cookbook** (e.g. "the German HGB §257 says 10 years for these specific record classes") — l10n modules (`kontor-l10n-de-retention`).

**Heuristic**: if it's a UI surface, a clause library, a workflow visualizer, or a third-party API integration, defer to consumer apps. If it's an invariant over the data ("must not purge under hold", "every status transition needs a codified reason"), it belongs in the kernel.

## 5. Open questions

The research surfaces design calls that should be answered before any of section 3 lands as ADRs.

1. **Should `:retention-policy` be kernel or l10n?** Min/max retention periods are jurisdiction-specific (HGB §257 = 10y in DE, IRS Pub 583 = 3-7y in US). Kernel ships the entity shape; l10n ships the seeds. Probably kernel-shape + l10n-data, matching the ADR-026 effective-dated tax-rates pattern.
2. **Is `:legal-hold/scope` an EDN query or a structured FK?** EDN is flexible but unindexed; structured forces hold-creators to enumerate. Recommendation: hybrid — `:legal-hold/scope-query` (EDN) + `:legal-hold/scope-entity-ids` (computed cache, refreshed when query runs). The cache is what middleware checks at purge time.
3. **Does `:dsar-request` belong before or after `kontor-collections` (Stage L)?** Collections needs partner-level pause flags (ADR-039); DSAR-erasure on a partner with open AR is a hard case (you can't erase the partner, but you must erase the marketing-consent record). Probably *after* Stage M revrec, because revrec amplifies the "PII vs financial record" tension.
4. **Cap-table-of-record question.** Carta/Pulley own this market and there is no obvious wedge. Does kontor need *any* cap-table primitive beyond AcctgTrans for SBC, or is the answer simply "the cap-table lives at Carta, with monthly AcctgTrans postings from a sync job"? Recommendation: no first-class cap-table; document the Carta-sync pattern in `doc/architecture.md` as a reference integration.
5. **UTBMS code vocabulary — kernel or l10n?** Codes are US-bar-association origin but adopted internationally; LEDES files are de-facto standard. Probably kernel (small, public-domain) — same logic as `:account-type-direction` (ADR-041) being data not code.
6. **Privilege-tag and the multi-tenant question.** A privileged document for tenant A is just a document for tenant B. The tag itself is correct kernel-level; the role gating is consumer-side (auth/identity is not a kontor concern per ADR-010). Need to be careful not to leak privilege via cross-tenant query — but kontor's `:entity`-scoped queries (ADR-031) already handle the partition.
7. **How does `:contract-obligation` compose with `:performance-obligation` from Stage M revrec?** ASC 606 distinct-performance-obligations are revrec primitives; contract obligations are broader (renewal alerts, SLAs, indemnity caps). Recommendation: `:performance-obligation` is a subtype of `:contract-obligation` with `:kind :revenue-recognition`, sharing the schedule + state-machine plumbing.
8. **Should DocuSign certificate-of-completion ingestion be a kernel parser?** DocuSign exposes envelope events via webhook + a downloadable PDF certificate. The integrity-verification logic (PKI seal validation) is non-trivial. Recommendation: kernel ships the `:audit-doc/external-attestation-id` + `:audit-doc/external-attestation-vendor` fields; the verifier is a separate `kontor-esign-docusign` library, parallel to the e-invoicing vendor adapters (ADR-017).
9. **Bitemporal consent.** GDPR consent is the simplest example, but employment-policy consent, medical-data consent, marketing consent, terms-of-service acceptance are all the same shape. Worth a dedicated `:consent` entity (companion-level, probably in `kontor-partner`), or just `:status-history` on the partner with codified `:consent/given` and `:consent/withdrawn` reasons? Recommendation: try the simple shape first.
10. **Counsel-engagement security clearance** — if counsel matters carry attorney-client privilege markers, do *non-counsel* staff querying spend reports see the matter exist or not? E.g. CFO needs to see "outside legal cost = $X by practice area," but should not see the matter titles ("Investigation re Y"). Recommendation: a `:counsel-matter/privacy-level` keyword on the matter — `:title-private` hides the title from spend reports, `:full-private` removes the matter entirely from aggregate queries. Composes with `:privilege-tag` on the underlying docs.

## 6. Recommendation summary for kontor roadmap

**Do not start a "kontor-legal" stage now.** The substrate isn't pulled by the current showcase set (DE B2B, US multi-state, IN B2B/GSTR, multi-entity intercompany — none of these crash on legal/compliance gaps). But there are **three low-cost, high-leverage primitives** that can land opportunistically:

1. **`:legal-hold`** (kernel) — single entity + a purge-middleware extension. Closes the largest *structural* gap (purge-vs-hold race) and gives kontor a story that no big ERP has.
2. **`:retention-policy`** (kernel-shape, l10n-data) — single entity + a query helper. Lays the groundwork for jurisdiction-specific retention without committing to a full DSAR module.
3. **`:audit-doc/privilege`** + role-gated URI accessor (kernel attribute) — one new attribute + one new query helper. Closes the privilege-bleed gap for any document attached to a transaction.

Combined, these three are ~ADR-038-sized (one ADR, ~half a stage). They unblock future `kontor-counsel`, `kontor-privacy`, and `kontor-clm` companions without committing to them.

**Heavy stages (`kontor-counsel`, `kontor-privacy`, `kontor-clm`) wait until a real user story pulls them.** The current cross-stage-validation set (Showcases 1-4) doesn't. If a future showcase requires UTBMS-coded legal-spend invoices or a DSAR workflow, that's the pull.

## Sources

- SAP: [SAP S/4HANA for Enterprise Contract Management announcement](https://www.sap.com/assetdetail/2024/11/6a0d2cc3-e27e-0010-bca6-c68f7e60039b.html), [SAP CLM to Ariba migration note](https://community.sap.com/t5/spend-management-q-a/sap-clm-to-ariba-contracts-or-s-4hana-e-cm/qaq-p/12694235), [SAP ILM product page](https://www.sap.com/products/technology-platform/information-lifecycle-management.html), [SAP S/4 DMS course S4102](https://training.sap.com/course/s4102-document-management-in-sap-s4hana-classroom-023-g-en/), [SAP S/4 SoD review](https://help.sap.com/docs/SUPPORT_CONTENT/grc/3362387180.html)
- NetSuite: [Houseblend on SuiteApprovals + audit trail](https://www.houseblend.io/articles/netsuite-custom-approval-workflow), [Houseblend on NetSuite GDPR](https://www.houseblend.io/articles/netsuite-gdpr-compliance), [Houseblend on NetSuite GRC](https://www.houseblend.io/articles/netsuite-grc-compliance-features)
- Oracle: [Fusion Cloud Procurement Contracts datasheet](https://www.oracle.com/a/ocom/docs/applications/erp/oracle-procurement-contracts-cloud-ds.pdf), [Oracle Risk Management Cloud datasheet](https://www.oracle.com/a/ocom/docs/applications/erp/oracle-risk-management-cloud-ds.pdf)
- Odoo: [Odoo GDPR page](https://www.odoo.com/gdpr), [Odoo 18 Sign module docs](https://www.odoo.com/documentation/18.0/applications/productivity/sign.html), local source `addons/privacy_lookup/models/privacy_log.py`, `addons/data_recycle/models/data_recycle_record.py`
- Tryton: [Tryton audit-trail discussion](https://tryton.narkive.com/56s047tN/is-there-an-audit-trail-module), [Tryton attachment + S3 backend](https://pypi.org/project/tryton-filestore-s3/)
- CLM: [Juro on DocuSign-vs-Ironclad](https://juro.com/learn/docusign-clm-vs-ironclad-comparison), [Hyperstart Ironclad-vs-DocuSign buyer guide](https://www.hyperstart.com/blog/ironclad-vs-docusign/), [Business Software on contract obligation management + WCC 9% figure](https://www.business-software.com/blog/contract-obligation-management-using-clm-to-track-what-you-promised-and-what-they-owe/)
- E-signature: [DocuSign Trust + Certificate of Completion](https://www.docusign.com/trust/security/transaction-data-use), [DocuSign Certificate of Completion docs](https://support.docusign.com/s/document-item?language=en_US&bundleId=oeq1643226594604&topicId=gpa1578456339545.html)
- Cap-table: [Carta best-cap-table guide](https://carta.com/best-cap-table-software/), [The Startup Law Blog — what Carta/Pulley can't do](https://www.thestartuplawblog.com/cap-table-software-carta-pulley-angellist/), [Pulley 409A guide](https://pulley.com/guides/409a-valuation), [PwC SBC accounting](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/stockbased_compensat/stockbased_compensat__3_US/chapter_10_plan_desi_US/1010_summary_of_irc__US.html)
- Board portal: [Diligent Boards product](https://www.diligent.com/products/boards), [Diligent vs Boardvantage comparison](https://board-room.org/blog/compare-diligent-and-boardvantage/), [Diligent corporation overview (Wikipedia)](https://en.wikipedia.org/wiki/Diligent_Corporation)
- Counsel engagement: [Brightflag UTBMS guide](https://brightflag.com/resources/cracking-the-code-a-beginners-guide-to-understanding-utbms-codes/), [Onit UTBMS/LEDES explainer](https://www.onit.com/blog/what-is-ledes-utbms/), [UTBMS Wikipedia](https://en.wikipedia.org/wiki/Uniform_Task-Based_Management_System), [Brightflag on UTBMS+LEDES limits](https://brightflag.com/resources/utbms-and-ledes-codes-ai/)
- E-discovery: [Relativity overview](https://www.relativity.com/data-solutions/ediscovery/), [Logikcull eDiscovery product page](https://www.logikcull.com/product/ediscovery), [r/legaltech wiki — Relativity](https://www.rlegaltech.com/vendors/relativity/), [Native Legal AI comparison](https://native.legal/blog/ai-document-review-tools-comparison-2025)
- Privilege + privilege log: [Wikipedia Privilege log](https://en.wikipedia.org/wiki/Privilege_log), [ABA Crafting effective privilege logs](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-november/crafting-effective-privilege-logs-legal-success/), [Knovos The Privilege Puzzle](https://www.knovos.com/blog/the-privilege-puzzle-in-ediscovery-untangling-the-mystery/), [IAPP best practices](https://iapp.org/news/a/best-practice-considerations-for-preserving-attorney-client-privilege)
- Litigation hold: [TechTarget litigation-hold definition](https://www.techtarget.com/searchstorage/definition/litigation-hold), [Logikcull legal-holds guide](https://www.logikcull.com/learning/ultimate-guide/legal-hold), [Intradyn legal-holds tips](https://www.intradyn.com/how-to-implement-a-legal-hold/)
- Retention + SOX vs GDPR: [Pathlock SOX retention](https://pathlock.com/learn/sox-data-retention-requirements/), [TechTarget SOX retention 4 steps](https://www.techtarget.com/searchcio/tip/4-steps-to-remain-compliant-with-SOX-data-retention-policies), [Securiti data retention](https://securiti.ai/what-is-data-retention/)
- DSAR + privacy: [BigID DSAR guide](https://bigid.com/blog/what-is-dsar/), [DataGrail DSAR process](https://www.datagrail.io/blog/data-privacy/dsar-process/), [Termly DSAR 10-step](https://termly.io/resources/guides/dsar-process/), [Osano DSAR guide](https://www.osano.com/articles/data-subject-access-requests-guide), [Ethyca DSAR](https://ethyca.com/about-data-subject-requests-and-dsars)
- VDR + M&A: [Peony VDR cost guide April 2026](https://www.peony.ink/blog/virtual-data-room-cost-guide), [Peony top-10 VDR providers](https://www.peony.ink/blog/top-10-virtual-data-room-providers), [Dealroom VDR comparison](https://dealroom.net/resources/virtual-data-room-providers-comparison)
- Obligation tracking + auto-renewal: [ExpiryEdge hidden costs of forgotten renewals](https://expiryedge.com/blogs/the-hidden-costs-of-forgotten-renewals-how-businesses-lose-money/), [Sastrify SaaS auto-renewal trap](https://www.sastrify.com/blog/how-to-avoid-the-saas-auto-renewal-trap), [BetterCloud auto-renewal save](https://www.bettercloud.com/monitor/how-to-avoid-automatic-software-renewals-and-save-big/)
- GRC + SoD: [MTC Skopos vs SAP GRC](https://meylan-tc.com/en/blog/sap-grc-vs-skopos-sod-tools/), [Pathlock SAP Access Control](https://pathlock.com/learn/sap-access-key-capabilities-and-how-to-use-them-to-implement-sod/), [ISACA cross-system SoD monitoring](https://www.isaca.org/resources/isaca-journal/issues/2022/volume-6/benefits-and-challenges-of-implementing-cross-system-sod-monitoring-using-sap-grc)
- Revenue recognition + contract obligations: [Deloitte DART Chapter 14.2 Contract Liabilities](https://dart.deloitte.com/USDART/home/codification/revenue/asc606-10/roadmap-revenue-recognition/chapter-14-presentation/14-2-contract-liabilities), [PwC presenting contract-related assets and liabilities](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/financial_statement_/financial_statement___18_US/Chapter-33--Revenue-and-contract-costs/33-3-Presenting-contract-related-assets-and-liabilities-ASC-606.html), [RSM US revrec guide](https://rsmus.com/content/dam/rsm/insights/services/audit/1pdf/a-guide-to-revenue-recognition.pdf)
- Law-firm software: [Clio conflict-check how-to](https://www.clio.com/blog/conflict-check-how-to/), [Litify practice management](https://www.litify.com/full-service-practice-management-software)

Date: 2026-05-13. Single-agent research, no parallel agents. Verification: medium — claims are vendor-doc + analyst-blog cited but no source code inspection of CLM/cap-table/board-portal products (they are proprietary SaaS). The Odoo + Tryton claims are checked against local source.
