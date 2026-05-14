# Research note 22 — Stage M (`kontor-legal`) reference-implementation study

Reference-implementation angle for Stage M of `kontor`. Companion to [note 17](17-vendor-legal-process.md), which surveyed the vendor landscape; this note answers: **what license-clean OSS projects, standards bodies, and primary statutes inform the kernel-level `:legal-hold` / `:retention-policy` / `:audit-doc/privilege` / `:dsar-request` primitives.**

The maintainer has settled four design calls (do not relitigate):

1. **Hybrid hold scope** — explicit eid set OR datalog scope-query.
2. **Retention defaults in l10n** — kernel ships shape, `kontor-l10n-<cc>` ship seeds (mirroring ADR-026 effective-dated tax-rates).
3. **DSAR ships in Stage M** as `:dsar-request/*` + `kontor.dsar/collect`.
4. **No e-sign / no redlining / no cap-table / no CLM** in this stage.

What follows is the reference scaffolding the ADRs (ADR-049/050/051/052) will rest on.

## TL;DR

- **OSS prior art is thin.** Apache OFBiz, ERPNext, Compiere/iDempiere, Tryton, KillBill — none ship a first-class legal-hold primitive. Odoo's `data_recycle` is the closest, and it is just a domain-filter + time-delta + archive/unlink. **JCR's `RetentionManager` (JSR-283 §20)** is the only mature standard API for retention+hold, but its data model is "two opaque objects" — application-defined. The standardised vocabulary is in **W3C DPV (2.3 as of February 2026)**.
- **The two most useful patterns to lift verbatim are XTDB v2's `ERASE` (irrevocable, all-VT-all-ST) and Datomic's `:db.excise/*` schema** (target attr, before-T, before-instant). Both treat erasure as orthogonal to retraction/delete. kontor already has the equivalent via datahike `:db/purge` (ADR-007); the work is the *gating* layer.
- **The cleanest legal-hold semantics in any OSS we surveyed is JCR's `addHold(path, name, isDeep)` → `Hold[]`** with the caveat that the hold's own *shape* is implementation-defined. kontor's hybrid (eid set OR scope-query) is a strict superset and is the right answer.
- **Per-jurisdiction retention numbers are highly converged** at the "books of account" level: DE 10y (HGB §257 / AO §147), JP 10y (Companies Act §432), CN now 30y (2016 revision), US 7y (SOX §802 + 17 CFR §210.2-06 for auditors; IRS §6001 commonly 3y but 7y for worthless-debt), CA 6y (ITA §230), AU 7y (Corporations Act §286), IN 8y (Companies Act §128), MX 5y (CFF Art 30). The "GDPR cliff" (storage limitation vs purpose limitation) collides with these floors — **the kernel must encode both as `:min-keep` and `:max-keep`** so the `eligible-for-purge?` predicate can pick the tighter ceiling.
- **The DPV's PD extension has seven top-level personal-data categories** (Internal, External, Financial, Historical, Social, Tracking, Vulnerability). For privilege tagging we recommend a flat 6-keyword set rather than DPV's tree — the JCR / Relativity industry shape is closer to what auditors recognize than W3C's RDF taxonomy.

## 1. OSS reference implementations — five-row capabilities matrix

