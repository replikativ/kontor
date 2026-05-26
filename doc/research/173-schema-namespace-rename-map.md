---
date: 2026-05-25
title: 173 — Schema-namespace rename map (`:kontor.*` prefix for v0.1.0-alpha)
audience: maintainer + the W1-B execution agent
status: AUDIT — plan; no code change. Awaiting maintainer sign-off on §7 design calls
related: notes 166 §1.9 (tax-group naming collision), 167 §1.1 (fiscal-unit), 168, 170, 171, 172 (clean-v2 reimplementation plan)
---

# 173 — Schema-namespace rename map (`:kontor.*` prefix)

kontor will go public as a Clojure library that consumers wire into their
own datahike-using apps. Today every kernel attribute is generically named
(`:account/path`, `:posting/amount`, `:person/name`) — names that *silently
collide* with any consumer app using the same datahike DB. The fix is the
standard library-author move: prefix every kontor-owned attribute with
`:kontor.*` (so `:account/path` → `:kontor.account/path`,
`:posting/amount` → `:kontor.posting/amount`, …). Cohabitation (ADR-002)
becomes *enforced by namespace*, not by gentleman's agreement.

This is the AUDIT phase. NO CODE CHANGE in this commit. Once the
maintainer signs off on §7, the W1-B execution agent runs the batches
in §6 and lands the rename behind a single `feat(schema)!:` commit per
batch.

## §1. Methodology

Every keyword in the codebase is classified into one of four buckets:

### 1.1 Attribute namespace — RENAME

The X in `:X/attr`, where `:X/attr` appears in `:db/ident` somewhere
(kernel `src/kontor/schema.clj` or any `modules/**/schema.clj`). These are
the keys consumers will see in `(d/pull conn [:X/y] eid)`. Every one of
these gets the `:kontor.*` prefix.

Evidence pattern:
```bash
grep ":db/ident" src/kontor/schema.clj modules/*/src/kontor/*/schema.clj \
  | grep -oE ':[a-z][a-z0-9-]*/' | sort -u
```

### 1.2 Attribute-value keyword — DO NOT RENAME

The *value* a keyword-typed attr takes, e.g. `:journal/type :general`
(where `:general` is the value). These are not part of any ident; they
live inside the data, not in the schema's `:db/ident` declarations.
Renaming them would break every test asserting on values + the closed
enums shipped by ADR-101 / ADR-099 / ADR-034.

Examples of value-keywords (NOT renamed):
- `:asset | :liability | :equity | :income | :expense` (account types)
- `:sale | :purchase | :cash | :bank | :general` (journal types)
- `:customer | :vendor | :both` (partner kinds)
- `:draft | :posted | :pending-attestation | :cancelled` (tx states)
- `:fifo | :lifo | :avg | :standard | :specific` (cost methods)
- `:debit | :credit` (directions)
- `:placed | :pending-review | :released | :expired` (legal-hold states)
- `:single-base | :per-member-with-netting` (fiscal-unit regime shape)
- `:participation-exemption | :rollover-relief | :loss-bucket | :lifetime-cap`
  (ADR-101 closed tax-concept enum)
- All `:de`, `:fr`, `:us-fed`, `:ca-on`, etc. used as jurisdiction values
  on `:provision/jurisdiction`, `:tax/authority`, `:account-code/regulator`

### 1.3 Datahike-owned — NEVER RENAME

`:db/*`, `:tx/*`, `:db.type/*`, `:db.cardinality/*`, `:db.unique/*`,
`:db.valid/from`, `:db.valid/to`, `:db.alter/*`, `:db.install/*`,
`:db.part/*`, `:db.fn/*`, `:db.sys/*`. These are owned by the underlying
substrate. Touching them breaks datahike compatibility — they have *defined
meaning* upstream.

Note: `:tx/valid-from` is no longer in kontor — it was migrated to
upstream `:db.valid/from` (ADR-048 history). Verify the rename agent
does NOT introduce `:kontor.tx/*` anywhere; the migration is closed.

### 1.4 Closed-enum value (sub-case of 1.2)

A keyword that participates in a closed enum *declared by an ADR* — e.g.
the 14-concept `:tax-concept/code` starter set (`:participation-exemption`,
`:rollover-relief`, …) per ADR-101, or the 8-value `period-tax-kinds` per
ADR-099. These values live in `:parameter-value` / `:tax-concept/code` /
`:provision/condition` strings — they are DATA, not attribute idents. DO
NOT RENAME them. They appear in `:db/ident` declarations only when seeded
as installed entities, not as schema attributes.

### 1.5 Country-code value keywords — DO NOT RENAME

Pattern `:ar`, `:br`, `:ca`, `:cl`, `:cn`, `:de`, `:fr`, `:in`, `:jp`,
`:kr`, `:mx`, `:sa`, `:tr`, `:us-fed`, `:ca-on`, plus regulator codes
`:br/sefaz`, `:cn/sta`, `:de/finanzamt`, `:ca/cra`, `:ar/afip`,
`:cl/sii`, `:mx/cfdi-pagos-2.0`, `:complemento/sequence`, `:ubl/factur-x-additional-doc`.

Confirmed by grep — schema.clj contains 10 `:br/`, 7 `:cn/`, 5 `:in/`,
5 `:mx/`, 4 `:de/`, 2 `:ca/`, 2 `:sap/`, 1 `:ar/`, 1 `:sat/`, 1 `:ubl/`,
1 `:un/`, 1 `:ifrs/` — *all values*, not attribute namespaces (verified
by inspection — they appear inside doc strings or as values on
`:account-code/regulator`, `:document-type/jurisdiction`,
`:transaction/clearance-format`, `:attestation/format`,
`:complemento/format`, `:state-code/regulator`).

The lone exception: `:complemento/*` IS an attribute namespace (kernel
schema, ADR-025). The bare keyword `:complemento` standing alone is the
format-discriminator value; the `:complemento/transaction` /
`:complemento/namespace` / `:complemento/format` family is real schema.
Renamed accordingly.

## §2. Kernel rename map

The full inventory from
`grep ":db/ident" src/kontor/schema.clj | grep -oE ':[a-z][a-z0-9-]*/' | sort -u`
(67 attribute namespaces). One row per namespace.

