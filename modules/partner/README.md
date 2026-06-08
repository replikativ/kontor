# kontor-partner

Party-as-root model + `:person` / `:org` subtypes + polymorphic contact
mechanisms + temporal roles + temporal relationships for `kontor`.

## What it does

The kernel ships `:partner/*` as a flat identity-bearing root
(`:partner/external-id`, `:partner/name`, `:partner/country-code`,
`:partner/tax-id`) that other namespaces (`:posting/partner`,
`:invoice/buyer`, `:bank-account/owner`) reference. `kontor-partner`
extends that root into a full party model:

- **Party-as-root with discriminator subtypes** (ADR-033). A
  `:partner` carries `:partner/type ∈ #{:person :org}`; the type-
  specific attributes hang off a 1:1 subtype entity (`:person` or
  `:org`, joined by `:person/partner` / `:org/partner` with
  `:db.unique/value`). Splitting PII into `:person/*` lets a
  redaction / encryption policy target PII alone.
- **`:person` subtype** with first / middle / last names, salutation,
  suffix, nickname, localized name (CJK script), gender, marital
  status, birth date / place, nationality, etc.
- **`:org` subtype** with legal name, trade name, registration
  numbers, legal form, founded-at, dissolved-at, employee-count,
  parent-org ref.
- **Polymorphic contact mechs** — `:contact-mech` root with typed
  subtypes (`:postal-address`, `:telecom-number`, `:email-address`,
  `:web-address`) joined via `:partner-contact-mech` junction with
  `:from-date` (inclusive) + `:thru-date` (exclusive). Multi-purpose
  routing via `:partner-contact-mech-
  purpose` (`:billing`, `:shipping`, `:primary`, etc.).
- **Capability roles** — `:partner-role` junction (`:customer`,
  `:supplier`, `:employee`, `:internal-org`, …) with effective-
  dating. `has-role?`, `partners-with-role`, `roles-of`.
- **Temporal relationships** — `:partner-relationship` (party-to-
  party links: employment, group membership, board seat, parent-
  org, …) with relationship type + from / thru dates.
  `relationships-of`, `relationships-from`, `relationships-to`,
  `relationships-of-type`, `current-employer`, `current-employees`.
- **Bank accounts** (`:partner-bank-account` junction, ADR-039).
  Per-(partner, bank-account) routing with `:purpose ∈
  #{:disbursement :collection :both}` + effective dating.
  `bank-accounts-of`, `primary-disbursement-account`.
- **Tags** (`:partner-tag`). Free-form taxonomy. `tags-of`,
  `partners-with-tag`.
- **Multi-country tax IDs** (`:partner-tax-id`). One partner can
  have N tax IDs across N countries. `tax-ids-of`,
  `tax-id-for-country`.
- **Non-destructive merge** (`:partner-merge` + `merge-partners!`,
  ADR-039). Mark a duplicate as superseded by a canonical partner;
  the superseded record is archived (`:partner/status :archived`),
  not deleted; `resolve-canonical-partner` walks the merge chain
  (recursive, with a cycle guard) so consumers always reach the
  current canonical. ADR-068 routes the write through the gate
  with `:reason` + optional `:supporting-doc` + `:merged-by-uid`
  for audit.
- **Effective-date convention** — `active-as-of?` predicate is
  `from-date ≤ d < thru-date`. `:thru-date` is exclusive. Junction-
  time validity is layered on top of
  bitemporal `db` snapshots — caller passes the db at the desired
  tx-time snapshot + an optional `:as-of` for junction validity.

## When to use it

- Any consumer that needs more than `:partner/external-id` +
  `:partner/name` (which the kernel already has)
- Multi-purpose contact routing (billing address ≠ shipping address)
- Employment + organizational hierarchy walks
- Multi-country tax-ID resolution (e.g. a partner with `DE` UStId +
  `FR` TVA + `IT` Partita IVA)
- Customer-master de-duplication via `merge-partners!`

When NOT to use it:
- `:posting/partner` references — the kernel root suffices
- HR-employee records → `kontor-hr` (`:person` / `:employment` /
  `:compensation`; the kontor-hr `:person` is intentionally
  separate from kontor-partner `:person` because HR carries
  consent + retention discipline kontor-partner does not)
- Identity / authentication / authorization — consumer-layer; not
  shipped by kontor