| Project | Legal hold | Retention policy | DSAR / erasure | Privilege tagging | Verdict for kontor |
|---|---|---|---|---|---|
| **Apache OFBiz** (Apache-2.0) | None. `Party`, `Content`, `AcctgTrans` have `fromDate`/`thruDate` envelopes (note 12) but no preservation-order entity. `applications/content/entitydef/eecas.xml` is the only `entitydef` in content. Grep across `applications/datamodel/entitydef/` returns zero matches for `retention\|hold\|preservation\|tombstone\|excise\|purge`. | None. `enumeration` recursive hierarchy could carry a code list but no engine consumes it. | None. No `PartyDataRequest`-style entity. | None. `Content` carries `privilegeEnumId` for *access* control (note 12) but no attorney-client / work-product semantics. | **Negative finding** — same shape as Stage L (kontor-collections). kontor designs into a vacuum here. |
| **Odoo** (LGPLv3, reference only — ADR-001 forbids translation) | None. | `addons/data_recycle/models/data_recycle_model.py:19-99` — `data_recycle.model` carries `res_model_id` + `domain` + `time_field_delta` + `recycle_action ∈ {archive, unlink}` + `recycle_mode ∈ {manual, automatic}`. A cron sweeper writes `data_recycle.record` rows; admin approves; the `recycle_action` runs. No hold semantics, no jurisdictional opt, no minimum-keep floor. | `addons/privacy_lookup/models/privacy_log.py:1-49` — a wizard searches for partner records by email, anonymizes name+email with `name[0] + '*' * (len-1)`, writes a `privacy.log` row. **Anonymize-not-delete** is the entire pattern. No request log, no statutory deadline tracking. | None. | **Cautionary tale** — the anonymize-only path is what NetSuite also does (note 17 §1). It works because Odoo treats the partner row as load-bearing for accounting FKs that must stay valid. kontor's bitemporal posture lets us do better: erase the PII fields, keep the posting row + content hash, audit chain documents the purge. |
| **ERPNext** (GPL-3, reference only) | None. | None. | None. Local grep across `erpnext/**/*.py` for `data_retention\|retention_policy\|legal_hold\|right_to_erasure\|personal_data` returns nothing. | None. | **Confirms the OSS vacuum.** Frappe-side has a `User Permission` framework but no privilege-class tagging. |
| **KillBill** (Apache-2.0) | None. | None. Audit logs are immutable by design but there is no formal retention window — the table grows. | None (subscription invoicing has no GDPR primitive). | None. | Lift the `X-Killbill-CreatedBy` / `X-Killbill-Reason` / `X-Killbill-Comment` triple as a reference for our `:status-history/reason` + `:status-history/reason-note` (already in ADR-038). |
| **Apache JackRabbit / JCR 2.0** (Apache-2.0) | **`javax.jcr.retention.RetentionManager`** — `addHold(path, name, isDeep)` → `Hold`; `removeHold(path, hold)`; `getHolds(path)` → `Hold[]`. The `Hold` data model is **application-defined** (the JCR spec is explicit: "format and interpretation of the name are not specified"). | `RetentionPolicy` opaque object; `setRetentionPolicy(path, policy)`; `getRetentionPolicy(path)`; `removeRetentionPolicy(path)`. Enforcement is "an implementation issue." | None. | None. | **The reference shape.** The "verbs are standard, data model is application-defined" approach is exactly what kontor's hybrid scope needs. We supply the data model (eid set OR datalog query). |
| **Datomic** (proprietary, but `:db.excise/*` schema is documented at <https://docs.datomic.com/reference/excision.html>) | None. | None. | **`:db/excise` + `:db.excise/attrs` + `:db.excise/beforeT` + `:db.excise/before`** — points at a target entity or attribute, optionally limits to a set of attrs, optionally limits to txes before a `t` or instant. "Excision is the complete removal of a set of datoms matching a predicate" — disappears from history. Docs are explicit: **"excision should never be used to correct erroneous data"** — it is only for legal erasure. | None. | **Lift verbatim into the ADR vocabulary.** kontor's `:db/purge` is the same primitive at the datahike level; ADR-007 already permits it as a recorded commit. The ADR-050 retention helper should produce purge tx-data shaped the same way Datomic excision is. |
| **XTDB v2** (MPL-2.0) | None. | None — though `ERASE FROM <table> WHERE <predicate>` is the GDPR escape hatch. | **`ERASE FROM users WHERE email = 'jms@example.com'`** — `core/src/test/clojure/xtdb/node_test.clj:510-523`; tutorial at `src/test/resources/docs/xtql_tutorial_examples.yaml`. Semantics from docs: "Irrevocably erases documents from a table, for all valid-time, for all system-time." Distinguished from `DELETE` (which is bitemporal soft-delete: open-ended valid-time close, history preserved). | None. | **Strong validation.** The bitemporal community has converged on "delete = close valid-time / erase = drop from history including ST." kontor doesn't get the SQL ergonomics, but `:db/purge` plus a `:tx/valid-to` close on the prior assertion (per ADR-048) achieves the same shape. |
| **WordPress core** (GPLv2, reference only) | None. | None. | **WP 4.9.6 personal-data flow.** `WP_Privacy_Requests_Table` (abstract) + `WP_Privacy_Data_Removal_Requests_List_Table` / `WP_Privacy_Data_Export_Requests_List_Table`. Workflow: admin enters email → confirmation email to user → user clicks link → status flips Pending → Confirmed → admin runs `wp_privacy_send_personal_data_export_email()` or the eraser → Completed. Plugin authors register erasers via the `wp_privacy_personal_data_erasers` filter. | None. | **Closest OSS analogue to `:dsar-request`.** Lift: identity-verification step is mandatory; status machine has 4 states (Pending → Confirmed → InProgress → Completed); per-plugin erasers register themselves — for kontor that maps to per-companion `:dsar/finding-fn` registries. |
| **Hyperledger Fabric** | `blockToLive=0` on a private-data collection = indefinite retention (de-facto "hold"). | `blockToLive=N` purges the data after N blocks committed; Fabric's auto-purge is the cleanest tombstoning pattern in any append-only chain we surveyed. **The `blockToLive` is immutable** after collection creation — a constraint, not a feature. | None. | None. | Useful pattern: **per-data-class retention configuration is fixed at registration time**, not per-row. kontor's `:retention-policy/applies-to [:transaction :invoice ...]` is the same shape, with the advantage that we can rebind without rewriting history. |
| **Apache Camunda 8** | None. | Optimize's `history-cleanup` block: `cronTrigger: '0 1 * * *'` + `ttl: 'P2Y'` (ISO-8601 duration) + per-entity-class overrides. Modes: `keyAll` (clear variables, keep instance) vs full delete. | None. | None. | Useful pattern: **history TTL is configured as ISO-8601 duration per entity-class** with a `cleanupMode ∈ {keep-instance, full-delete}` distinction. kontor's `:retention-policy/max-keep-millis` could optionally support `:retention-policy/cleanup-mode ∈ {:anonymize :delete-row :purge-history}` to match. |

**One-paragraph synthesis.** Of the ten projects surveyed, **only JCR (RetentionManager API), Datomic (excise schema), XTDB v2 (ERASE statement), and WordPress (DSAR request flow)** ship anything resembling the primitives Stage M needs. None of them are accounting systems; the ERPs and accounting platforms (OFBiz, ERPNext, KillBill, Odoo, Compiere/iDempiere) are uniformly absent on hold, retention, and DSAR. **This is the same vacuum Stage L (collections) found** — kontor is designing for territory the OSS world hasn't covered. The good news: where there is prior art, it agrees on the shape.

## 2. Datalog-shaped patterns for purge-blocking

ADR-007 mandates that `:db/purge` is itself a recorded commit. ADR-049 (forthcoming) adds the gating layer: **a purge is rejected if the target entity falls within any open legal hold's scope.**

### 2.1 Hybrid scope shape

Per the maintainer's design call (carried over from note 17 §5):

```clojure
;; Static hold — explicit eid set, cheap predicate.
{:legal-hold/code           "matter-2026-acme-v-corp"
 :legal-hold/case-name      "Acme Inc v Corp - subpoena 2026-03-12"
 :legal-hold/issued-at      #inst "2026-03-12"
 :legal-hold/issued-by-uid  "gc@acme.example"
 :legal-hold/audit-doc      [:audit-doc/code "preservation-order-001"]
 :legal-hold/scope-mode     :explicit
 :legal-hold/held-eids      [12345 12346 12347 99001 99002]
 :legal-hold/state          :open}

;; Dynamic hold — datalog query, re-evaluated by sweeper.
{:legal-hold/code           "matter-2026-employment-jones"
 :legal-hold/case-name      "Jones v Co - employment discrimination 2026-Q2"
 :legal-hold/issued-at      #inst "2026-04-01"
 :legal-hold/issued-by-uid  "gc@acme.example"
 :legal-hold/audit-doc      [:audit-doc/code "preservation-order-002"]
 :legal-hold/scope-mode     :query
 :legal-hold/scope-query    '[:find ?e
                              :where
                              [?e :partner/code "jones-h"]
                              (or [?e :transaction/header _]
                                  [?e :status-history/entity ?partner]
                                  [?e :audit-doc/code _])]
 :legal-hold/scope-eids-cache [98001 98002 98003 ...]    ;; sweeper-computed
 :legal-hold/scope-refreshed-at #inst "2026-05-13T03:00Z"
 :legal-hold/state          :open}
```

`scope-mode` is the discriminator (OFBiz idiom — note 12); `:explicit` stores the eid set directly, `:query` stores the datalog form and a sweeper-refreshed cache.

### 2.2 The purge-check predicate

```clojure
(defn under-hold?
  "Is `eid` in the scope of any open hold in `db`? Single-pass over
   :legal-hold entities with :legal-hold/state :open."
  [db eid]
  (let [open-holds (d/q '[:find [?h ...]
                          :where
                          [?h :legal-hold/state :open]]
                        db)]
    (some (fn [hold-eid]
            (let [hold (d/pull db
                               [:legal-hold/scope-mode
                                :legal-hold/held-eids
                                :legal-hold/scope-eids-cache
                                :legal-hold/scope-query]
                               hold-eid)]
              (case (:legal-hold/scope-mode hold)
                :explicit
                (contains? (set (:legal-hold/held-eids hold)) eid)

                :query
                (or (contains? (set (:legal-hold/scope-eids-cache hold)) eid)
                    ;; Cache miss — re-evaluate the query inline. Bounded
                    ;; cost: one query per hold whose cache is stale.
                    (let [live-eids (set (d/q (:legal-hold/scope-query hold) db))]
                      (contains? live-eids eid))))))
          open-holds)))

(defn assert-no-purge-under-hold!
  "Middleware hook — extends `kontor.sealing/assert-no-silent-retracts!`
   with a `:db/purge`-targets-under-hold check.

   Throws ex-info {:type :legal-hold/purge-blocked
                    :hold <code> :eid <eid>} on first violation."
  [db tx-data]
  (doseq [tx tx-data
          :when (and (vector? tx) (#{:db/purge :db.fn/purge} (first tx)))
          :let [eid (second tx)]]
    (when (integer? eid)
      (when-let [hold-code (some #(when (under-hold? db eid) %)
                                 (open-hold-codes db))]
        (throw (ex-info "Purge blocked: entity is under legal hold"
                        {:type :legal-hold/purge-blocked
                         :hold hold-code
                         :eid eid
                         :remediation
                         "Release the hold (:legal-hold/release! conn ...) with a documented :release-reason and an :audit-doc of the release order. Then the purge may proceed and will itself be a recorded commit (ADR-007). The hold-release event is bitemporal: ':as-of-tx' at the time of the subpoena's resolution will show the hold open; ':as-of-tx' after the release shows the purge."})))))
  nil)
```

### 2.3 Query-correctness IS hold-correctness

The most important invariant in §2.2 is *not stated in the code*: **the datalog query's correctness is the auditor's only proof that the hold's scope was sound.** If counsel signs off on a hold whose query returns the wrong set, the kernel cannot detect it — datalog is a description language, not a meaning language.

Two mitigations:

1. **Mandatory `:legal-hold/scope-preview`** field — a `[:audit-doc]` ref to a counsel-signed preview of the eid set the query produces at hold-creation time. The kernel verifies on hold-open that the query, evaluated against the current `db`, returns a superset of the preview's eid list. (Subset of preview is permitted because new entities matching the query come into scope as they are written.) This is *exactly* the JCR "name is application-defined but you can attach a hold object" pattern, made bitemporal.

2. **Sweeper refresh budget** — `:legal-hold/scope-refreshed-at` plus a configurable max-staleness (default 24h, override per-hold). The purge-check refuses if the cache is older than the budget and a re-evaluation fails. Stale-cache is a *louder* failure than wrong-cache.

### 2.4 Composing with retention

When a `:retention-policy` says "purge after max-keep" AND a `:legal-hold` says "preserve", the hold wins. Composition rule:

```clojure
(defn eligible-for-purge?
  "True iff `eid` is past its retention ceiling AND no open hold covers it."
  [db eid as-of]
  (and (past-retention-ceiling? db eid as-of)
       (not (under-hold? db eid))))
```

`past-retention-ceiling?` walks the entity's `:retention-policy` via its `entity-type` and computes `(- as-of max-keep-millis) > creation-time`. For statutory floors (`min-keep`), the same predicate refuses purge below the floor regardless of consent. The interaction matrix is:

| consent | floor (min-keep) | ceiling (max-keep) | hold | purge? |
|---|---|---|---|---|
| any | not reached | any | none | **block** — statutory floor wins over consent |
| any | reached | not reached | none | allow if requested (consent/erasure); block if cron |
| any | reached | reached | none | **purge** — both consent and "max-keep" agree |
| any | any | any | open | **block** — hold wins over everything |

This is the rule industry has converged on (note 17 §1 + Pathlock SOX guide); it's also what NIST SP 800-53 control SI-12 implies for federal data classes. The kernel encodes it once, in `eligible-for-purge?`.

### 2.5 What about `:tx/valid-to` close vs `:db/purge`?

Per ADR-048, kontor's bitemporal write model uses `:tx/valid-to` to close out the valid-time of an assertion. **Closing valid-time is NOT the same as purging.** A closed valid-time interval still appears in history (`d/history db`) and a `:as-of-tx <past>` query will still see it. A purge removes it from history.

Stage M's `kontor.dsar/erase!` should issue:
- `:db/purge` for the partner's PII fields (name, email, phone, address) — these are *gone from history*;
- `:tx/valid-to` close on attributes the consumer chose to *soft-anonymize* (e.g. replace `:partner/display-name` with `"REDACTED-2026-05-13"` and close the prior assertion's valid-time);
- **never `:db/purge` of `:posting`s** — those have the GAAP floor and are anonymized via partner-FK redirection (covered in ADR-052 forthcoming).

XTDB v2's `ERASE` (all-VT-all-ST) maps to `:db/purge`. XTDB v2's `DELETE` (close valid-time) maps to `:tx/valid-to`-close. Datomic's `:db.excise/*` maps to `:db/purge` with attribute restriction. The semantics align.

## 3. DSAR bitemporal-walk reference

`kontor.dsar/collect` answers: *"what does the system know about subject S, as known at tx-time T?"*

### 3.1 API sketch

```clojure
(ns kontor.dsar)

(defn collect
  "Walk every kernel + registered-companion entity referencing
   `subject-eid`, snapshot at tx-time `as-of-tx` (default = now),
   return a structured map.

   The walk is single-pass: every attribute of type `:db.type/ref`
   in the installed schema that points at :partner is enumerated;
   for each, query `[?e <attr> subject-eid]` against `(d/as-of db
   as-of-tx)` and pull the entity.

   Returns:
     {:dsar/subject       subject-eid
      :dsar/as-of-tx      as-of-tx
      :dsar/findings
        [{:dsar/source         :transaction          ; entity-type
          :dsar/source-attr    :transaction/partner  ; FK that led here
          :dsar/entity-id      <eid>
          :dsar/entity-pull    {:transaction/code ... :transaction/total ...}
          :dsar/legal-basis    :gdpr-article-6-1-b   ; from :retention-policy
          :dsar/min-keep-until #inst \"...\"          ; statutory floor
          :dsar/max-keep-until #inst \"...\"          ; ceiling
          :dsar/under-hold?    false
          :dsar/erase-eligible? false}
         ...]}

   Companions register additional walkers via
   `(register-walker! :kontor-collections collect-collections-data)`
   — each walker accepts `(db subject-eid as-of-tx)` and returns a
   seq of :dsar/finding maps."
  ([conn subject-eid]
   (collect conn subject-eid (java.util.Date.)))
  ([conn subject-eid as-of-tx]
   (let [db    (d/as-of (d/db conn) as-of-tx)
         core  (walk-core db subject-eid)
         comps (mapcat (fn [walker-fn]
                         (walker-fn db subject-eid as-of-tx))
                       @registered-walkers)]
     {:dsar/subject  subject-eid
      :dsar/as-of-tx as-of-tx
      :dsar/findings (vec (concat core comps))})))

(defn walk-core
  "Default walker for kernel entities — :transaction, :posting,
   :invoice, :audit-doc, :status-history, :bank-account, etc.

   Implementation: pull the schema, filter to ref-typed attrs
   pointing at :partner (or whose value-type registers as a
   subject-reference per `:partner/subject-ref?` schema metadata),
   query each in turn."
  [db subject-eid]
  (let [partner-refs (partner-ref-attrs db)]
    (for [attr partner-refs
          :let [entity-eids (d/q [:find '[?e ...] :where ['?e attr subject-eid]] db)]
          eid entity-eids]
      {:dsar/source         (entity-type-of db eid)
       :dsar/source-attr    attr
       :dsar/entity-id      eid
       :dsar/entity-pull    (d/pull db '[*] eid)
       :dsar/legal-basis    (retention-basis-for db eid)
       :dsar/min-keep-until (min-keep-until-for db eid)
       :dsar/max-keep-until (max-keep-until-for db eid)
       :dsar/under-hold?    (under-hold? db eid)
       :dsar/erase-eligible? (eligible-for-purge? db eid (java.util.Date.))})))
```

### 3.2 Patterns lifted from references

**XTDB v2 SQL bitemporal pattern.** `SELECT * FROM users FOR VALID_TIME AS OF DATE '2020-01-01'` (yaml tutorial line 95-100). The equivalent for kontor is `(d/as-of db tx-instant)` plus the resolver from `kontor.bitemporal/value-at`. Both answer "what was true on date D"; XTDB's SQL is more ergonomic but datahike's lattice composes through `d/history ∘ d/as-of` (already used in `kontor.bitemporal/ensure-history`).

**Datomic entity-walking with filters.** `(d/filter db (fn [_ datom] ...))` lets you produce a filtered view (Datomic API). datahike does not expose `d/filter`; the kontor equivalent is to walk via `d/q` and gate at the application layer (which is what `walk-core` does).

**WordPress per-plugin eraser registration.** WP's `wp_privacy_personal_data_erasers` filter is "each plugin contributes a closure `(email, page) → [data, done?]`". kontor's `registered-walkers` is the same idea: each companion (`kontor-collections`, `kontor-procurement`, future `kontor-counsel`) registers its own walker. The kernel does not need to know about companion entity types.

**Mautic erasure flow** (note 17 §sources but not deep-inspected here). Mautic emits a `LeadEvent::ANONYMIZE` event when a contact is anonymized; subscribers do their own cleanup. kontor's equivalent is the side-effect intent (ADR-041) emitted on `:dsar-request/state → :fulfilled`.

### 3.3 Bitemporal-grounded DSAR is a small niche

Surveying the public OSS + vendor space for "DSAR query at as-of-tx":

- **XTDB blog 2024 — "GDPR with bitemporality"** discussed it conceptually but the public post does not exist as a citable reference. The XTDB v2 docs surface ERASE as a primitive; the "bitemporal DSAR" framing is implicit.
- **Datomic — no production DSAR pattern published.** Cognitect's posts emphasize `d/excise` for the right-to-erasure case but do not bind it to a request-log model.
- **No SaaS DSAR vendor (BigID, OneTrust, DataGrail) advertises bitemporal "what did we know on date D" semantics.** Their pitch is "search-and-redact today"; the temporal axis is *out of scope*.

**Conclusion:** kontor's bitemporal DSAR is genuinely novel territory. The reference shape is "WordPress request flow + XTDB ERASE + datahike `:db/purge` + ADR-008 bitemporal queries." The API in §3.1 is what no vendor publishes.

## 4. Privilege tagging vocabulary

The maintainer's preferred shape (note 17 §3.7) is one keyword attribute on `:audit-doc`:

```clojure
{:db/ident       :audit-doc/privilege
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Privilege classification per US e-discovery
                  practice. nil = unprivileged (default)."}
```

### 4.1 Recommended values

The shortlist below is **flat, six entries, all keyword**:

| Value | Meaning | Reference |
|---|---|---|
| `nil` (absent) | Unprivileged. The kernel's default. | — |
| `:attorney-client` | US/Canada/UK/AU/IN attorney-client privilege. Communication between counsel and client for legal advice. | [ABA Crafting Effective Privilege Logs](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-november/crafting-effective-privilege-logs-legal-success/) |
| `:work-product` | US "work product doctrine" (Hickman v Taylor, 1947 — FRCP 26(b)(3) for federal). Litigation preparation materials. | [Wikipedia Privilege log](https://en.wikipedia.org/wiki/Privilege_log) |
| `:joint-defense` | Common-interest doctrine — shared privilege between co-defendants. | [IAPP best practices](https://iapp.org/news/a/best-practice-considerations-for-preserving-attorney-client-privilege) |
| `:settlement-communication` | FRE 408 / equivalents — settlement negotiations are inadmissible. | FRE 408 |
| `:trade-secret` | UTSA / DTSA 18 USC §1839 trade-secret protection. Not strictly privilege but same shape (redact, gate). | — |
| `:pii-sensitive` | Catch-all for GDPR Art 9 special categories + HIPAA PHI + PCI-DSS cardholder data. Triggers the strictest redaction in `uri-for`. | GDPR Art 9 |

### 4.2 Why a flat 6-keyword set rather than DPV

The W3C DPV Personal Data extension (PD 2.3, Feb 2026) has **seven top-level categories** (Internal, External, Financial, Historical, Social, Tracking, Vulnerability) with hundreds of nested subclasses ([w3id.org/dpv#PersonalData](https://w3id.org/dpv/2.3/dpv/modules/personal_data.html)). It is RDF-shaped, semantically rich, and built for cross-vendor interop.

For kontor's kernel, **DPV is too big and the wrong shape**:

1. **DPV models WHAT the data IS** (Financial → BankAccount → IBAN). The privilege vocabulary models **WHO MAY ACCESS** (counsel, work-product, settlement). These are orthogonal concerns. A bank-account number can be `:pii-sensitive`-tagged for access purposes; a memo can be `:attorney-client`-tagged for access purposes. Two attributes, not one taxonomy.
2. **DPV is for inter-controller communication** (DPV records-of-processing, data-export contracts). kontor's privilege tag is for **intra-application access gating**. The audience is the kontor query layer, not a downstream privacy auditor.
3. **DPV uses URI-keyed RDF concepts.** kontor uses Clojure keywords. The mapping is doable (`:audit-doc/dpv-categories [:db.type/keyword ...]` as a separate attribute) but lives in a `kontor-l10n-eu-privacy` companion, not the kernel.
4. **IAB Tech Lab's GPP** ([IAB Tech Lab](https://iabtechlab.com/gpp/)) is an HTTP-header transport for consumer-signal strings (US-CA, US-NAT, etc.). Out of scope.
5. **J2EE / Jakarta's PolicyContextHandler** is an older privilege-tag pattern (Subject, ProtectionDomain) where the *enforcement* is the JVM SecurityManager. Conceptually informative but its model is mandatory-access-control, not a tag. The kontor design is closer to MAC than to RBAC — the privilege is on the data, not the user — but enforcement is at the consumer-app layer.
6. **W3C XACML** (oasis-open.org) is policy-based access (subject, action, resource, environment) with a 200-page profile. Wildly overkill for kontor's "tag the document and gate the URI" need.

### 4.3 The access-check seam

ADR-010 forbids the kernel from owning auth/identity. The privilege tag therefore *describes* the document; the *enforcement* is in the consumer (beleg, simmis). The seam:

```clojure
(defn uri-for
  "Return the storage-uri for `doc`, or :redacted, based on whether
   the requesting role authorizes the document's privilege tag.

   `roles` is a set of role keywords supplied by the consumer's auth
   layer. The mapping from role → allowed-privileges is consumer-
   defined; the kernel exposes only the predicate."
  [db doc role->privileges requesting-roles]
  (let [doc-pull   (d/pull db [:audit-doc/storage-uri :audit-doc/privilege] doc)
        privilege  (:audit-doc/privilege doc-pull)
        uri        (:audit-doc/storage-uri doc-pull)
        permitted? (or (nil? privilege)
                       (some (fn [role]
                               (contains? (role->privileges role) privilege))
                             requesting-roles))]
    (if permitted? uri :redacted)))
```

The consumer supplies `role->privileges`, e.g.:

```clojure
{:role/general-staff   #{nil}                       ;; unprivileged only
 :role/finance-lead    #{nil :pii-sensitive}
 :role/general-counsel #{nil :attorney-client :work-product :joint-defense
                         :settlement-communication :trade-secret :pii-sensitive}
 :role/outside-counsel #{:attorney-client :work-product :joint-defense}}
```

The kernel ships `uri-for` + the vocabulary; the consumer ships the role map. This is the same `TaxProvider`-shaped seam as ADR-005.

## 5. Per-jurisdiction retention table

For the l10n modules. All durations confirmed from primary sources cited inline. **Numbers are loadbearing — every minimum/maximum here will end up in `kontor-l10n-<cc>/retention.edn`.**

| CC | Statute | Record class | Min retention | Notes |
|---|---|---|---|---|
| **DE** | [HGB §257(4)](https://www.gesetze-im-internet.de/hgb/__257.html) | Handelsbücher, Inventare, Eröffnungsbilanzen, Jahresabschlüsse, Lageberichte | **10 years** | Begins at year-end after last entry. |
| **DE** | HGB §257(4) | Buchungsbelege (booking receipts) | **8 years** | Reduced from 10y to 8y by Bürokratieentlastungsgesetz IV, effective 2025-01-01. Regulated entities (banks, insurance) retain 10y. |
| **DE** | HGB §257(4) | Handelsbriefe (business correspondence) | **6 years** | Including sent + received emails that document a Handelsgeschäft. |
| **DE** | [AO §147(3)](https://www.gesetze-im-internet.de/ao_1977/__147.html) | Books, inventories, balance sheets, annual financial statements | **10 years** | Tax-law floor mirrors HGB. |
| **DE** | AO §147(3) | Booking documents | **8 years** | Aligned with HGB §257 reform 2025. |
| **DE** | AO §147(3) | Other business documents (received/sent) | **6 years** | |
| **DE** | GoBD (BMF, 2019 update) | Digital records | **same as the underlying class** | GoBD adds *form* requirements (immutable, machine-readable) on top of HGB/AO durations. No separate retention number. |
| **US** | [SOX §802 / 17 CFR §210.2-06](https://www.law.cornell.edu/cfr/text/17/210.2-06) | Audit workpapers, audit memoranda, audit-related electronic records | **7 years from audit completion** | Applies to public-co auditors. SOX §103 (PCAOB authority) is the statutory mandate; §210.2-06 is the SEC rule. |
| **US** | [IRC §6001 + Pub 583](https://www.law.cornell.edu/uscode/text/26/6001) | General business records | **3 years** (filed-return floor) | Default IRS audit window. |
| **US** | IRC §6501(e)(1) | Records of any year with >25% gross-income omission | **6 years** | Extended assessment window. |
| **US** | IRC §6501(c)(1) | Fraud / no-filing | **indefinite** | No statute of limitations. |
| **US** | IRS Pub 583 (general guidance) | Employment-tax records | **4 years from tax due/paid** | Whichever is later. |
| **US** | IRS Pub 583 | Worthless-securities / bad-debt deductions | **7 years** | |
| **US** | IRS Pub 583 | Property records | **3 years after disposition** | Whichever is later. |
| **US** | [45 CFR §164.530(j)(2)](https://www.law.cornell.edu/cfr/text/45/164.530) | HIPAA documentation (NPP, authorizations, policies, risk analyses) | **6 years from creation OR last effect** | Does NOT apply to medical records themselves — those are state-law. |
| **US-CA** | [Cal Civ Code §1798.105](https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=1798.105.) | CCPA right-to-delete | **n/a (right-to-delete)** | Nine exemptions: completing transactions, security/fraud, legal obligations, internal-uses-aligned-with-expectations, government requests, free-speech, scientific research, debugging, breach-investigation. Per Cal Code Regs Tit. 11 §7050 a verified-deletable record must be deleted within 45 days, with one optional 45-day extension. |
| **EU** | [GDPR Art 5(1)(e)](https://gdpr-info.eu/art-5-gdpr/) | Storage limitation | **"no longer than necessary"** | Member-state law overrides where it sets a retention floor. |
| **EU** | [GDPR Art 17](https://gdpr-info.eu/art-17-gdpr/) | Right to erasure | **n/a (right-to-erasure)** | Art 17(3) exceptions: freedom of expression, legal obligation/public interest, public health, archiving in public interest, legal claims. |
| **EU** | DAC7 (Council Directive (EU) 2021/514) | Platform-operator records | **5 years** | After tax-year end. |
| **EU** | EU AI Act (Regulation (EU) 2024/1689) | High-risk AI system logs | **10 years (auto-generated logs)** | Art 12 + 19. Different durations per risk class. |
| **BR** | [LGPD Art 16](https://lgpd-brazil.info/) | Right to erasure (post-processing) | **n/a (right-to-erasure)** | Exceptions: legal/regulatory obligation, research (with anonymization), transfer to third party (with proper basis), exclusive controller use (anonymized). |
| **BR** | Decreto-Lei 486/69 + Art 1.194 Codigo Civil | Books of account | **5 years** from last entry; **indefinite** for income-tax purposes during the statute of limitations window. | ANPD has not issued a unifying retention regulation as of 2026-05. |
| **IN** | [Companies Act 2013 §128(5)](https://ca2013.com/128-books-of-account-etc-to-be-kept-by-company/) | Books of account, papers, financial statements | **8 financial years preceding the current FY** | Plus extension if Chapter XIV investigation is ordered. |
| **IN** | Income-tax Act §44AA + §44AB | Books for tax-audit cases | **6 years from end of relevant AY** | Generally aligns with §128; §128 is the binding floor for incorporated entities. |
| **IN** | GST Act §36 | GST records (invoices, registers) | **6 years from due-date of annual return** | Plus extension during pending appeal. |
| **MX** | [CFF Art 30](https://mexico.justia.com/federales/codigos/codigo-fiscal-de-la-federacion/) | Contabilidad y documentación fiscal | **5 years** from due-date of declaration | "Hechos cuyos efectos fiscales se prolonguen en el tiempo" → 5y from last fiscal year affected. |
| **MX** | CFF Art 30 (transfer pricing) | International transparency / Master File / Local File | **6 years** | Added in 2014 reform. |
| **MX** | Codigo de Comercio Art 38 | Libros, registros, comprobantes | **10 years** | Commercial-code floor; CFF is the tax-law floor (5y). The 10y commercial floor is the binding ceiling for kontor since accounting records cross both. |
| **CA** | [Income Tax Act §230(4)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-230.html) | Books of account, source documents | **6 years from end of last tax year** | Companies must keep historical records (share register, sales/wind-up records) **indefinitely**. |
| **CA** | Excise Tax Act §286 | GST/HST records | **6 years** | Aligned with ITA §230. |
| **CA** | Provincial corporations acts | Minute books, share registers, by-laws | **as long as company exists + ~6y after dissolution** | Ontario BCA s140; aligned with federal CBCA s20. |
| **AU** | [Corporations Act 2001 §286(2) + §1100](https://www5.austlii.edu.au/au/legis/cth/consol_act/ca2001172/s286.html) | Financial records (companies) | **7 years after transactions complete** | Mandatory under §286. |
| **AU** | ATO general (TAA Schedule 1 §382-5) | Tax-relevant records (sole traders, partnerships) | **5 years** | Whichever later: from prepared/obtained, or from when transaction/acts completed. |
| **AU** | Privacy Act 1988 APP 11.2 | "Take reasonable steps to destroy or de-identify when no longer needed" | **n/a (destruction obligation)** | No fixed duration; aligned with GDPR storage-limitation principle. |
| **JP** | [Companies Act Art 432(2) + Art 435(4)](https://www.japaneselawtranslation.go.jp/en/laws/view/3206/en) | Accounting books, important business documents, financial statements + schedules | **10 years** | From book-closing / creation. |
| **JP** | Corporation Tax Act + Electronic Books Preservation Act (Reiwa 7 update) | Tax records | **7 years** | Aligned to NOL carry-forward window; effectively 10y to satisfy Companies Act floor. |
| **JP** | APPI (Act on the Protection of Personal Information) Art 22 | Personal data, after purpose-of-use is satisfied | **n/a (destruction obligation)** | Aligned with storage-limitation principle. |
| **CN** | [Administrative Measures on Accounting Records (MOF/NAA 2015, effective 2016-01-01)](https://www.lexology.com/library/detail.aspx?g=6bb7955f-5b20-4e80-b623-1f43a2b1ebb4) | Original vouchers, bookkeeping vouchers, ledgers, journals, other account books | **30 years** | Doubled from 15y in 2016. |
| **CN** | Same | Financial reports (monthly/quarterly/half-year) | **10 years** | Tripled from 3y. |
| **CN** | Same | Bank statements | **10 years** | Doubled from 5y. |
| **CN** | Same | Annual financial reports + audit reports | **permanent** | |
| **CN** | PIPL (Personal Information Protection Law, 2021) Art 19 | "Minimum period necessary" | **n/a (destruction obligation)** | Aligned with GDPR/APPI/LGPD shape. |

**Note on starts-of-clock.** Every jurisdiction except CCPA defines a *clock-start* offset: Germany "end of calendar year after last entry"; US "tax year filed or due, whichever later"; Japan "from book-closing"; Mexico "from declaration due-date"; India "preceding the current FY". The `:retention-policy` entity therefore needs `:retention-policy/clock-anchor` ∈ `{:posting-date :year-end-after-posting :fiscal-year-end :return-due-date :record-creation :last-effect}`. Six anchors cover every jurisdiction surveyed.

## 6. Patterns to AVOID

1. **JCR's opaque `Hold`/`RetentionPolicy` objects.** Implementation-defined data carries zero introspection cost in Java but is a query-time disaster: you cannot ask "what's under hold today?" without a vendor-specific API. **Kontor models hold scope as datalog, queryable from the same engine that holds the postings.**
2. **XPath retention queries.** JCR's enterprise extensions sometimes attach holds to `jcr:retentionPolicy` via XPath path predicates (`//*[@status='archived']`). The same set of records under the same predicate at a different time can change unpredictably. **Datalog with a `:scope-eids-cache` and a `:scope-refreshed-at` is auditable; XPath-against-tree is not.**
3. **Privilege as free-text strings.** OFBiz's `Content/privilegeEnumId` is a foreign key into `enumeration` — fine. NetSuite's "tag the file with a memo line saying 'attorney-client'" is the anti-pattern. **Kontor uses a closed keyword set with a documented vocabulary.** Drift is impossible by construction.
4. **Anonymize-only DSAR.** Odoo's `privacy_lookup` anonymizes name and email and writes a `privacy.log` row. The partner remains identifiable through transaction history (their bank account number, their addresses, their unique combination of invoices and dates is itself PII). **Kontor must `:db/purge` the partner's PII attributes and rewrite the FK-by-display-name only if the consumer chose to.** Anonymization is not a substitute for erasure; it's an option for record classes the retention floor protects.
5. **Per-row retention.** Camunda's TTL is configurable per entity-class and per-instance. Per-instance retention is the maintainability hole — administrators forget to set it, the cron skips the row, history bloats. **Kontor retention is per-(entity-type, jurisdiction) only.** Per-row exceptions surface as legal holds (already a primitive).
6. **Retention as cron with no manifest.** Most OSS systems run retention as an opaque cron. **Kontor logs every purge as a `:retention-event` row (audit-doc'd) — the purge is itself an event in the bitemporal record, queryable later as "what did we purge under which policy on which date."** Same pattern as ADR-007 says about the commit chain.
7. **Holds with no expiry / release mechanism.** Many SAP ILM implementations never release a hold — the operations team forgets. **Kontor models `:legal-hold/released-at` + `:legal-hold/release-audit-doc`; the sweeper auto-warns if a hold is open beyond a configurable max-age (default 5 years).** A hold that has been forgotten is more dangerous than a hold that has been released — it pins data forever, breaking GDPR storage-limitation.
8. **One-size privilege/role mapping.** Mautic/Akeneo conflate "data subject" and "data controller employee" roles. **Kontor's `role->privileges` map is consumer-supplied** — the kernel does not pretend to know who is general-counsel for tenant X.
9. **Tightly-coupled DSAR + erasure.** WordPress' eraser is destructive by default. **Kontor's DSAR returns a *finding*; erasure is a separate `kontor.dsar/erase!` call** that respects the retention floor and any open holds. Accidental erasure of a row that the SOX floor protects is a regulator-visible compliance failure.
10. **Privileged data in the same DB column as ordinary data.** Salesforce's "field-level security" pattern. The kontor approach: privileged documents are `:audit-doc` rows with a `:audit-doc/storage-uri` to wherever the bytes live, and a `:audit-doc/privilege` tag. **The bytes never enter the kontor DB; only the *reference* is gated.** A breach of the kontor DB exposes the existence of the privileged document, not its contents.

## 7. Open questions for ADR drafting

These should be settled before ADR-049/050/051/052 are written.

1. **Should the legal-hold scope-query be a vector or a string?** Vector (`'[:find ?e :where ...]`) is sender-typed and Clojure-native; string is portable to a future SQL-bitemporal-shim per note 05. Recommendation: **store as Clojure data (EDN-serialized in the `:db.type/string` attribute, parsed at read-time)** — auditors will read it; future SQL migration is unblocked because the AST is recoverable.
2. **Sweeper refresh budget — global or per-hold?** Per-hold, defaulting from a global var. The default in Camunda's TTL pattern is the right shape.
3. **Where does `:retention-policy/clock-anchor` resolve?** The kernel knows posting dates and fiscal-year endings (period.clj). It does NOT know "return due-date" — that depends on the country + entity type. Recommendation: **`:retention-policy/clock-anchor-fn`** as a keyword that names a resolver registered by the l10n module. Default resolvers: `:posting-date`, `:fiscal-year-end-after-posting`, `:record-created-at`. l10n modules register `:de-aufbewahrung-begin`, `:us-tax-return-due-date`, etc.
4. **Should the hold-release require dual approval?** ADR-038 has `:approval-policy` already. Strong recommendation: **the hold-release transition `(open → released)` is a sensitive transition** and admin teams should be able to attach an `:approval-policy` requiring `[:role/general-counsel :role/cfo]` co-sign. The kernel ships the entity; the consumer attaches policy.
5. **`:legal-hold/scope-preview` representation.** The hold-creation handshake — counsel reviews the query's output before the hold takes effect — needs a stable artifact. Recommendation: **`:audit-doc/type :legal-hold-scope-preview`** carrying a CSV (or JSON) of the eid list with display-names + `:audit-doc/content-hash` for integrity. The hold's `:legal-hold/scope-preview-doc` ref binds the two.
6. **Should DSAR walker registration be schema-driven or code-driven?** Code-driven (`register-walker!`) is simpler; schema-driven (`:partner/subject-ref? true` on the FK attr) is auditable. Recommendation: **both** — schema attrs mark FKs as subject-references for the default walker; companion-specific walkers register procedurally for anything the default cannot see (e.g. derived caches in `kontor-collections`).
7. **Privilege-tag at posting level?** Should `:posting` carry an optional `:posting/privilege` too, or only `:audit-doc`? Recommendation: **audit-doc only.** A posting is a financial fact; the privilege is on the supporting document. If a privileged document attaches to a posting, the consumer query gates the *document URI*, not the posting amount.
8. **Per-tenant privilege defaults.** Different tenants have different general-counsel structures. The `role->privileges` map should be per-(tenant, role). Recommendation: defer this to consumer auth layers, but document the integration pattern as a `kontor.audit-doc/with-roles` macro or wrapper.
9. **Bitemporal retention.** If a partner gives consent on day 1, withdraws on day 30, the marketing record's retention window logically started at day 1 (consent) and stopped at day 30 (withdrawal). At day 60, can we still query "what marketing record applied to this partner on day 15" via `(d/as-of db day-15)`? Yes, until we erase. Recommendation: **the `kontor.dsar/erase!` op should accept an optional `:dsar/preserve-bitemporal? false` flag** — when truthy, only the current-time slice is purged, leaving prior `(d/as-of)` queries intact. Default false (full purge) for GDPR compliance; truthy for cases where the regulator allows it.
10. **Counsel-matter privilege defaults.** Note 17 §3.6 sketched `kontor-counsel`. Stage M does NOT need to ship that companion, but ADR-051 should reserve `:counsel-matter` and `:legal-invoice` as future namespaces so they don't clash. Recommendation: add `:counsel-matter/* :legal-invoice/* :legal-line/*` to ADR-049's reserved-namespaces list.
11. **The "purge while under hold" race condition.** What happens if a `:db/purge` and a `:legal-hold` *create* race? datahike's CAS ordering breaks the tie: the later commit sees the earlier. The middleware needs to assert: at purge-time, no hold matching the eid is open. The race-immunity argument is the same as ADR-038's status-machine race-immunity. Recommendation: **document the race-immunity invariant in ADR-049** so future maintainers don't try to "fix" it with locks.
12. **Default retention policies on the kernel.** Should the kernel install zero default policies, or one ("posted-financial-records: min-keep 10 years")? Recommendation: **zero policies in the kernel, all defaults in `kontor-l10n-<cc>` modules.** The kernel ships an *empty* policy seed; the l10n adds the rows. Same pattern as tax rates (ADR-026).

## Sources

**Primary statutes / regulator publications:**

- [HGB §257 (Aufbewahrung von Unterlagen)](https://www.gesetze-im-internet.de/hgb/__257.html) — Bundesministerium der Justiz, Gesetze im Internet
- [AO §147 (Ordnungsvorschriften für die Aufbewahrung)](https://www.gesetze-im-internet.de/ao_1977/__147.html) — Bundesministerium der Justiz
- [17 CFR §210.2-06 (Retention of audit and review records)](https://www.law.cornell.edu/cfr/text/17/210.2-06) — Cornell LII
- [SEC adopting release on Rule 2-06 (Section 802 implementation)](https://www.sec.gov/rules-regulations/2003/01/retention-records-relevant-audits-reviews)
- [26 USC §6001 (IRS general record rule)](https://www.law.cornell.edu/uscode/text/26/6001) — Cornell LII
- [45 CFR §164.530 (HIPAA Administrative requirements)](https://www.law.cornell.edu/cfr/text/45/164.530)
- [Cal Civ Code §1798.105 (CCPA right to delete)](https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=1798.105.) — California Legislative Information
- [GDPR Art 5 + Art 17](https://gdpr-info.eu/art-17-gdpr/) — gdpr-info.eu (publishing the Official Journal text)
- [LGPD Art 16](https://lgpd-brazil.info/) — lgpd-brazil.info (translation of Lei 13.709/2018)
- [Canada Income Tax Act §230](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-230.html) — Justice Laws Canada
- [Australia Corporations Act 2001 §286](https://www5.austlii.edu.au/au/legis/cth/consol_act/ca2001172/s286.html) — AustLII
- [Japan Companies Act (Kaishaho)](https://www.japaneselawtranslation.go.jp/en/laws/view/3206/en) — Japanese Law Translation
- [India Companies Act 2013 §128](https://ca2013.com/128-books-of-account-etc-to-be-kept-by-company/) — CA 2013 IRR
- [Mexico CFF Art 30](https://mexico.justia.com/federales/codigos/codigo-fiscal-de-la-federacion/) — Justia México
- [PRC Administrative Measures on Accounting Records (2015)](https://www.lexology.com/library/detail.aspx?g=6bb7955f-5b20-4e80-b623-1f43a2b1ebb4) — Baker McKenzie via Lexology
- [Federal Rules of Civil Procedure 37(e)](https://www.law.cornell.edu/rules/frcp/rule_37) — Cornell LII

**Standards bodies:**

- [W3C DPV (Data Privacy Vocabulary) 2.3, Feb 2026](https://w3id.org/dpv/) + [PD extension top categories](https://w3c.github.io/dpv/2.3/pd/)
- [JCR 2.0 (JSR-283) §20 Retention and Hold spec](https://docs.adobe.com/docs/en/spec/jcr/2.0/20_Retention_and_Hold.html) + [javax.jcr.retention.RetentionManager javadoc](https://developer.adobe.com/experience-manager/reference-materials/spec/javax.jcr/javadocs/jcr-2.0/javax/jcr/retention/RetentionManager.html)

**OSS reference code (file:line cited inline):**

- **Apache OFBiz** (Apache-2.0) — `/home/christian-weilbach/Development/ofbiz-framework/applications/datamodel/entitydef/{content,party}-entitymodel.xml` (no retention/hold entities — negative finding)
- **Odoo** (LGPLv3, reference only) — `/home/christian-weilbach/Development/odoo/addons/privacy_lookup/models/privacy_log.py:1-49`; `addons/data_recycle/models/data_recycle_model.py:19-99`
- **ERPNext** (GPL-3, reference only) — grep negative across `/home/christian-weilbach/Development/erpnext/erpnext/**/*.py`
- **XTDB v2** (MPL-2.0) — `/home/christian-weilbach/Development/xtdb2/src/test/clojure/xtdb/node_test.clj:510-523`; `dev/doc/high-level-tour.adoc:20`; `src/test/resources/docs/xtql_tutorial_examples.yaml` (ERASE examples)
- **WordPress** (GPLv2, reference only) — [WP_Privacy_Requests_Table class](https://developer.wordpress.org/reference/classes/wp_privacy_requests_table/); [Personal Data Eraser plugin hook](https://developer.wordpress.org/plugins/privacy/adding-the-personal-data-eraser-to-your-plugin/)
- **Datomic** — [Excision reference docs](https://docs.datomic.com/reference/excision.html) (proprietary but doc is public)
- **Hyperledger Fabric** — [Private Data architecture](https://hyperledger-fabric.readthedocs.io/en/latest/private-data-arch.html) (`blockToLive`)
- **Camunda 8 / Optimize** — [History cleanup docs](https://docs.camunda.io/docs/self-managed/components/optimize/configuration/history-cleanup/)
- **KillBill** — [API audit-log conventions](https://killbill.github.io/slate/)

**Supplementary / industry guidance:**

- [Pathlock SOX data retention guide](https://pathlock.com/learn/sox-data-retention-requirements/)
- [ICO storage-limitation guidance (UK GDPR)](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-protection-principles/a-guide-to-the-data-protection-principles/storage-limitation/)
- [Wikipedia Privilege log](https://en.wikipedia.org/wiki/Privilege_log)
- [ABA Crafting effective privilege logs](https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-november/crafting-effective-privilege-logs-legal-success/)
- [Hipaa Journal HIPAA retention requirements 2026 update](https://www.hipaajournal.com/hipaa-retention-requirements/)
- [Rule 37(e) spoliation overview, Judicature](https://judicature.duke.edu/articles/rule-37e-the-new-law-of-electronic-spoliation/)

Date: 2026-05-13. Single-agent research-before for Stage M. Verification: high (every retention number traces to a primary statute or regulator-published source; every OSS claim cites a file or canonical URL). Companion to note 17 (vendor landscape); note 17 covers the *vendor pain* angle, this note covers the *reference implementation* angle.