| # | Current | Proposed `:kontor.*` | Sample attrs (3-5) | Approx line range | Risk |
|---|---|---|---|---|---|
|  1 | `:account` | `:kontor.account` | `:account/path` `:account/code` `:account/type` `:account/commodity` `:account/external-codes` | 244-351 | **HIGH — 1681 refs** |
|  2 | `:account-code` | `:kontor.account-code` | `:account-code/account` `:account-code/regulator` `:account-code/code` `:account-code/identity` | 425-454 | low |
|  3 | `:account-tag` | `:kontor.account-tag` | `:account-tag/name` `:account-tag/country-code` `:account-tag/applicability` `:account-tag/concept-iri` | 465-517 | low |
|  4 | `:account-type-direction` | `:kontor.account-type-direction` | `:account-type-direction/invoice-type` `:account-type-direction/account-type` `:account-type-direction/direction` | 1704-1731 | low |
|  5 | `:analytic-account` | `:kontor.analytic-account` | `:analytic-account/path` `:analytic-account/code` `:analytic-account/plan` `:analytic-account/parent` | 2981-3008 | low |
|  6 | `:analytic-distribution` | `:kontor.analytic-distribution` | `:analytic-distribution/plan` `:analytic-distribution/account` `:analytic-distribution/percent` `:analytic-distribution/posting` | 3010-3036 | low |
|  7 | `:analytic-plan` | `:kontor.analytic-plan` | `:analytic-plan/code` `:analytic-plan/name` `:analytic-plan/applicability` `:analytic-plan/active` | 2959-2979 | low |
|  8 | `:approval-policy` | `:kontor.approval-policy` | `:approval-policy/entity-type` `:approval-policy/facet` `:approval-policy/transition-from` `:approval-policy/rule` | 4266-4320 | low |
|  9 | `:attestation` | `:kontor.attestation` | `:attestation/transaction` `:attestation/format` `:attestation/token` `:attestation/state` | 3317-3394 | low |
| 10 | `:audit-doc` | `:kontor.audit-doc` | `:audit-doc/code` `:audit-doc/type` `:audit-doc/privilege` `:audit-doc/category` `:audit-doc/language` | 4161-4264 | **HIGH — 869 refs** |
| 11 | `:balance-assertion` | `:kontor.balance-assertion` | `:balance-assertion/account` `:balance-assertion/at` `:balance-assertion/amount` `:balance-assertion/commodity` | 2129-2151 | low |
| 12 | `:bank-account` | `:kontor.bank-account` | `:bank-account/code` `:bank-account/iban` `:bank-account/bic` `:bank-account/account-number` | 1442-1495 | low |
| 13 | `:bank-line` | `:kontor.bank-line` | `:bank-line/external-id` `:bank-line/bank` `:bank-line/source-account` `:bank-line/amount` | 2846-2941 | medium — bank-importer companions |
| 14 | `:commodity` | `:kontor.commodity` | `:commodity/symbol` `:commodity/name` `:commodity/precision` `:commodity/iso-4217` `:commodity/concept-iri` | 64-99 | **HIGH — 749 refs** |
| 15 | `:complemento` | `:kontor.complemento` | `:complemento/transaction` `:complemento/namespace` `:complemento/format` `:complemento/sequence` | 3422-3470 | low — MX only |
| 16 | `:country` | `:kontor.country` | `:country/code` `:country/code-iso3` `:country/name` `:country/default-commodity` `:country/groups` | 3130-3171 | low |
| 17 | `:country-code` | `:kontor.country-code` | `:country-code/country` `:country-code/regulator` `:country-code/code` `:country-code/identity` | 3173-3202 | low |
| 18 | `:country-group` | `:kontor.country-group` | `:country-group/code` `:country-group/name` | 3204-3214 | low |
| 19 | `:create` | `:kontor.audit.create` | `:create/uid` | 42-47 | medium — special-case, see §7-3 |
| 20 | `:cross-tx` | `:kontor.cross-tx` | `:cross-tx/step-id` | 1605-1615 | low |
| 21 | `:document-type` | `:kontor.document-type` | `:document-type/code` `:document-type/jurisdiction` `:document-type/identity` `:document-type/concept-iri` | 361-423 | low |
| 22 | `:dsar-request` | `:kontor.dsar-request` | `:dsar-request/external-id` `:dsar-request/partner` `:dsar-request/kind` `:dsar-request/state` | 954-1052 | low |
| 23 | `:entity` | `:kontor.entity` | `:entity/code` `:entity/name` `:entity/parent-entity` `:entity/lei` `:entity/legal-form` | 3650-3762 | medium — 462 refs |
| 24 | `:fiscal-position` | `:kontor.fiscal-position` | `:fiscal-position/name` `:fiscal-position/country-code` `:fiscal-position/auto-apply` `:fiscal-position/vat-required` | 1822-1841 | low |
| 25 | `:fx-rate` | `:kontor.fx-rate` | `:fx-rate/from-commodity` `:fx-rate/to-commodity` `:fx-rate/at-date` `:fx-rate/rate` `:fx-rate/by-tuple` | 126-186 | low |
| 26 | `:invoice` | `:kontor.invoice` | `:invoice/external-id` `:invoice/issue-date` `:invoice/buyer` `:invoice/status` `:invoice/factur-x-xml` | 2658-2770 | **HIGH — 1509 refs, COLLIDES with companions/invoice + companions/collections + companions/procurement**; see §7-5 |
| 27 | `:invoice-line` | `:kontor.invoice-line` | `:invoice-line/invoice` `:invoice-line/sequence` `:invoice-line/quantity` `:invoice-line/vat-rate` | 2772-2825 | **HIGH — 929 refs, same collision** |
| 28 | `:journal` | `:kontor.journal` | `:journal/code` `:journal/name` `:journal/type` `:journal/default-account` `:journal/sequence-prefix` | 527-559 | medium — 778 refs |
| 29 | `:layer-adjustment` | `:kontor.layer-adjustment` | `:layer-adjustment/layer` `:layer-adjustment/amount` `:layer-adjustment/reason` `:layer-adjustment/origin-transaction` | 3961-3992 | low |
| 30 | `:layer-consumption` | `:kontor.layer-consumption` | `:layer-consumption/layer` `:layer-consumption/qty` `:layer-consumption/issue-transaction` | 3609-3638 | low |
| 31 | `:ledger` | `:kontor.ledger` | `:ledger/code` `:ledger/name` `:ledger/type` `:ledger/framework` `:ledger/commodity` `:ledger/entity` | 3076-3109 + 3812-3819 | low — 112 refs |
| 32 | `:legal-hold` | `:kontor.legal-hold` | `:legal-hold/code` `:legal-hold/state` `:legal-hold/scope-eids` `:legal-hold/scope-query` `:legal-hold/supporting-doc` | 702-808 | low |
| 33 | `:lot` | `:kontor.lot` | `:lot/commodity` `:lot/acquired-at` `:lot/cost-basis` `:lot/label` `:lot/expires-at` | 198-229 | low |
| 34 | `:parameter` | `:kontor.parameter` | `:parameter/code` `:parameter/label` `:parameter/jurisdiction` `:parameter/unit` `:parameter/concept-iri` | 1288-1333 | **HIGH — 2206 refs (largest, all in l10n provision data)** |
| 35 | `:parameter-bracket` | `:kontor.parameter-bracket` | `:parameter-bracket/parameter` `:parameter-bracket/index` `:parameter-bracket/rate` `:parameter-bracket/upper` | 1366-1394 | medium — l10n provisions |
| 36 | `:parameter-value` | `:kontor.parameter-value` | `:parameter-value/parameter` `:parameter-value/effective-from` `:parameter-value/decimal-value` `:parameter-value/citation` | 1335-1364 | medium — l10n provisions |
| 37 | `:partner` | `:kontor.partner` | `:partner/external-id` `:partner/name` `:partner/kind` `:partner/country-code` `:partner/tax-id` `:partner/credit-status` `:partner/kyc-status` `:partner/concept-iri` | 569-655 + 3283-3289 | **HIGH — 588 refs, partial collision with companions/partner + companions/hr** (richer person model); see §3 |
| 38 | `:partner-bank-account` | `:kontor.partner-bank-account` | `:partner-bank-account/partner` `:partner-bank-account/bank-account` `:partner-bank-account/purpose` `:partner-bank-account/identity` | 1497-1540 | low |
| 39 | `:partner-merge` | `:kontor.partner-merge` | `:partner-merge/duplicate-of` `:partner-merge/superseded` `:partner-merge/merged-at` `:partner-merge/identity` | 1398-1440 | low |
| 40 | `:partner-tag` | `:kontor.partner-tag` | `:partner-tag/partner` `:partner-tag/tag-type` `:partner-tag/from-date` `:partner-tag/identity` | 1783-1811 | low |
| 41 | `:partner-tax-id` | `:kontor.partner-tax-id` | `:partner-tax-id/partner` `:partner-tax-id/country` `:partner-tax-id/tax-id-type` `:partner-tax-id/tax-id` | 1733-1781 | low |
| 42 | `:payment-application` | `:kontor.payment-application` | `:payment-application/payment` `:payment-application/invoice` `:payment-application/amount` `:payment-application/strategy` | 1623-1702 | low |
| 43 | `:payment-term` | `:kontor.payment-term` | `:payment-term/code` `:payment-term/name` `:payment-term/net-days` `:payment-term/discount-pct` | 2606-2639 | low |
| 44 | `:period` | `:kontor.period` | `:period/start` `:period/end` `:period/journal` `:period/locked-at` `:period/sealed-at` `:period/tag` | 2046-2118 | medium — 415 refs |
| 45 | `:person` | `:kontor.person` | `:person/birth-date` `:person/national-id` (kernel-shared) | 676-693 | **HIGH — consolidates with `:hr-person/*` (note 95). 91 refs in `:person/*`; see §3 + §7-2** |
| 46 | `:posting` | `:kontor.posting` | `:posting/transaction` `:posting/account` `:posting/amount` `:posting/commodity` `:posting/entity` `:posting/ledger` `:posting/dimensions` | 2346-2514 + 3042-3049 + 3111-3119 + 3764-3777 | **HIGHEST — 2320 refs (the load-bearing kernel namespace)** |
| 47 | `:posting-dimension` | `:kontor.posting-dimension` | `:posting-dimension/axis` `:posting-dimension/value` | 2530-2544 | low |
| 48 | `:provision` | `:kontor.provision` | `:provision/code` `:provision/jurisdiction` `:provision/concept` `:provision/condition` `:provision/consequence` `:provision/compute-fn` | 1102-1239 | **HIGH — 1053 refs, ADR-101 statute-as-data; verify `:provision/condition` EDN strings don't reference renamed attrs (they don't — they reference `:tax-context` facts, not kontor attrs)** |
| 49 | `:regime` | `:kontor.regime` | `:regime/code` `:regime/label` `:regime/jurisdiction` `:regime/extends` `:regime/effective-from` | 1241-1286 | low |
| 50 | `:retention-policy` | `:kontor.retention-policy` | `:retention-policy/code` `:retention-policy/applies-to` `:retention-policy/category` `:retention-policy/expiry-action` | 814-948 | low |
| 51 | `:schedule` | `:kontor.schedule` | `:schedule/code` `:schedule/name` `:schedule/kind` `:schedule/origin-entity` `:schedule/start-date` | 3839-3910 | medium — 262 refs |
| 52 | `:schedule-occurrence` | `:kontor.schedule-occurrence` | `:schedule-occurrence/schedule` `:schedule-occurrence/sequence` `:schedule-occurrence/scheduled-date` `:schedule-occurrence/transaction` | 3912-3959 | low |
| 53 | `:side-effect-intent` | `:kontor.side-effect-intent` | `:side-effect-intent/key` `:side-effect-intent/type` `:side-effect-intent/payload` `:side-effect-intent/status` | 1542-1597 | low |
| 54 | `:state` | `:kontor.state` | `:state/country` `:state/code` `:state/name` `:state/identity` `:state/external-codes` | 3216-3249 | medium — 50-state US, 28-state IN |
| 55 | `:state-code` | `:kontor.state-code` | `:state-code/state` `:state-code/regulator` `:state-code/code` `:state-code/identity` | 3251-3281 | low |
| 56 | `:status-history` | `:kontor.status-history` | `:status-history/entity` `:status-history/entity-type` `:status-history/from` `:status-history/to` `:status-history/changed-at` `:status-history/origin-transaction` | 4078-4151 | low |
| 57 | `:status-transition` | `:kontor.status-transition` | `:status-transition/entity-type` `:status-transition/facet` `:status-transition/from` `:status-transition/to` `:status-transition/auto-after-millis` | 4002-4076 | low |
| 58 | `:tax` | `:kontor.tax` | `:tax/code` `:tax/name` `:tax/country-code` `:tax/type-tax-use` `:tax/amount-type` `:tax/mechanism` `:tax/authority` `:tax/effective-from` `:tax/concept-iri` | 1857-1964 + 3488-3501 | medium — 109 refs |
| 59 | `:tax-application` | `:kontor.tax-application` | `:tax-application/posting` `:tax-application/tax` `:tax-application/base` `:tax-application/amount` `:tax-application/compound-on` | 2546-2596 | low |
| 60 | `:tax-concept` | `:kontor.tax-concept` | `:tax-concept/code` `:tax-concept/label` `:tax-concept/family` `:tax-concept/concept-iri` | 1060-1100 | low |
| 61 | `:tax-group` | **`:kontor.vat-group` (SEMANTIC rename)** | `:tax-group/name` `:tax-group/country-code` `:tax-group/payable-account` `:tax-group/receivable-account` | 2012-2030 | **HIGH — see §7-1 (frees up name for fiscal-unit per note 167)** |
| 62 | `:tax-rep` | `:kontor.tax-rep` | `:tax-rep/tax` `:tax-rep/document-type` `:tax-rep/repartition-type` `:tax-rep/factor-percent` `:tax-rep/account` `:tax-rep/tags` | 1966-2010 | low |
| 63 | `:transaction` | `:kontor.transaction` | `:transaction/external-id` `:transaction/journal` `:transaction/effective-date` `:transaction/state` `:transaction/clearance-token` `:transaction/document-type` `:transaction/posted-at` `:transaction/reverses` `:transaction/place-of-supply` `:transaction/attestations` `:transaction/complementos` `:transaction/intercompany-pair-id` | 2167-2331 + 3291-3304 + 3396-3409 + 3472-3476 + 3779-3810 | **HIGH — 1238 refs** |
| 64 | `:valuation-book` | `:kontor.valuation-book` | `:valuation-book/code` `:valuation-book/framework` `:valuation-book/cost-method` `:valuation-book/commodity` `:valuation-book/entity` | 3512-3545 + 3821-3827 | low |
| 65 | `:valuation-layer` | `:kontor.valuation-layer` | `:valuation-layer/book` `:valuation-layer/item` `:valuation-layer/origin-transaction` `:valuation-layer/qty-original` `:valuation-layer/unit-cost-original` | 3556-3607 | low |
| 66 | `:write` | `:kontor.audit.write` | `:write/uid` | 49-53 | medium — special-case, see §7-3 |
| -- | `:db/*` `:tx/*` `:db.type/*` `:db.cardinality/*` `:db.unique/*` `:db.valid/*` | **NEVER RENAME — datahike-owned** | (system-installed) | n/a | n/a |