## Load-bearing ADRs

- [ADR-033](../../doc/decisions.md) — party-as-root + person/org
  subtypes + polymorphic contact mechs + temporal roles +
  relationships
- [ADR-039](../../doc/decisions.md) — `:partner/credit-status`
  scalar + `:partner-bank-account` junction + non-destructive
  `:partner-merge`
- [ADR-068](../../doc/decisions.md) — `merge-partners-tx-data`
  pure builder + `!` wrapper through `kontor.validation/transact-
  with-validation`

## Key namespaces

- `kontor.partner.schema` — `:partner/*` extensions (`:type`,
  `:status`, `:preferred-commodity`, …), `:person/*`, `:org/*`,
  `:postal-address/*`, `:telecom-number/*`, `:email-address/*`,
  `:web-address/*`, `:partner-contact-mech/*`,
  `:partner-contact-mech-purpose/*`, `:partner-role/*`,
  `:partner-relationship/*`, `:partner-bank-account/*`,
  `:partner-tag/*`, `:partner-tax-id/*`, `:partner-merge/*` +
  `install!`
- `kontor.partner` — the public surface (resolution, subtype pulls,
  junction queries, merge transactor). NOTE: this namespace lives at
  `src/kontor/partner.clj` at the top level — kernel-collision case
  similar to `kontor.invoice` vs `kontor.invoice.bridge`.

## Minimal example

```clojure
(require '[datahike.api          :as d]
         '[kontor.core           :as k]
         '[kontor.partner        :as p]
         '[kontor.partner.schema :as p-schema])

(def conn (k/create-test-db))
(p-schema/install! conn)

;; Define an org partner with one postal address + one email,
;; routed for billing + shipping.
(d/transact conn
  [{:db/id "p1"
    :partner/external-id "acme-de"
    :partner/name "Acme GmbH"
    :partner/type :org
    :partner/status :enabled
    :partner/country-code :de}
   {:db/id "o1"
    :org/partner "p1"
    :org/legal-name "Acme Gesellschaft mit beschränkter Haftung"
    :org/legal-form :gmbh}
   {:db/id "addr1"
    :contact-mech/type :postal-address
    :postal-address/street1 "Marienplatz 1"
    :postal-address/city "München"
    :postal-address/postal-code "80331"
    :postal-address/country :de}
   {:partner-contact-mech/partner "p1"
    :partner-contact-mech/contact-mech "addr1"
    :partner-contact-mech/from-date #inst "2026-01-01"}
   {:partner-contact-mech-purpose/partner "p1"
    :partner-contact-mech-purpose/contact-mech "addr1"
    :partner-contact-mech-purpose/purpose :billing}])

;; Resolve + query
(p/contact-mech-by-purpose (d/db conn) "acme-de" :billing)
(p/active-as-of? <from> <thru> #inst "2026-06-01")

;; Merge a duplicate (writes :partner-merge, archives superseded)
(p/merge-partners!
  conn "acme-de" "acme-duplicate"
  {:reason :duplicate-record
   :supporting-doc <doc-eid>
   :merged-by-uid <admin-uid>})

;; Always walk the merge chain
(p/resolve-canonical-partner (d/db conn) <superseded-eid>)
```

## What it does NOT do

- **No CRM / sales-pipeline / opportunity entities.** Pipeline lives
  in the consumer's CRM.
- **No identity / authentication.** `:create/uid` references for
  who-did-what are kernel-side; identity provider integration is
  consumer-side.
- **No automated de-duplication.** `merge-partners!` is the
  transactor; the *detection* of duplicates (fuzzy name match,
  address normalisation, tax-ID dedup) is consumer-side.
- **No PII encryption-at-rest.** PII is namespaced to `:person/*`
  to make encryption / redaction *targetable*; the encryption itself
  is a runtime / datahike-storage concern.
- **No HR `:person` entity.** `kontor-hr` ships its own
  `:person` namespace with consent + retention discipline; the two
  are intentionally separate (ADR-094).

## Tests

`modules/partner/test/kontor/partner_test.clj` — single file
covering install, resolution, subtype pulls, contact-mech queries +
purpose routing, temporal junctions + `active-as-of?`,
relationships, bank-account purpose filtering, multi-country tax-id
resolution, and the merge chain walk.

## License

Apache 2.0.