Total: **66 kernel namespaces to rename**, plus 2 audit-trail micro-namespaces
(`:create/uid`, `:write/uid`) requiring a design call (§7-3).

### Namespaces inventoried but NOT in kernel schema

The task brief listed these — confirmed by grep they are NOT attribute
namespaces in `src/kontor/schema.clj`:

- `:customer` — beleg-side, not kontor-kernel
- `:tax/effective-from` is *under* `:tax/*` (added by tax-effective-window-attrs, ADR-026); no separate `:tax-effective-window` namespace
- `:partner-state` is a *namespace shared with `:partner/*`* (the `partner-state-attrs` def adds `:partner/state` — same `:partner` namespace, not a new one)
- All `:ar :br :ca :cl :cn :de :fr :in :it :jp :kr :mx :sa :tr :complemento :ifrs :sap :sat :ubl :un` are VALUE keywords (per §1.5)

## §3. Companion rename map

15 companion modules ship a `schema.clj` (inventory:
`find modules -name schema.clj`). Per-companion namespace inventory:

| Companion | Current namespace(s) | Proposed `:kontor.*` | Collisions / notes |
|---|---|---|---|
| **asset** | `:asset`, `:asset-class`, `:asset-depreciation`, `:asset-event`, `:asset-method-params` | `:kontor.asset`, `:kontor.asset-class`, `:kontor.asset-depreciation`, `:kontor.asset-event`, `:kontor.asset-method-params` | none |
| **authz** | `:authz` | `:kontor.authz` | none |
| **collections** | `:collection-case`, `:credit-hold`, `:dispute`, `:dunning-event`, `:dunning-pause`, `:dunning-policy`, `:invoice`, `:payment-promise` | `:kontor.collection-case`, `:kontor.credit-hold`, `:kontor.dispute`, `:kontor.dunning-event`, `:kontor.dunning-pause`, `:kontor.dunning-policy`, `:kontor.invoice` (companion extends), `:kontor.payment-promise` | `:invoice/*` collision — see §7-5 |
| **commitment** | `:commitment`, `:commitment-fulfillment` | `:kontor.commitment`, `:kontor.commitment-fulfillment` | none |
| **disposal** | `:disposal` | `:kontor.disposal` | none |
| **expense** | `:expense-line`, `:expense-report` | `:kontor.expense-line`, `:kontor.expense-report` | none |
| **hr** | `:compensation`, `:compensation-component`, `:consent`, `:department`, `:employment`, `:hr-person`, `:partner` (richer extension!), `:pay-period`, `:payroll-run`, `:person` (richer overlay!) | `:kontor.compensation`, `:kontor.compensation-component`, `:kontor.consent`, `:kontor.department`, `:kontor.employment`, **`:kontor.hr.person`** (extends kernel `:kontor.person`), `:kontor.partner` (extends kernel `:kontor.partner`), `:kontor.pay-period`, `:kontor.payroll-run`, **MERGE into `:kontor.person/*`** (consolidation principle) | **CRITICAL** — consolidation per §7-2: hr's `:person/*` extras (given-name, last-name, gender, kind, state, partner) merge into kernel `:kontor.person/*`; only HR-genuinely-distinct attrs (e.g. `:hr-person/national-id-doc`) become `:kontor.hr.person/*`. Audit confirms 9 top-level `:person/*` attrs across hr + partner, of which 1 (`national-id-doc`) is HR-only and 8 are reusable. |
| **import-edgar** | `:reported-fact` | `:kontor.reported-fact` | none |
| **inventory** | `:facility`, `:facility-location`, `:facility-product`, `:inventory-detail`, `:inventory-item`, `:inventory-transfer`, `:inventory-variance`, `:negative-fill`, `:physical-inventory` | `:kontor.facility`, `:kontor.facility-location`, `:kontor.facility-product`, `:kontor.inventory-detail`, `:kontor.inventory-item`, `:kontor.inventory-transfer`, `:kontor.inventory-variance`, `:kontor.negative-fill`, `:kontor.physical-inventory` | none |
| **invoice** | `:gl-account-default`, `:invoice` (extends kernel), `:invoice-line` (extends kernel), `:order-item-billing` | `:kontor.gl-account-default`, `:kontor.invoice` (companion is canonical), `:kontor.invoice-line` (companion is canonical), `:kontor.order-item-billing` | **§7-5 — consolidate. Kernel `:invoice/*` + companion `:invoice/*` both contribute attrs; recommended: kernel drops `:invoice/*` attrs (they were a thin scaffold for the einvoice path); companion `kontor-invoice` owns the namespace. Cross-confirms note 172 §5 finding 2.** |
| **lease** | `:lease`, `:lease-liability`, `:lease-modification` | `:kontor.lease`, `:kontor.lease-liability`, `:kontor.lease-modification` | none |
| **partner** | `:contact-mech`, `:email-address`, `:org`, `:partner` (richer extension!), `:partner-contact-mech`, `:partner-contact-mech-purpose`, `:partner-relationship`, `:partner-role`, `:person` (richer overlay!), `:postal-address`, `:telecom-number` | `:kontor.contact-mech`, `:kontor.email-address`, `:kontor.org`, `:kontor.partner` (kernel + companion overlay), `:kontor.partner-contact-mech`, `:kontor.partner-contact-mech-purpose`, `:kontor.partner-relationship`, `:kontor.partner-role`, `:kontor.person` (overlays kernel), `:kontor.postal-address`, `:kontor.telecom-number` | **Same consolidation as HR (§7-2)** — partner's 8 `:person/*` extras (`:person/first-name`, `:person/last-name`, `:person/gender`, `:person/kind`, `:person/partner`, `:person/state`, `:person/external-id`, `:person/birth-date`) merge into kernel `:kontor.person/*`. Existing kernel ships `:person/birth-date` + `:person/national-id` (2 attrs). Net kernel after merge: 9-10 attrs. |
| **people-record** | `:performance-review`, `:position-held`, `:promotion` | `:kontor.performance-review`, `:kontor.position-held`, `:kontor.promotion` | none |
| **procurement** | `:invoice` (vendor-bill overlay!), `:match-tolerance`, `:order-item`, `:order-item-assoc`, `:receipt`, `:receipt-invoice-billing`, `:receipt-item`, `:requirement`, `:requirement-commitment`, `:return`, `:return-item`, `:return-item-billing`, `:return-response`, `:service-acceptance` | `:kontor.invoice` (procurement is THIRD contributor — §7-5), `:kontor.match-tolerance`, `:kontor.order-item` (procurement-specific is PO line, sales-specific is SO line — COLLISION with sales!), `:kontor.order-item-assoc`, `:kontor.receipt`, `:kontor.receipt-invoice-billing`, `:kontor.receipt-item`, `:kontor.requirement`, `:kontor.requirement-commitment`, `:kontor.return`, `:kontor.return-item`, `:kontor.return-item-billing`, `:kontor.return-response`, `:kontor.service-acceptance` | **NEW COLLISION discovered** — `:order-item/*` lives in BOTH procurement and sales. Recommend `:kontor.procurement.order-item/*` + `:kontor.sales.order-item/*` (sub-namespacing — the dot is the discriminator). Confirm in §7-6. Same shape: `:order/*` lives in BOTH sales (sales order) and procurement (purchase order is `:requirement/*` already). Verified sales has `:order/*`; procurement does not. |
| **sales** | `:inv-reservation`, `:order`, `:order-adjustment`, `:order-item`, `:order-role`, `:ship-group`, `:ship-group-assoc` | `:kontor.inv-reservation`, `:kontor.sales.order` (vs procurement.order-item), `:kontor.order-adjustment`, `:kontor.sales.order-item`, `:kontor.order-role`, `:kontor.ship-group`, `:kontor.ship-group-assoc` | **§7-6 — sales/procurement `:order-item/*` split** |

Total: **15 companions, ~70 distinct namespaces**. Net after consolidation
(invoice merge in §7-5 + person merge in §7-2 + order-item split in §7-6):
expect ~72 final companion-prefixed namespaces.

### Module-collision summary

| Collision | Locations | Resolution |
|---|---|---|
| `:person/*` | kernel + hr + partner | Merge to single kernel `:kontor.person/*` (§3 + §7-2). HR-only extras → `:kontor.hr.person/*`. |
| `:invoice/*` | kernel + companions/invoice + companions/collections + companions/procurement | Consolidate: companions/invoice is canonical `:kontor.invoice/*`; kernel drops its `:invoice/*` (no kernel-specific need beyond `:transaction/document-type :invoice` reference); collections + procurement extend the companion namespace by adding their own attrs to it (each is additive, no key conflict per grep audit). §7-5. |
| `:invoice-line/*` | kernel + companions/invoice | Same as invoice — kernel drops, companion owns. |
| `:partner/*` | kernel + hr (1 attr: `:partner/person`?) + partner (rich) + collections (via `:credit-hold`) | Kernel keeps as `:kontor.partner/*`; companions extend additively (no key conflicts on the rename target). §7-4. |
| `:order-item/*` | sales + procurement | Sub-namespace per side: `:kontor.sales.order-item/*` + `:kontor.procurement.order-item/*`. §7-6. |
| `:order/*` | sales only | No collision — `:kontor.sales.order/*` (or flat `:kontor.order/*`; recommend dotted for symmetry with order-item). |

## §4. Resources / l10n / docs surface

Beyond `src/` + `test/`, the rename reaches:

### 4.1 `resources/invariants/*.edn`

Two files. Both contain hardcoded kontor attr-name references inside
datalog queries:

- `resources/invariants/account_active.edn` — references `:posting/account`, `:account/active`. Both rename to `:kontor.posting/account`, `:kontor.account/active`. The 30 lines of explanatory comment also reference these and must be updated.
- `resources/invariants/commodity_match.edn` — references `:posting/account`, `:posting/commodity`, `:account/commodity`. All three rename.

Note: the `.claude/worktrees/agent-*/resources/invariants/*` copies are scratch agent worktrees, NOT shipped artifacts; the rename agent should leave them untouched (or the maintainer will clean them up in a separate sweep).

### 4.2 `modules/l10n-*/resources/**/*.edn`

12 EDN data files across l10n charts of accounts:

| File | Attrs referenced |
|---|---|
| `modules/l10n-de/resources/kontor/l10n_de/skr04.edn` | `:code`, `:path`, `:type`, `:name`, `:reconcilable?`, `:commodity`, `:tax-recoverable?`, `:tax-payable?`, `:tags` — **these are unqualified consumer keys**, mapped by the install code to `:account/code`, `:account/path`, `:account/type`, `:account/name`, `:account/reconcilable`, `:account/commodity`, `:account/tags`. So the EDN itself does NOT need rename, but the install code (`modules/l10n-de/src/.../install.clj`) does. |
| `modules/l10n-at/resources/kontor/l10n_at/kontenrahmen.edn` | same pattern |
| `modules/l10n-au/resources/kontor/l10n_au/chart.edn` | same pattern |
| `modules/l10n-br/resources/kontor/l10n_br/chart.edn` | same pattern |
| `modules/l10n-ca/resources/kontor/l10n_ca/chart.edn` | same pattern |
| `modules/l10n-cn/resources/kontor/l10n_cn/chart.edn` | same pattern |
| `modules/l10n-fr/resources/kontor/l10n_fr/pcg.edn` | same pattern |
| `modules/l10n-in/resources/kontor/l10n_in/chart.edn` | same pattern |
| `modules/l10n-jp/resources/kontor/l10n_jp/chart.edn` | same pattern |
| `modules/l10n-mx/resources/kontor/l10n_mx/chart.edn` | same pattern |
| `modules/l10n-us/resources/kontor/l10n_us/chart.edn` | same pattern |
| `modules/payroll-{br,ca,fr,in,au,jp}/resources/.../coa_starter.edn` | same pattern |
| `modules/payroll-us-adp/resources/.../wage_type_map_reference.edn` | reference data only, no schema refs |

**Audit conclusion**: l10n EDN data files use *unqualified* short keys
(`:code`, `:path`, `:type`) for ergonomics — the install code is what
maps them to fully-qualified attribute idents. So no EDN edits needed;
only the per-module install/seed Clojure code touches the renamed attrs.

### 4.3 ADR-101 `:provision/condition` and `:provision/consequence` strings

`:provision/condition` + `:provision/consequence` are EDN-encoded predicate
expressions / consequence maps stored as strings (per ADR-101 §D7). They
reference a closed vocabulary over `:tax-context` facts — NOT kontor
attribute idents. Verified by inspection of `modules/l10n-{de,fr,jp,ca,br,in}/src/.../cit_statute.clj` and `investment_income_statute.clj`. No rename required inside these EDN strings.

The l10n provider .clj files DO reference renamed kernel attrs at the
top level (`:account/path`, `:posting/amount`, `:transaction/effective-date`)
— those are standard Clojure code paths and rename normally.

### 4.4 `doc/decisions.md`

Grep `grep -roh ":[a-z][a-z0-9-]*/" doc/decisions.md | sort | uniq -c | sort -rn | head -40` shows the top references:

| Namespace | Occurrences |
|---|---|
| `:posting/` | 135 |
| `:transaction/` | 98 |
| `:audit-doc/` | 94 |
| `:invoice/` | 76 |
| `:partner/` | 58 |
| `:tax-application/` | 52 |
| `:account/` | 42 |
| `:tax/` | 40 |
| `:invoice-line/` | 39 |
| ... | (44 more) |

Total ~1300 attr-name references in `doc/decisions.md` (the file is ~14k
lines). W5 (ADR distillation per task #357) will rewrite/condense the
ADRs anyway. W1-B should NOT touch `doc/decisions.md` during the rename —
flag the count, leave it to W5.

### 4.5 `CLAUDE.md`

The "Conventions/Namespacing" section explicitly enumerates the namespace
list, and "File layout summary" mentions several. ~20 attr-name references
total. **W1-B MUST update CLAUDE.md as part of the rename** (it's the
canonical list for future contributors; leaving it stale would mislead
the next agent).

### 4.6 `test/**/*.clj` + `modules/*/test/**/*.clj`

Sizing via grep (`grep -rh ":NAME/" test/ modules/*/test/`):

| Namespace | test/ refs | modules/*/test refs | Total test refs |
|---|---|---|---|
| `:account` | 629 | 801 | 1430 |
| `:posting` | 462 | 763 | 1225 |
| `:transaction` | 365 | 306 | 671 |
| `:commodity` | 201 | 414 | 615 |
| `:invoice` | 87 | 807 | 894 |
| `:invoice-line` | 30 | 545 | 575 |
| `:journal` | 204 | 415 | 619 |
| `:partner` | 139 | 328 | 467 |
| `:entity` | 138 | 230 | 368 |
| `:audit-doc` | 86 | 297 | 383 |
| `:period` | 33 | 273 | 306 |
| ... | | | |

Aggregate test surface: ~**8000 attr-name references** in `test/` +
`modules/*/test/` combined. All resolved by mechanical find-replace —
no test logic changes needed.

## §5. Risk analysis (top 5 highest-touch namespaces)

The five most-touched namespaces account for ~70% of the rename surface.

### 5.1 `:posting/*` — 2320 refs

- src/: 394 refs across `kontor.posting`, `kontor.book`, `kontor.balance`, `kontor.trial`, `kontor.consolidation`, `kontor.tax-posting-builder`, `kontor.payment-application`, `kontor.fx`, etc.
- test/: 462 refs (the heaviest-tested kernel module).
- modules/*/src: 701 refs across asset, lease, inventory, expense, l10n-*, payroll-*, all CIT/CGT/investment-income providers.
- modules/*/test: 763 refs.

**Risk**: load-bearing — every accounting write touches `:posting/*`.

**Dynamic-binding check**: `:provision/compute-fn` strings (ADR-101)? NO — they reference `:tax-context` facts. `:provision/consequence` EDN? NO. `resources/invariants/*.edn`? YES — both invariant queries reference `:posting/account` + `:posting/commodity`. Find-replace will catch them.

**Approach**: atomic find-replace, single commit.

### 5.2 `:parameter/*` — 2206 refs

- src/: 12 (just `kontor.statute` evaluator + tests).
- modules/*/src: **2161 refs** — this is the bulk. Every l10n provider (CIT, CGT, investment-income, PIT, payroll) writes `:parameter/code` / `:parameter/jurisdiction` / `:parameter/unit` repeatedly across statute/parameter seed data.

**Risk**: medium — high volume, low complexity. All in statute *data* declarations, no query-time dynamic dispatch.

**Approach**: atomic find-replace; the bulk lives in 11 jurisdictional statute files, each ~200 LOC.

### 5.3 `:account/*` — 1681 refs

- src/: 66, test/: 629, modules/*/src: 185, modules/*/test: 801.

**Risk**: HIGH — `:account/path` is used as a *value seed* in chart-of-account EDN install code AND as a query attribute in trial-balance, GL-scan, financial-statements code. Both must rename together.

**Dynamic-binding check**: `kontor.report/marginalize` uses `:account` axis name internally (per ADR-096); the axis-keyword `:account` is NOT renamed (it's a value, not an attr ident — confirm with the `marginalize` impl). Verified by grep on `kontor.report` source.

**Approach**: atomic find-replace. Run `:account-active` invariant test immediately after to catch any miss.

### 5.4 `:invoice/*` — 1509 refs

- src/: 123, test/: 87, modules/*/src: 492, modules/*/test: 807.

**Risk**: HIGHEST among collisions. Three contributors (kernel + companions/invoice + companions/collections + companions/procurement). Per §7-5, resolution is "kernel drops, companion-invoice owns; collections + procurement extend additively".

**Approach**: 3-step:
1. Move kernel `invoice-attrs` + `invoice-line-attrs` defs from `src/kontor/schema.clj` to `modules/invoice/src/kontor/invoice/schema.clj` (with `:kontor.invoice/*` prefix).
2. Update kernel callers (just `kontor.einvoice-provider` per grep) to depend on companion invoice OR remove the kernel-side ergonomics dep entirely.
3. Find-replace `:invoice/` → `:kontor.invoice/` throughout, including collections + procurement.

### 5.5 `:transaction/*` — 1238 refs

- src/: 267, test/: 365, modules/*/src: 300, modules/*/test: 306.

**Risk**: medium-high — load-bearing but no collisions and no dynamic-binding context.

**Approach**: atomic find-replace.

### Top 3 highest-risk renames (summary)

1. **`:invoice/*` → `:kontor.invoice/*`** — 4-contributor collision needing
   schema-def relocation BEFORE the find-replace. Recommended per-batch isolation. (§7-5).
2. **`:tax-group/*` → `:kontor.vat-group/*`** — SEMANTIC rename (not just
   prefix). Frees up `:kontor.tax-group/*` for ADR-167 fiscal-unit work.
   Single source of truth (12 src refs, 4 test refs) — small footprint but
   the only "rename the second segment, not just prefix" entry. (§7-1).
3. **`:person/*` consolidation** — 91 kernel + companion refs to consolidate
   into one kernel `:kontor.person/*` while peeling HR-only attrs into
   `:kontor.hr.person/*`. Requires per-attr triage (which of partner's 8
   `:person/*` extras are genuinely shared vs HR-specific?). (§7-2).

## §6. Execution sequence (for the W1-B agent)

The ~66 kernel + ~70 companion namespaces group into **8 batches** where
each batch is independent (cross-references between batches resolve only
after both are renamed; within-batch cross-references resolve atomically).
Each batch:

1. Find-replace the namespace prefix in `src/kontor/schema.clj` (or relevant
   `modules/*/src/.../schema.clj`)
2. Find-replace in all `src/` files for the kernel namespaces in this batch
3. Find-replace in all `test/` files for kernel namespaces
4. Find-replace in all `resources/*.edn` (only invariants for kernel batches)
5. Find-replace in all `modules/**/*.clj` + `modules/**/test/**/*.clj`
6. Run focused tests for the most-impacted area (`bb test :only kontor.<ns>-test`)
7. Run full `bb test`
8. Commit (`feat(schema)!: rename :X/* → :kontor.X/* (Batch N)`)
9. Move to next batch

### Batch 1 — Foundational, low-risk (4 namespaces)

`commodity`, `country`, `country-code`, `country-group`

Estimated: ~30 src files, ~1200 occurrences, low risk.

### Batch 2 — Foundational, person consolidation (4 namespaces)

`entity` + `partner` + `person` + `:hr-person` → consolidate into
`:kontor.entity/*` + `:kontor.partner/*` + `:kontor.person/*` (kernel-shared
across hr + partner companions per §7-2) + `:kontor.hr.person/*` (HR-only).

This is the most-design-intensive batch — should run AFTER §7-2 is
confirmed by the maintainer.

Estimated: ~80 src files, ~1500 occurrences, **medium-high risk** (per-attr triage on `:person/*`).

### Batch 3 — Account family (4 namespaces)

`account`, `account-code`, `account-tag`, `account-type-direction`

Includes invariant EDN updates. Estimated: ~50 src files, ~2200 occurrences,
high risk (load-bearing).

### Batch 4 — Core posting flow (4 namespaces)

`journal`, `transaction`, `posting`, `posting-dimension`

Includes invariant EDN updates. Estimated: ~70 src files, ~4400 occurrences,
**highest risk** (the load-bearing kernel). Run with extra care; full suite
between each find-replace.

### Batch 5 — Substrate (5 namespaces)

`ledger`, `period`, `balance-assertion`, `status-transition`, `status-history`

Estimated: ~40 src files, ~900 occurrences, low risk.

### Batch 6 — Tax kernel (5 namespaces, includes semantic rename)

`tax`, `tax-application`, `tax-concept`, `tax-rep`,
`tax-group → :kontor.vat-group` (per §7-1).

Estimated: ~25 src files, ~360 occurrences, medium risk (the
`:tax-group → :kontor.vat-group` is one-off; do it FIRST in the batch
before any other tax rename).

### Batch 7 — Statute substrate (4 namespaces)

`parameter`, `parameter-bracket`, `parameter-value`, `provision`,
`regime`, `tax-concept` (if not done in Batch 6).

Estimated: ~80 src files, ~3400 occurrences (most in l10n provider
files). High volume, low complexity (data files).

### Batch 8 — Companions (rest)

All remaining kernel namespaces (`audit-doc`, `approval-policy`,
`attestation`, `bank-account`, `bank-line`, `complemento`, `cross-tx`,
`document-type`, `dsar-request`, `fx-rate`, `fiscal-position`,
`legal-hold`, `lot`, `partner-bank-account`, `partner-merge`,
`partner-tag`, `partner-tax-id`, `payment-application`, `payment-term`,
`retention-policy`, `schedule`, `schedule-occurrence`, `side-effect-intent`,
`state`, `state-code`, `valuation-book`, `valuation-layer`,
`layer-adjustment`, `layer-consumption`) + ALL companion namespaces
(§3 table).

Resolve `:invoice/*` collision (§7-5) BEFORE find-replace.

Resolve `:order-item/*` sales/procurement split (§7-6) BEFORE find-replace.

Estimated: ~200 src files, ~5000 occurrences, mixed risk.

Recommended split into **8a** (kernel-only companions) and **8b**
(actual companions/* modules) to keep commits reviewable.

### Per-batch checklist appendix

For each batch, the agent runs:
```bash
# 1. Sanity check: snapshot the namespace's occurrence count BEFORE.
grep -rc ":<ns>/" src/ test/ modules/ resources/ > /tmp/<ns>-before.txt

# 2. Find-replace via sed (CAREFUL with anchoring — never use bare 'ns'
#    without trailing '/' or you'll catch :ns-other).
find src/ test/ modules/ resources/ -type f \( -name '*.clj' -o -name '*.edn' \) \
  -exec sed -i 's|:<ns>/|:kontor.<ns>/|g' {} +

# 3. Sanity check AFTER.
grep -rc ":kontor.<ns>/" src/ test/ modules/ resources/ > /tmp/<ns>-after.txt

# 4. Verify pre-count == post-count (every occurrence migrated).

# 5. Run focused tests
bb test :only kontor.<key-test-ns>

# 6. Run full suite.
bb test

# 7. Commit.
git add -A && git commit -m "feat(schema)!: rename :<ns>/* → :kontor.<ns>/* (Batch N)"
```

**Watch-out**: never write a sed pattern that matches a bare keyword like
`:account` without `/`; doing so would catch `:account-tag`, `:account-code`,
`:account-type-direction`, and `:account-active` value keywords as
collateral damage. The trailing `/` is load-bearing.

## §7. Special-case decisions you need confirmation on

The W1-B agent will halt and wait for maintainer confirmation on these
items BEFORE running batches that touch them.

### §7-1. `:tax-group/*` rename target

**Recommend `:kontor.vat-group/*`** (the VAT bucket-pair concept — what
the namespace currently models, per `tax-group-attrs` line 2012-2030 in
`src/kontor/schema.clj`: `:tax-group/payable-account` is "where collected
output VAT lands"; `:tax-group/receivable-account` is "where deductible
input VAT lands"). This is a VAT bucket-pair, NOT a fiscal unit.

Renaming to `:kontor.vat-group/*` frees up `:kontor.tax-group/*` for the
future `:kontor.tax-group/*` fiscal-unit namespace (ADR-167 / note 167
shipped under the *different* name `:fiscal-unit/*`, but the canonical
note explains the original collision concern). Per note 167 §1.1 the
project ALREADY picked `:fiscal-unit/*` over `:tax-group/*` for the
fiscal-unit concept — so the rename is more "make the VAT meaning
explicit" than "free up a name for fiscal-unit". Still recommended.

**Maintainer confirm**: rename to `:kontor.vat-group/*` or keep as
`:kontor.tax-group/*` (mechanical prefix only)?

### §7-2. `:hr-person/*` → `:kontor.hr.person/*` (dotted) vs `:kontor.hr-person/*` (flat)

**Recommend dotted**: `:kontor.hr.person/*`.

Rationale:
- Mirrors Clojure namespace convention (e.g. `clojure.core.async`).
- Makes the "hr's extension of person" relationship explicit: a query
  walking both `:kontor.person/*` and `:kontor.hr.person/*` reads as
  "person + hr's person extras" at a glance.
- Other dotted-sub-namespace candidates that emerge (`:kontor.audit.create-uid`
  per §7-3, `:kontor.sales.order-item/*` + `:kontor.procurement.order-item/*`
  per §7-6) benefit from the same convention — applied consistently it's
  a clear pattern.

Counter: queries become slightly more verbose
(`[:kontor.hr.person/national-id-doc ?id]` vs `[:kontor.hr-person/national-id-doc ?id]`).
But the additional dot is one character and the namespace clarity wins.

**Per the consolidation principle in the task brief**: the kernel
`:kontor.person/*` carries the shared person attrs (birth-date,
national-id, plus name/first-name/last-name/gender/etc. — see audit in
§3). HR-only extras (e.g. `:hr-person/national-id-doc`) become
`:kontor.hr.person/*` and *reference* the kernel `:kontor.person`
entity via a ref (`:kontor.hr.person/person` → ref to the kernel
person eid). Partner consolidates to use the kernel `:kontor.person/*`
directly.

**Maintainer confirm**: dotted `:kontor.hr.person/*`? OR flat `:kontor.hr-person/*`?

### §7-3. `:create/uid` + `:write/uid` — the audit-trail micro-namespaces

These attrs are attached to every kernel entity (see `audit-attrs` at
schema.clj:38-53). Two paths:

A. Flat prefix: `:kontor.create/uid`, `:kontor.write/uid`. Pros:
mechanical, mirrors every other rename. Cons: leaves two
single-attribute namespaces in the public API.

B. Consolidate: `:kontor.audit.create-uid`, `:kontor.audit.write-uid`
(or `:kontor.audit/create-uid`, `:kontor.audit/write-uid` — flat
single-namespace). Pros: cleaner public surface (one audit namespace
to mention in docs); semantically truer (these ARE audit attrs).
Cons: bigger rename — every kernel entity using `:create/uid` / `:write/uid`
in defaults/seeds needs the new attribute name (~25 occurrences in
src/, ~15 in test/, ~10 in modules — small).

**Recommend B with the flat shape `:kontor.audit/create-uid` and
`:kontor.audit/write-uid`**. The audit namespace is also where future
audit-doc-adjacent micro-attrs can live without proliferating
single-attr namespaces (e.g. `:kontor.audit/change-reason` if needed).

**Maintainer confirm**: A (mechanical) or B (consolidate to
`:kontor.audit/*`)?

### §7-4. `:partner/*` collision (kernel + companions/partner + companions/hr)

Per §3 audit, all three additively contribute attrs to `:partner/*`
WITHOUT key collisions (no two namespaces define the same `:partner/X`
attr). Recommend: keep `:kontor.partner/*` as the single namespace;
all three contributors install distinct attrs into it. The kernel
ships ~13 attrs, partner companion ships ~3 more (including
`:partner/person` ref), hr ships ~1.

If a future collision is discovered during execution (W1-B), fall back
to: kernel keeps `:kontor.partner/*`; companions use
`:kontor.partner.<sub>/*` (e.g. `:kontor.partner.contact/*` for the
partner companion's contact-mech bridge attrs). Audit found no current
collisions — verified by:
```bash
grep -h ":db/ident\s*:partner/" src/kontor/schema.clj \
  modules/{partner,hr,collections}/src/kontor/*/schema.clj | sort -u
```

**Maintainer confirm**: single `:kontor.partner/*` for all contributors?
(Default: yes per audit; only flag if a collision surfaces during
execution.)

### §7-5. `:invoice/*` collision (kernel + 3 companions)

Per §3 + §5.4, four contributors:
1. **Kernel** (`src/kontor/schema.clj` invoice-attrs + invoice-line-attrs,
   lines 2658-2825): minimal accounting-side invoice with EN16931
   / Factur-X / XRechnung payload slots + a status-machine facet.
2. **Companions/invoice**: extends with vendor-bill specifics + GL-account
   defaults + `:order-item-billing` junction.
3. **Companions/collections**: adds dunning-context attrs to invoices
   under `:invoice/*` (e.g. `:invoice/dunning-status`).
4. **Companions/procurement**: extends with `:invoice/*` for vendor bills.

**Recommend**: kernel drops `:invoice/*` + `:invoice-line/*`; relocate
them into `modules/invoice/src/kontor/invoice/schema.clj` under
`:kontor.invoice/*` + `:kontor.invoice-line/*`. The companion becomes
the canonical source. Kernel `:transaction/*` retains
`:transaction/document-type` (which can ref a `:document-type` entity
of `:invoice` semantics).

This decouples the kernel from invoice schema — consumers who don't
need an invoicing flow install only the kernel; consumers who do install
`kontor-invoice` (which transitively pulls invoice / invoice-line attrs).

**Risk**: existing kernel-only consumers (none yet — kontor pre-1.0
isn't depended upon by anyone external) won't break, but tests that
install only the kernel and then expect `:invoice/*` attrs will. Audit
suggests this is `kontor.einvoice-provider` + a few showcases (Showcases
01, 04, 05, 08) — all need to add a `kontor-invoice` companion install
call.

**Maintainer confirm**:
- A: kernel drops, companion owns (recommended).
- B: kernel keeps `:kontor.invoice/*` (and the companion's contribution
  collapses into kernel; no per-companion invoice schema).
- C: split — kernel `:kontor.kernel-invoice/*` + companion
  `:kontor.invoice/*` (NOT recommended — confusing).

### §7-6. `:order-item/*` sales/procurement split

NEW collision discovered during this audit (not in the task brief):
both `modules/sales/src/kontor/sales/schema.clj` and
`modules/procurement/src/kontor/procurement/schema.clj` define
`:order-item/*` attrs.

**Recommend sub-namespace per side**:
- `:kontor.sales.order-item/*` for sales-side line items.
- `:kontor.procurement.order-item/*` for purchase-side line items.

Same for `:order/*` (sales only today, but procurement has
`:requirement/*` which is the procurement equivalent — confirm whether
`:kontor.sales.order/*` is symmetric enough to procurement's
`:kontor.procurement.requirement/*` or whether we want
`:kontor.purchase.order/*` instead — the task is to flag, not to
decide).

**Maintainer confirm**:
- A: sub-namespace `:kontor.<side>.order-item/*` (recommended).
- B: rename to functional names: `:kontor.sales-line/*` +
  `:kontor.purchase-line/*` (cleaner public API, bigger rename).

## §8. Cost + sanity-check estimate

### Aggregate touch surface

Adding the §5 size data + audit numbers:

| Surface | Approx occurrence count |
|---|---|
| `src/` (kernel) | ~2,200 |
| `test/` (kernel) | ~3,300 |
| `modules/*/src/` | ~9,000 |
| `modules/*/test/` | ~7,000 |
| `resources/invariants/*.edn` | ~10 |
| `CLAUDE.md` | ~20 |
| `doc/decisions.md` | ~1,300 (DEFERRED to W5; not touched in W1-B) |
| **TOTAL (W1-B scope)** | **~22,000 attr-name find-replace operations** |

These resolve as ~135 distinct namespaces × ~165 average occurrences
each (with high variance — `:posting/` is 2320, `:write/` is small).

### W1-B agent-hours estimate

| Phase | Estimated agent-time |
|---|---|
| Read brief + this note + warm up | 0.5 hr |
| §7 design-call resolution (await maintainer) | 0.5 hr (incl. round-trip) |
| Batch 1 (commodity/country) | 0.5 hr |
| Batch 2 (entity/partner/person consolidation) | 2.0 hr (person triage is the hard part) |
| Batch 3 (account) | 1.0 hr |
| Batch 4 (posting/journal/transaction) | 1.5 hr |
| Batch 5 (substrate) | 0.5 hr |
| Batch 6 (tax kernel + semantic vat-group rename) | 0.5 hr |
| Batch 7 (statute substrate, parameters) | 1.5 hr (volume) |
| Batch 8a (kernel-only companions) | 1.0 hr |
| Batch 8b (companion modules + invoice consolidation + order-item split) | 2.5 hr |
| CLAUDE.md + invariants + README updates | 0.5 hr |
| Final `bb ci`, integration test, contingency | 1.0 hr |
| **Total** | **~13 agent-hours** |

Add ~3-4 hours buffer for the inevitable surprise (a test that depends
on the *string form* of an attribute name, a `resolve`-by-symbol-name
indirection, an EDN data file with a hardcoded attr the audit missed).

**Realistic estimate: 16-18 agent-hours, spread across ~3 sessions.**

### Sanity check

After every batch, the focused-test pass should remain at the same
green count as before the rename (assuming no maintainer-driven
schema additions land in parallel). The full suite should remain green
at the end of EACH batch's commit; any red between batches is a
batch-internal incompleteness and the W1-B agent must NOT advance to
the next batch until it's green.

Once all 8 batches are in, the maintainer should be able to:
1. Spin up a fresh datahike DB
2. Transact the kontor kernel schema
3. Confirm via `(d/q '[:find ?ns :where [_ :db/ident ?id] [(namespace ?id) ?ns]] @conn)`
   that EVERY non-datahike namespace starts with `kontor.` or `kontor.<companion>.`
4. Transact a beleg-style consumer schema (`:beleg.invoice/*`,
   `:beleg.customer/*`) into the SAME DB — no collisions, no install
   errors — proving the cohabitation invariant (ADR-002) is enforced
   by the namespace itself, not by gentleman's agreement.

That step-4 cohabitation check IS the acceptance criterion for the
v0.1.0-alpha schema-publishability gate.

---

**End of note 173**. Awaiting maintainer sign-off on §7. Once
confirmed, W1-B can execute the §6 batches.
