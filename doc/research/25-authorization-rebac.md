# 25 — Authorization models for `kontor`: RBAC / ABAC / ReBAC / hybrid, with a recommendation for a `kontor-authz` companion shape

**Date:** 2026-05-13
**Status:** research input only — feeds a future ADR-053 design decision
**Scope:** survey the authorization landscape, deep-read the two closest analogs in the local source tree (Odoo, Tryton), summarize Zanzibar-shaped ReBAC, propose a shape for `kontor-authz`.
**Non-goals:** writing code, choosing a vendor, locking an ADR.

---

## 0. Why now

The `kontor` kernel enforces **no read-side access control today**. Every transactor records `:status-history/changed-by-uid` and `:transaction/changed-by-uid` for audit (ADR-038), `:approval-policy` enforces SoD-style write-time rules (`:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note`), and the substrate already shapes user/role/tenant-ish concepts in passing — but the kernel does *no read filtering*. A consumer that has a datahike connection sees everything.

This is fine for a single-tenant local install. It becomes a real gap when:
1. Multiple users with mixed seniority share one DB (junior bookkeeper vs CFO vs external auditor).
2. Privilege-tagged audit docs land (Stage M legal-research, in flight) — `:audit-doc/privilege ∈ {:attorney-client | :work-product | …}` is a classification, but right now anyone with the DB sees the doc anyway.
3. Multi-entity tenancy hardens (ADR-031): a parent-entity admin must see all subs; a sub-entity user must not see siblings.
4. ML/agent tools are wired in (research note 20) — an LLM should not get every customer's payment history "by accident" via tool poisoning.

The question is not "should kontor have authorization?" — that's obviously yes for production. The question is **where**: in the kernel, in a companion, or in the consumer.

This note feeds that decision. It is deliberately broad on the landscape so the maintainer has a comparator across the whole industry, then narrow on the two systems we can read locally (Odoo `/home/christian-weilbach/Development/odoo/`, Tryton `/home/christian-weilbach/Development/tryton/`) because their shape — domain expressions evaluated against an ORM — is the closest practical analog to what we'd build on top of datalog.

---

## 1. Landscape map

### 1.1 Classification axes

**RBAC** — Role-Based Access Control. Permissions assigned to roles; users get roles. NIST RBAC-0/1/2/3 levels (flat → hierarchical → constrained → symmetric).
**ABAC** — Attribute-Based Access Control. Decision a function of (subject-attrs, object-attrs, action, env). XACML codifies it. PERM-model engines (Casbin) and policy engines (OPA, Cerbos, Cedar) usually live here.
**ReBAC** — Relationship-Based Access Control. Permissions derived from a relationship graph: `(subject, relation, object)` tuples. Zanzibar (Pang et al. 2019), OpenFGA, SpiceDB, Ory Keto.
**Hybrid** — most production systems. Roles for coarse-grained access, attributes/relationships for fine-grained scoping, sometimes policy-as-code overlaid.

### 1.2 Comparison table

| System | Primary model | Row-level | Field-level | List-objects | Bitemporal | Negative perms | Engine venue | License | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Odoo | hybrid (RBAC + domain-ABAC) | yes (`ir.rule`) | yes (`groups=` on `fields.Field`) | yes (rules become SQL `WHERE`) | no | OR of group-rules + AND of global rules — no explicit deny | ORM-side, `_apply_ir_rules` → SQL | LGPLv3 | sudo escalation pervasive |
| Tryton | RBAC + domain-rules | yes (`ir.rule` via `ir.rule.group`) | yes (`ir.model.field.access`) | yes (`Rule.query_get` returns a sub-query) | no | OR semantics; no deny | ORM-side, `Rule.domain_get` | GPLv3 | cleaner than Odoo, no `sudo()` API |
| ERPNext / Frappe | RBAC + permission matrix + user-permissions | yes | yes | yes | no | no deny; "if-owner" flag | Python permission engine | GPL-3 | metadata-driven; matrix has ~10 verbs |
| Apache OFBiz | permission-string RBAC | partial (per-entity) | no | weak (entity-level only) | partial (`fromDate`/`thruDate` on `UserLoginSecurityGroup`) | no | Java `Security` interface | Apache-2.0 | permission strings like `ACCOUNTING_VIEW` |
| SAP S/4HANA | RBAC + auth-objects (ABAC) | yes (auth fields) | yes (via auth-objects) | indirect (programs filter) | no | implicit via missing values | ABAP runtime | proprietary | famous for SoD ruleset bloat (GRC) |
| NetSuite | RBAC + restrictions | yes (subsidiary / class / dept / location restrictions) | yes (form-level) | yes (queries auto-filtered) | no | partial | SuiteScript User Event scripts can add | proprietary | "Own + Subordinate" dynamic restriction |
| Oracle Fusion | RBAC (job/duty/role) | yes (data-security policies) | yes | yes | no | no | declarative + Apex-like | proprietary | role inheritance: job → duty → privilege |
| Salesforce | RBAC + role hierarchy + sharing | yes (OWD + sharing rules + manual + Apex-managed) | yes (FLS) | yes | no | sharing rules grant only — restriction rules grant DENY since 2020 | platform | proprietary | "permission explosion" |
| Dynamics 365 | RBAC + business units + teams | yes (BU hierarchy + team membership) | yes (field security profiles) | yes | no | no | platform | proprietary | hierarchical security |
| Zanzibar / OpenFGA / SpiceDB / Keto | ReBAC (relation-tuples) | yes (it IS the model) | typically out-of-scope | yes — separate ListObjects API | partial (Zookies = read-after-write consistency, not bitemporal) | exclusion via set-difference; SpiceDB has caveats | dedicated service | Apache-2.0 (OpenFGA, SpiceDB community) | Pang et al. 2019 |
| Cerbos | ABAC | n/a (stateless) | application supplies attrs | no — Cerbos doesn't know your data | no | yes (deny rules) | embeddable or sidecar | Apache-2.0 | policy YAML/CEL |
| Casbin | configurable (PERM) | depends on model | no | weak | no | yes (deny effect) | embeddable library | Apache-2.0 | RBAC/ABAC/ReBAC by config |
| Oso (Polar) | ReBAC + ABAC | yes (Polar DSL) | n/a | yes via "data filtering" | no | no | embeddable Rust core | Apache-2.0 (Polar) | Prolog-ish, interesting for datalog adjacency |
| OPA (Rego) | policy-as-code (ABAC) | depends on input | no | weak | no | yes | sidecar / library | Apache-2.0 | dominant for K8s/infra |
| AWS Cedar | ABAC + simple relations | yes via policy | n/a | partial | no | yes (forbid) | embeddable Rust | Apache-2.0 | "designed to reason about" |

**One-paragraph summary, per system:**

- **Odoo** — Two layers: `ir.model.access` is RBAC table-grants (group × model × CRUD); `ir.rule` is per-group domain expressions that become extra `WHERE` clauses when the ORM searches. Field-level is via `groups=` on the field. `sudo()` bypasses everything.
- **Tryton** — Same two layers, slightly cleaner: `ir.model.access` for model CRUD, `ir.rule` (grouped by `ir.rule.group`) for domain expressions. Field-level is its own model `ir.model.field.access`. No `sudo()` API — code switches to `user=0` explicitly inside a transaction context manager.
- **ERPNext/Frappe** — Permission matrix per (Role × DocType × {read, write, create, delete, submit, cancel, amend, print, email, report, import, export, share, set_user_permissions}). Plus User Permissions (per-user data restrictions, e.g. "Alice → Branch=North"). Engine `frappe.permissions` adds WHERE-clauses to queries.
- **Apache OFBiz** — Coarse permission strings (`ACCOUNTING_VIEW`, `ACCOUNTING_CREATE`). Per-row checking is bolted on by `EntityPermissionChecker` for content entities only. The `Security` interface is verb-oriented (`hasEntityPermission(entity, action, userLogin)`).
- **SAP** — Authorization Objects (e.g. `F_BKPF_BUK` = "accounting doc per company code") with multiple fields; users get roles via PFCG; a transaction code is gated by `S_TCODE`; HCM adds structural authorizations against the org chart. The complexity is real and audit firms charge a lot to clean it up.
- **NetSuite** — Standard + custom Roles. Per-role: subsidiary restriction (with optional "include subsidiaries"), department/class/location restrictions, accounting restriction. Plus Restrict View dynamic options ("None / Own / Subordinates / All"). Permission matrix per "permission" (e.g., Lists → Customers → Full).
- **Oracle Fusion Cloud** — Three role types: Job Role (e.g. "GL Accountant"), Duty Role (capability), Data Role (Job Role + data scope). Functional security via privilege grants; data security via policies on object instance sets.
- **Salesforce** — Org-Wide Defaults set baseline. Role Hierarchy grants up the tree. Sharing Rules grant criteria-based. Permission Sets and Permission Set Groups grant additive. Apex-managed sharing for complex code-driven cases. Field-Level Security per profile/permission-set. Restriction Rules (since Winter '21) add explicit *limit* logic. Salesforce Shield adds platform-level audit + encryption.
- **Dynamics 365** — Security Role assigned to user OR team. Role × Privilege matrix per "entity" with access-level dropdowns: None, User, Business Unit, Parent: Child BU, Organization. Field Security Profiles for FLS. Manager/Position hierarchical security stacks on top.
- **Zanzibar** — Relations (`viewer`, `editor`, `owner`, `member`) on objects (`doc:42`, `folder:99`, `group:eng`). Tuples `(object, relation, subject)`. Userset rewrites compose tuples via union/intersection/exclusion + `computed_userset` (re-route this relation through another) + `tuple_to_userset` (transitive: "viewer of doc:42 = member of doc:42#parent.viewer"). Consistency model via Zookies.
- **OpenFGA / SpiceDB / Keto** — Open Zanzibar implementations. OpenFGA (Auth0/Okta) has type-system DSL + ListObjects/Check/Watch APIs. SpiceDB (Authzed) adds caveats (context-aware policy on tuples). Keto (Ory) is the Go-stack flavor.
- **Cerbos** — Stateless policy evaluation. App sends "principal + resource + action" with all attributes; Cerbos returns ALLOW/DENY/NO_MATCH per (principal, resource, action). Policy in YAML with CEL expressions.
- **Casbin** — Configurable matcher via PERM model (Policy, Effect, Request, Matcher). Same engine implements RBAC, ABAC, ReBAC by changing the model file.
- **Oso / Polar** — Polar is a Prolog-flavored declarative language. Open-source Polar core is small; the commercial Oso Cloud builds on it. The data-filtering feature solves list-objects by translating Polar to SQL.
- **OPA / Rego** — Datalog-with-superpowers. Rego policy is evaluated against a JSON document. Used in many domains but adoption *inside* an application's permission layer (rather than ingress / infra) is mixed.
- **AWS Cedar** — Designed by AWS for "verified" reasoning. Strong type system, formal-methods-friendly. `forbid` overrides `permit`. Used in Verified Permissions and now AWS IAM resource policies in some services.

### 1.3 The Clojure / datalog adjacency

- **Datomic** — historically *no* authorization layer. Datomic's recommended pattern is a "filter db" function applied after pull: `(d/filter db pred)` returns a derived DB that hides certain datoms. This composes well with bitemporal `(d/as-of db t)`. But it's a *consumer* concern.
- **XTDB** — community has discussed authz extensively but ships no built-in model. Same pattern: an interceptor on the read path.
- **Lacinia** — Walmart's GraphQL implementation. Resolvers do their own auth checks. No framework-level model.
- **Pedestal interceptors** — the idiomatic Clojure venue for authz: an interceptor before a resolver checks; another after the resolver filters.
- **buddy-auth / Friend** — authentication-shaped, not authorization-modeled.
- **Datalog-as-policy** — has been proposed multiple times in academic papers (e.g., "Logic Programming with Default and Strong Negation" lineage); Rego is essentially this. There is no widely adopted "Datalog-AS-policy-language-FOR-business-systems" library, but the substrate is conceptually a perfect fit: a relationship graph queried by datalog is *already* a Zanzibar-shaped store.

---

## 2. Deep dive — Odoo's `ir.model.access` + `ir.rule`

**Source:** `/home/christian-weilbach/Development/odoo/odoo/addons/base/models/ir_model.py` (lines 2059–2202), `/home/christian-weilbach/Development/odoo/odoo/addons/base/models/ir_rule.py` (entire file), `/home/christian-weilbach/Development/odoo/odoo/orm/models.py` (lines 4099–4192, 5360–5392).

### 2.1 The two layers

**Layer 1 — `ir.model.access` (table-level CRUD).** One row per (group, model, permission-bitmap). Fields (`ir_model.py:2065-2072`):
```
name              char (display)
active            bool
model_id          M2O ir.model
group_id          M2O res.groups   ; NULL means "global / everyone"
perm_read         bool
perm_write        bool
perm_create       bool
perm_unlink       bool             ; Odoo's name for "delete"
```
Effect: rows are **additive disjunctions** — if ANY active row says (user-has-this-group OR group is NULL) AND `perm_X = true`, the user has X on the model. This is plain RBAC.

The check function (`ir_model.py:2142-2155`):
```python
@api.model
def check(self, model, mode='read', raise_exception=True):
    if self.env.su:
        return True                          # sudo bypass
    has_access = model in self._get_allowed_models(mode)
    if not has_access and raise_exception:
        raise self._make_access_error(model, mode)
    return has_access
```

The `_get_allowed_models` query (`ir_model.py:2120-2139`) is `ormcache`d on `(uid, mode)` and runs a single SELECT:
```sql
SELECT m.model
  FROM ir_model_access a
  JOIN ir_model m ON (m.id = a.model_id)
 WHERE a.perm_<mode>
   AND a.active
   AND (a.group_id IS NULL OR a.group_id IN <user_groups>)
 GROUP BY m.model
```

**Layer 2 — `ir.rule` (row-level domains).** One row per rule (`ir_rule.py:15-35`):
```
name           char
active         bool
model_id       M2O ir.model
groups         M2M res.groups          ; empty = "global"
domain_force   text                    ; Python-ish ORM domain
perm_read      bool (default true)
perm_write     bool (default true)
perm_create    bool (default true)
perm_unlink    bool (default true)
```

A rule's `domain_force` is a string that, evaluated with `safe_eval` in a context containing `user`, `company_id`, `company_ids`, produces an ORM domain like:
```python
[('company_id', 'in', company_ids)]
```
or
```python
[('user_id', '=', user.id), ('state', '!=', 'cancel')]
```

**Composition rule** (canonical, `ir_rule.py:139-171`):

> Group-rules are **OR-ed** together for rules whose group set intersects the user's groups. Global rules (no groups set) are **AND-ed** with the OR-of-groups result.

```
final_domain = AND(global_rules) AND OR(applicable_group_rules)
```

The code:
```python
group_domains: list[Domain] = []
for rule in rules.sudo():
    if rule.groups and not (rule.groups & user_groups):
        continue
    dom = Domain(safe_eval(rule.domain_force, eval_context)) if rule.domain_force else Domain.TRUE
    if rule.groups:
        group_domains.append(dom)
    else:
        global_domains.append(dom)

if group_domains:
    global_domains.append(Domain.OR(group_domains))
return Domain.AND(global_domains).optimize(model)
```

### 2.2 How the rule becomes SQL

`_search` in `orm/models.py:5360-5392` is where the rule composes into the query. After the user's own search-domain is converted to SQL, the security domain is appended:
```python
if check_access:
    self_sudo = self.sudo().with_context(active_test=False)
    sec_domain = self.env['ir.rule']._compute_domain(self._name, 'read')
    sec_domain = sec_domain.optimize_full(self_sudo)
    if sec_domain.is_false():
        return self.browse()._as_query()
    if not sec_domain.is_true():
        query.add_where(sec_domain._to_sql(self_sudo, self._table, query))
```

So *every* `search()` against a model implicitly has the security domain appended. The implementation is "rule-as-extra-WHERE" — there is no separate authz query; the rule's domain becomes part of the same SQL plan, which keeps performance roughly identical to the unfiltered query.

`check_access` (`orm/models.py:4099-4158`) covers four call sites: explicit user code, `read()`, `write()`, `unlink()`. It first checks `ir.model.access` (table-level), then computes the rule domain and uses `filtered_domain` (in-memory) on the affected records to see if any are forbidden:
```python
def _check_access(self, operation):
    Access = self.env['ir.model.access']
    if not Access.check(self._name, operation, raise_exception=False):
        return self, functools.partial(Access._make_access_error, self._name, operation)
    if any(self._ids):
        Rule = self.env['ir.rule']
        domain = Rule._compute_domain(self._name, operation)
        if domain and (forbidden := self - self.sudo().with_context(active_test=False).filtered_domain(domain)):
            return forbidden, functools.partial(Rule._make_access_error, operation, forbidden)
    return None
```

### 2.3 Field-level access — `groups=` on a `fields.Field`

A field carries a comma-separated `groups` attribute (`orm/fields.py:124-125`):
```
:param str groups: comma-separated list of group xml ids; this restricts
the field access to the users of the given groups only
```

The check (`orm/models.py:3370-3381`):
```python
def _has_field_access(self, field, operation):
    if not field.groups or self.env.su:
        return True
    if field.groups == NO_ACCESS:
        return False
    return self.env.user.has_groups(field.groups)
```

Effect: a user lacking a required group sees `False` / `None` for the field; the ORM strips it from the read result.

### 2.4 `sudo()` and the audit problem it causes

`recordset.sudo()` (`orm/models.py:5952-5976`) flips a flag (`self.env.su`) that short-circuits all access checks: rule eval, model access, field access. The docstring itself warns:

> Using sudo could cause data access to cross the boundaries of record rules, possibly mixing records that are meant to be isolated.

In production Odoo code, `sudo()` is *pervasive* (grep for `\.sudo\(\)` across the addons returns thousands of hits). It's used for legitimate cross-record reads (writing audit data; resolving config; cross-company sequence allocation). But every sudo call is a *latent privilege escalation* — if a user can inject data that flows through a `sudo()`-ed code path, they may end up writing or reading beyond their rule scope. Security audits of Odoo deployments routinely flag this.

### 2.5 Multi-company isolation

`res.company` is the multi-tenant boundary. Fields named `company_id` (M2O `res.company`) are the standard discriminator. There's a `_check_company_domain` mixin (`orm/models.py:3997-4097`) that enforces cross-record company consistency. The standard record rule for company isolation is exactly the `'company_id', 'in', company_ids` form above. `with_company(c)` (`orm/models.py:5988-5997`) sets the *active* company in the context — important because the user can be a member of several companies and the rule has to know which to filter on.

### 2.6 The taxonomy of canonical groups

`/home/christian-weilbach/Development/odoo/odoo/addons/base/data/res.groups.csv` ships the seed taxonomy. Conventional names:
- `base.group_user` — internal user
- `base.group_portal` — limited external user
- `base.group_public` — anonymous
- `base.group_system` — sysadmin / settings
- `base.group_no_one` — only shown in debug mode (effectively never)

Each functional module ships its own `<module>.group_*` rows (e.g. `account.group_account_manager`, `account.group_account_user`). The transitive closure of `implied_ids` (`res_groups.py:69-77, 245-253`) is the user's effective group set.

### 2.7 What Odoo gets right and wrong, as a model for kontor

**Right:**
- Two layers (table-CRUD + row-domain) is the right separation of concerns.
- "Rules become part of the query" is the only performance-sustainable approach (no after-the-fact filtering on 1M-row tables).
- Domain expressions are *data*, not code — admins author them.
- Field-level access exists and is composable with the rest.
- Implied-groups (transitive closure) is essential for non-bureaucratic role design.

**Wrong (or at least painful):**
- `sudo()` as a function any code can call is a foot-gun. Tryton's "wrap in a context manager" is safer.
- No explicit deny; you compose by careful set-construction. This makes negative scenarios ("user X cannot see anything tagged confidential") awkward.
- No bitemporal axis. "What could Alice see on 2024-Q4-close" is unanswerable — the rules apply *now*.
- The Python domain is *eval-ed* in production via `safe_eval`. Datalog/policy-as-data is a strict improvement.

---

## 3. Deep dive — Tryton's `ir.model.access` + `ir.rule` (+ field access)

**Source:** `/home/christian-weilbach/Development/tryton/trytond/trytond/ir/rule.py` (entire file, 381 lines), `/home/christian-weilbach/Development/tryton/trytond/trytond/ir/model.py:514-820`.

### 3.1 Three layers, not two

Tryton splits where Odoo combines:

**Layer 1 — `ir.model.access`** (`ir/model.py:514-746`). Per (model, group) CRUD bitmap, same shape as Odoo. Cached by `Cache('ir_model_access.get_access')`. The `get_access` query (`ir/model.py:589-675`) is interesting — it uses SQL `MAX(CASE …)` aggregation to compute the *union* of all matching grants:
```sql
SELECT model,
       MAX(CASE WHEN perm_read   THEN 1 ELSE 0 END) AS r,
       MAX(CASE WHEN perm_write  THEN 1 ELSE 0 END) AS w,
       MAX(CASE WHEN perm_create THEN 1 ELSE 0 END) AS c,
       MAX(CASE WHEN perm_delete THEN 1 ELSE 0 END) AS d
  FROM ir_model_access
 WHERE model IN <names>
   AND active = TRUE
   AND (group IN <user_groups> OR group IS NULL)
 GROUP BY model
```

**Layer 2 — `ir.rule.group` + `ir.rule`** (`ir/rule.py:42-150` + `161-381`). Note the two-level structure: a `RuleGroup` declares (model, group(s), perm-bitmap, global_p, default_p); the `Rule` rows under a group hold the actual domain expression in PYSON. **A rule-group with multiple `rules` succeeds if at least one rule's domain matches** — Tryton documents this explicitly (`ir/rule.py:70-73`):

> The rule is satisfied if at least one test is True. If there is no test defined, the rule is always satisfied if not global.

The shape per row (`ir/rule.py:161-170`):
```
__name__ = 'ir.rule'
rule_group = M2O ir.rule.group
domain     = Char (PYSON-encoded domain)
```

`global_p` and `default_p` are mutually exclusive flags (`ir/rule.py:88-93`): `global_p=True` means "applies to everyone, AND-ed"; `default_p=True` means "applies to all users by default, OR-ed with their group rules"; otherwise the rule-group applies only to its explicit `groups`.

**Composition** (`ir/rule.py:296-328`):
```
final = AND(global_rules) AND OR(group_or_default_rules)
```

Same final logic as Odoo, but with the extra `default_p` axis for "applies to everyone unless overridden."

**Layer 3 — `ir.model.field.access`** (`ir/model.py:749-820`). A *first-class entity* for field-level grants — (model, field, group, perm-bitmap). Cleaner than Odoo's "attribute on a field class," because:
- Admins can add/remove field grants without restarting the runtime.
- The grant is queryable like any other data.
- Migration is straightforward (drop a row; don't touch the model class).

### 3.2 PYSON instead of `safe_eval`

The rule domain is in PYSON — Tryton's typed, JSON-serializable expression language. The decoder is `PYSONDecoder` (`ir/rule.py:272-277`):
```python
decoder = PYSONDecoder(cls._get_context(rule.rule_group.model))
dom = decoder.decode(rule.domain)
```

PYSON ≠ Python: it has a finite expression grammar (Eval, If, Greater, In, …). This is a **strict security improvement** over Odoo's `safe_eval(string)` — Tryton's grammar simply cannot express side effects or arbitrary computation. The same idea (typed DSL, not eval) is what datalog-as-policy would offer kontor.

### 3.3 How the rule becomes a sub-query

Tryton's approach is subtly different from Odoo's. Instead of inlining the domain as a `WHERE` clause, `Rule.query_get` (`ir/rule.py:331-339`) returns a `Model.search(domain, query=True)` — i.e., the domain becomes an **IN-subselect on `id`**:
```python
@classmethod
def query_get(cls, model_name, mode='read'):
    Model = pool.get(model_name)
    domain = cls.domain_get(model_name, mode=mode)
    with Transaction().set_user(0, set_context=True), inactive_records():
        return Model.search(domain, order=[], query=True)
```
The caller then composes this sub-query into the main search via `id IN (sub-query)`.

This is slightly more pessimistic for the query planner than Odoo's flat `WHERE` (PostgreSQL usually rewrites it but not always), but it composes cleanly with views and aggregations.

### 3.4 No `sudo()` — wrap-in-transaction instead

Tryton's escape hatch is a *context manager*:
```python
with Transaction().set_user(0, set_context=True):
    rules = cls.browse(ids)
```
The user-zero scope is **bounded** — when the `with` block ends, the elevation ends. Compare to Odoo's `obj.sudo()` which produces a recordset that propagates the flag indefinitely until reattached. Tryton's design is harder to misuse.

Additionally, Tryton has `transaction.check_access` (boolean on the transaction object) — code can disable rule checking entirely for a block, but the disable is **explicit and bounded**.

### 3.5 What Tryton gets right

- **PYSON, not Python.** Typed DSL = better security AND better tooling.
- **Field-level grants as data, not code.**
- **Bounded escalation.** `with set_user(0)` instead of `.sudo()`.
- **OR-within-group + AND-of-globals** is identical to Odoo; consistent across both ecosystems.

### 3.6 What Tryton doesn't address

- Same bitemporal gap as Odoo.
- Same lack of explicit deny.
- Same lack of structural-authorization (org-chart) primitive.

---

## 4. Deep dive — Zanzibar's relation-tuple model

**Primary source:** Pang, R., et al. (2019). "Zanzibar: Google's Consistent, Global Authorization System." *USENIX ATC '19*. https://research.google/pubs/pub48190/

**OSS implementations:** OpenFGA (https://openfga.dev/), SpiceDB (https://authzed.com/), Ory Keto (https://www.ory.sh/keto).

### 4.1 The relation-tuple

The smallest unit:
```
(object#relation@subject)
```
Examples:
```
doc:42#viewer@user:alice               ; Alice can view doc 42
doc:42#owner@user:bob                  ; Bob owns doc 42
folder:99#parent@doc:42                ; Doc 42 is in folder 99
group:eng#member@user:alice            ; Alice is a member of group eng
doc:42#viewer@group:eng#member         ; Anyone who is a member of eng can view doc 42
```

The last form is a *userset* — the subject is itself a (object, relation) pair, not a concrete user. This is what makes Zanzibar a graph: subjects can be sets, defined by other tuples.

### 4.2 The namespace config (userset rewrites)

For each object type, a config defines how each relation is derived:
```
name: "doc"
relation { name: "owner" }
relation {
  name: "editor"
  userset_rewrite {
    union {
      child { _this {} }                            ; direct grant
      child { computed_userset { relation: "owner" } } ; or you own it
    }
  }
}
relation {
  name: "viewer"
  userset_rewrite {
    union {
      child { _this {} }
      child { computed_userset { relation: "editor" } } ; or you can edit
      child {
        tuple_to_userset {                          ; or you can view the parent folder
          tupleset { relation: "parent" }
          computed_userset { object: "$TUPLE_USERSET_OBJECT" relation: "viewer" }
        }
      }
    }
  }
}
```

Three rewrite operators: `_this` (literal), `computed_userset` (reroute within the same object), `tuple_to_userset` (walk a relation and reroute on the target).

Two additional operators in the paper: `intersection` and `exclusion` (set-difference). Exclusion is how Zanzibar models "deny" without an explicit deny rule — `viewer = members_of(eng) - members_of(banned)`.

### 4.3 Check API

`Check(object, relation, subject)` → `bool`. The implementation walks the rewrite tree, recursively resolving usersets, with caching at every level. The paper reports p95 < 10ms for global checks; the trick is **caching usersets** (which change far less often than the tuples that reference them) and the **Leopard index** for transitive membership in deep group hierarchies.

### 4.4 ListObjects — the hard one

`ListObjects(type, relation, subject)` → "the set of objects of `type` on which `subject` has `relation`." Zanzibar's paper doesn't dwell on this much; the open implementations (OpenFGA, SpiceDB) have wrestled with it for years.

The naive approach is "for each object, run Check" — O(N × Check). The better approach (OpenFGA's "reverse expand") starts from the subject and walks the rewrite tree *backwards*: "find every tuple `(?, viewer, alice)` directly; find every group Alice is in and every tuple `(?, viewer, group:X#member)` for those groups; recurse." This requires the rewrite tree to be amenable to reversal — which it usually is, but with caveats (intersection and exclusion are non-trivial to reverse).

The pragmatic compromise: **time-bounded ListObjects with optional pagination**. Both OpenFGA and SpiceDB expose this with a documented max-objects-or-give-up-and-fall-back-to-Check parameter. The "what can Alice see" question is fundamentally harder than the "can Alice see X" question.

### 4.5 Zookies — consistency without strong consistency

Zanzibar runs across the planet. To make "after I write a tuple, the next Check sees it" work without forcing every Check to hit the leader, Zanzibar issues a **Zookie** with every write — an opaque token representing "the snapshot you should read at to see this write." Consumers send the Zookie back on the next Check call.

This is **read-after-write consistency on the authz state**, not bitemporal. The state has one history (linearizable global order), Zookies just let clients sequence their own reads correctly.

### 4.6 Why this matters for kontor

The Zanzibar model maps **almost directly** onto datalog:

| Zanzibar | datalog/datahike |
|---|---|
| `(object, relation, subject)` tuple | `[?o ?r ?s]` EAV-ish triple |
| `userset_rewrite._this` | direct lookup |
| `computed_userset` | rule with rerouted relation |
| `tuple_to_userset` | recursive datalog rule |
| `intersection` | datalog conjunction |
| `exclusion` | datalog negation-as-failure |
| namespace config | the rule database |
| Check | a single datalog query |
| ListObjects | a "find all `?o` such that" query |

The single conceptual gap is **userset-style recursive rerouting** — datalog handles this with recursive rules, which datahike's query engine supports.

**The kontor substrate already has the relations.** Examples that already exist in the schema:
- `(:partner ?p :partner/assigned-collector ?u)` — Alice is the collector for partner P
- `(:entity ?e :entity/parent ?ep)` — multi-entity hierarchy (ADR-031)
- `(:invoice ?i :invoice/owner-org ?o)` — invoice belongs to org
- `(:audit-doc ?d :audit-doc/privilege :attorney-client)` — privileged classification
- `(:status-history ?sh :status-history/changed-by-uid ?u)` — who did what

We can already write authz checks as plain datalog rules over this graph. The missing pieces are: the *subjects* (user / role / membership entities, none of which exist in the kernel schema), an API that means "evaluate this check" (with caching), and an answer to ListObjects.

---

## 5. Pattern catalog

15 named patterns observed across the systems above. Each: name, description, where, when-to-use, when-to-avoid.

**P1 — Owner-only.** The creator/owner of a row is the only one who can see/edit it. Appears in: NetSuite "Restrict View: Own," Salesforce OWD=Private, Frappe `if_owner`. **Use** for personal data (drafts, notes, expense reports). **Avoid** for shared workflow data.

**P2 — Hierarchical team / role-hierarchy.** Grants propagate up the org chart. Salesforce Role Hierarchy. Dynamics 365 manager hierarchy. SAP HCM structural authz. **Use** when org-chart is stable and accurate. **Avoid** when teams overlap or shift fluidly.

**P3 — Role + scope.** Coarse role granted, plus per-row scope filter (e.g. `:role/accountant` + `:org/germany`). NetSuite subsidiary restriction. Odoo company-rule. **Use** as default for multi-entity tenants. **Avoid** when scope semantics differ per record.

**P4 — View-vs-edit split.** Read access broader than write. Most ERPs. **Use** for collaborative review workflows. **Avoid** when read-leakage matters (e.g. PII).

**P5 — Field-level masking.** Different fields on the same row visible to different users. Salesforce FLS, Odoo `groups=`, Tryton `ir.model.field.access`. **Use** when row is shared but some columns are sensitive (salary, SSN). **Avoid** when fields drive joins; masking causes broken aggregates.

**P6 — Delegated authority / impersonation.** User A acts as user B (with audit trail). Salesforce "Login As," Odoo `with_user(uid)`. **Use** for support / admin scenarios. **Avoid** as routine workflow — destroys the "who did what" audit story.

**P7 — Break-glass / sudo.** Time-bounded full access for incident response, with audit log. Odoo `sudo()` (broken — no time bound), Tryton `set_user(0)` (better — context-bounded), SAP "firefighter ID" (best — explicit ticket + auto-revoke). **Use** for incident response only. **Avoid** as routine pattern.

**P8 — Context-aware (time / location / MFA).** Permission depends on environment. SpiceDB caveats, Cedar context, AWS IAM conditions. **Use** for high-sensitivity flows ("transfers >€10k require MFA"). **Avoid** for ergonomic cost in common paths.

**P9 — Row-level via policy-as-data.** Rules stored in DB, evaluated per query. Odoo `ir.rule`, Tryton `ir.rule`, Frappe permissions. **Use** when admins (not developers) need to author rules. **Avoid** when rule complexity exceeds the DSL (then escape to code).

**P10 — Row-level via policy-as-code.** Rules in code, often a DSL. Cerbos, OPA/Rego, Cedar, Polar. **Use** when rules are stable and developer-owned. **Avoid** when admins need to author.

**P11 — Zanzibar relation tuples.** Authz state IS a graph; checks are queries over it. OpenFGA, SpiceDB. **Use** at scale (10M+ tuples), or when relationship semantics dominate (file-sharing, social, multi-tenant SaaS). **Avoid** when most decisions are stateless (then ABAC is cheaper).

**P12 — Approval-chain-derived.** "Can user X approve transaction Y" derived from the approval-policy state. Already in kontor (ADR-038). **Use** for SoD and write-time gating. **Avoid** as the only authz layer — doesn't cover read.

**P13 — Merge of roles + relationships.** Hybrid. "User has `:role/account-manager` AND `(?u :partner/assigned-account-manager ?p)`." All large ERPs end up here. **Use** as the default real-world shape. **Avoid** premature unification — make the two axes explicit.

**P14 — Sharing rules / declarative grants on criteria.** "Anyone whose region matches the record's region can view." Salesforce sharing rules. **Use** when access is criteria-driven and admins author. **Avoid** when criteria are temporal or relationship-shaped (use P9/P11 instead).

**P15 — Restriction rules / explicit deny.** Modern Salesforce, Cerbos `DENY`, Cedar `forbid`. **Use** for "block PII to non-trusted roles" carve-outs. **Avoid** as the only mechanism — most policies should be expressed positively (grant); deny is a safety net.

---

## 6. What the failures teach us

**SAP's GRC bloat.** S/4HANA's authorization model with ~150-200 standard auth-objects, each with 4-10 fields, multiplied across job roles in a large enterprise, produces a Segregation-of-Duties rule-set with tens of thousands of conflicts. SAP GRC (Governance Risk Compliance) is a separate product that exists to clean up the mess. The lesson: **a fine-grained-by-default model is unmanageable**; coarse-grained-by-default with explicit fine-graining where needed scales.

**Salesforce's "permission explosion."** Profiles, Permission Sets, Permission Set Groups, Role Hierarchy, Sharing Rules, Manual Sharing, Apex Managed Sharing, Restriction Rules, Org-Wide Defaults, Public Groups, Queues, Territory Hierarchy. Asking "why can Alice see this record?" can require examining 8+ layers. Salesforce's own "View Setup" page is essentially a debugger for permission queries. The lesson: **layered grants without a unified explanation tool are operationally untenable**; whatever model kontor adopts, it must answer "why?" cheaply.

**Odoo `sudo()` everywhere.** Production Odoo apps are riddled with `sudo()` for legitimate reasons (cross-record reads, system bookkeeping). Every sudo is a latent privilege-escalation. The lesson: **the escape hatch must be bounded** (Tryton's context manager) and **must be auditable** (every sudo invocation should be recordable, with a reason).

**The deny + grant ordering trap.** Casbin and Cerbos both support deny-then-grant or grant-then-deny modes. Real-world bugs arise when admins assume one and the system implements the other. AWS Cedar's design choice — `forbid` always wins regardless of order — is the safer default. The lesson: **if deny exists, deny must dominate**, monotonically.

**XACML over-engineering.** The OASIS XACML standard tried to be the universal authz language. It's verbose, slow, and seldom adopted outside government and a few finance shops. Modern Cedar, Polar, Rego are *deliberate* simplifications. The lesson: **a small, expressive language beats a complete-coverage standard**.

**Ranger / Sentry / OPA-everywhere learning curve.** Apache Ranger and Sentry (Hadoop authz) and OPA-as-application-authz all suffer from the same problem: the policy lives somewhere far from the data; the developer must learn one more language; the audit trail spans two systems. The lesson: **co-locating policy and data has real ergonomic value**, especially when the data layer already supports rich queries.

**Frappe's "User Permission" sprawl.** Frappe allows arbitrary per-user data restrictions (Alice → Branch=North). In practice, large tenants end up with thousands of per-user rows; the cache invalidation cost on every login is non-trivial; and removing a user requires touching many rows. The lesson: **prefer group-scoped restrictions over per-user**; if per-user is needed, model the per-user assignment as a first-class entity, not a sprawling table.

**Multi-entity sudo-by-default.** Many ERPs default cross-company reads to "yes if you're in any group." This leaks data between subsidiaries during audits. The lesson: **multi-entity must be a deliberate scope, not a side-effect of who-you-happen-to-be.**

---

## 7. The list-objects problem

Every authz system finds Check easy and ListObjects hard. The conceptual reason: Check is `policy(subject, action, resource) → bool` — given all inputs, evaluate. ListObjects is `{resource | policy(subject, action, resource) → true}` — *invert* the policy over the resource space.

Three pragmatic answers:

**(a) Don't do it.** Cerbos, classical OPA. The application sends a candidate set and Cerbos filters. This is fine when the candidate set is small ("the 50 records on this page") and breaks when it's the full database.

**(b) Translate policy to a database filter.** Odoo, Tryton, Frappe, Oso's "data filtering" feature. The policy gets compiled into a `WHERE` clause (or a sub-query), pushed into the database. This is what makes "show me all invoices Alice can edit" feasible at scale. The compromise: the policy DSL must be restricted enough to translate (no arbitrary code).

**(c) Reverse-expand the relationship graph.** OpenFGA / SpiceDB ListObjects. Walk the rewrite tree from the subject backwards. Works well for relation-tuple models; works less well in the presence of intersection/exclusion.

**For kontor:** if the policy is datalog over kontor's existing schema, (b) is *trivially* available — the policy IS the query. The "list every invoice Alice can edit" question is just `(d/q '[:find [?inv ...] :in $ ?alice :where (can-edit-invoice? $ ?alice ?inv)] db alice)`. That's the conceptual sweet spot.

The remaining performance question is index design — but that's exactly what datalog query optimization is for, and datahike's query engine is competent here.

---

## 8. Recommended shape for `kontor-authz` — three options

### 8.1 Option A — No `kontor-authz` (consumer-owned authz)

The kernel stays auth-blind. Document the pattern in the README and one canonical consumer (beleg). The `:changed-by-uid` audit trail covers "who did what." Read filtering is up to the consumer.

**Implementation:** a documentation patch, plus a pattern note in `doc/architecture.md` showing how to wrap kontor queries in a Datomic-style filter-db.

**Pros:**
- Zero kernel surface, zero ADR debt.
- Each consumer (beleg vs simmis vs a future SaaS frontend) picks the authz model that fits its UX.
- Composes with whatever auth-stack lives in the host app (e.g. Keycloak + Polylith).

**Cons:**
- Every consumer reimplements row-level filtering for the same kernel entities. The first time beleg and simmis both ship will reveal that their filter logic diverges in ways that matter (e.g. "what counts as 'my' partner?" — what they answer determines what kontor numbers each user sees).
- The Stage M `:audit-doc/privilege` tag is essentially unenforced — it's metadata waiting for an enforcement layer that nobody owns.
- Multi-tenant SaaS deployments of kontor will need *something* — they will write it themselves, badly.

### 8.2 Option B — Light primitive (recommended)

Kontor ships a small set of schema additions and a single datalog rule extension point. Two new namespaces:

```
:user/uid             string             ; opaque identity; matches :changed-by-uid
:user/active          boolean
:user/email           string
:user/displayname     string

:role/ident           keyword            ; :role/accountant, :role/cfo, :role/auditor, :role/external-counsel ...
:role/description     string
:role/implies         many [keyword]     ; transitive — accountant implies viewer, cfo implies accountant

:user-role/user       ref → :user
:user-role/role       keyword
:user-role/scope-org  ref → :entity      ; optional — scopes to a specific entity (ADR-031)
:user-role/from-vt    instant            ; bitemporal validity (composes with kontor.bitemporal)
:user-role/to-vt      instant
```

Plus a single API namespace:

```
(kontor.authz/can? db user-eid action resource-eid
                   {:as-of-tx t1 :as-of-valid t2})
   → boolean

(kontor.authz/why db user-eid action resource-eid)
   → [{:role/ident ... :rule-path [...] :grant true} ...]   ; explanation chain

(kontor.authz/visible db user-eid resource-type
                      {:as-of-tx t1 :as-of-valid t2})
   → eduction of resource-eids
```

The crucial bit: `can?` and `visible` use a **datalog rule** that consumers extend. Kontor ships a few seed rules (`can-view-own-org?`, `can-view-via-role?`, `can-view-via-assignment?`) and an extension point — consumers add their own rules to the namespace.

**Why this is the right size for the kernel:**
- It introduces 2 entity types (user, user-role-assignment) — both are clearly missing from the schema for *audit* purposes anyway (right now `:changed-by-uid` is a `:db.type/ref` to nothing).
- It composes with `:approval-policy` (write-time SoD): an approval-policy rule can reference the user's roles via the same datalog.
- It composes with `kontor.bitemporal`: the `:user-role/from-vt`/`-to-vt` axes are exactly the same shape as `:posting/valid-from`. "What role did Alice have on 2024-Q4-close?" works out of the box.
- It does NOT introduce a separate authorization service, a new runtime, a new DSL, a new policy file format. Everything is datalog.
- It does NOT solve "ListObjects at scale" — `visible` is just a datalog query, which means the consumer has to write good rules and the user has to wait for the query when the graph is wide. For accounting workloads (10s of thousands of partners, not 100M-row consumer data), this is fine.

**Cons:**
- Doesn't cover field-level masking. That's deliberate — the kernel doesn't shape fields, attributes do. A field-level layer can be added later (`(authz-mask :posting/amount)`) without breaking the row-level shape.
- No formal "deny" — the model is positive grant. Deny can be added as another rule type later if needed.
- Consumer-side responsibility to actually CALL `can?` in their query path — kernel doesn't enforce. (This is the same trap as Datomic's filter-db: if you forget to wrap, you bypass.)

### 8.3 Option C — Full ReBAC companion

A separate `kontor-authz` module that ships a Zanzibar-shaped relation-tuple store layered on datahike. Schema for relations + namespace config + Check API + ListObjects API + bitemporal-aware policy evaluation.

This is several weeks of work. The closest open-source kernel to port is **OpenFGA's TypeScript core** (Apache-2.0) — its rewrite tree and ListObjects implementations are clean. SpiceDB's Go core is also Apache-2.0 but has more surface area.

**Pros:**
- Best-in-class for complex relationship-driven authz.
- Solves ListObjects properly via reverse-expand.
- Natural fit for multi-tenant SaaS at scale.

**Cons:**
- Massive ADR and design surface for a feature most kontor consumers won't use.
- Forces every consumer to learn a second query language (the Zanzibar rewrite DSL) on top of datalog.
- Negates the central kontor advantage: *the relationships are already in the kernel's datalog graph*. Building a separate relation-tuple store on top means maintaining a denormalized view of the same facts.
- Higher operational cost (cache invalidation, consistency tokens, etc.).

### 8.4 Recommendation: Option B

The substrate is uniquely well-suited for Option B because the relationships kontor already models (`:partner/assigned-collector`, `:entity/parent`, `:invoice/owner-org`, `:audit-doc/privilege`) are exactly the inputs Zanzibar's rewrite tree would consume. We don't need a separate graph store — we have one. We need:

1. **User + UserRole entities** (so `:changed-by-uid` resolves to something) — schema-only.
2. **Datalog rules** for the canonical patterns from §5 (P1, P3, P9, P12, P13 are the essentials).
3. **A single `can?` / `visible?` API** plus an explanation function (`why`).
4. **Bitemporal-aware lookup** (defer to `kontor.bitemporal` — already there).
5. **Documentation** of the consumer's responsibility to wrap reads (the equivalent of Datomic's "stage of last reduce").

Option B is the right size for `kontor` Phase 1 (or 2). Option C remains a future possibility *if* a multi-tenant SaaS consumer materializes; the upgrade path is "your relation-tuple store now indexes into kontor's datalog facts." Option A — no authz at all — is *unacceptable* once Stage M legal-research lands `:audit-doc/privilege` and we can't actually enforce it.

---

## 9. The bitemporal-authorization angle

Almost no production authz system answers: **what could user X see at valid-time T, given that user X's role was Y at T?**

Salesforce can't. SAP can't. NetSuite can't. Even Zanzibar can't (its Zookies are read-after-write tokens, not historical valid-time anchors).

The reason: their authz state is stored in current-state tables (joined into the live query). When Alice's role changes, the historical query "what did Alice see at Q4-close" gets answered using her *current* role — which is wrong for any audit, compliance, or "what did the system look like that day" question.

**With kontor's substrate, this is nearly free.**

Worked example: a regulator asks "Alice, your Q4-close-preparation role was junior-bookkeeper. Did your view at the moment of close show transactions in entity DE-Sub2?" The kontor query:

```clojure
(let [cutoff #inst "2024-12-31T23:59:59Z"
      alice  [:user/uid "alice@example.com"]
      ;; resolve Alice's roles at valid-time cutoff
      roles  (d/q '[:find [?role ...] :in $ ?alice ?cutoff
                    :where
                    [?ura :user-role/user ?alice]
                    [?ura :user-role/role ?role]
                    [?ura :user-role/from-vt ?from]
                    [?ura :user-role/to-vt ?to]
                    [(<= ?from ?cutoff)]
                    [(< ?cutoff ?to)]]
                  db alice cutoff)
      ;; resolve transactions visible to those roles at vt=cutoff
      visible (kontor.authz/visible db alice :transaction
                                    {:as-of-valid cutoff
                                     :as-of-tx    (db-at-snapshot cutoff)})]
  visible)
```

The query plan:
1. `:user-role/{from-vt,to-vt}` are bitemporal — pick the role assignment effective on cutoff.
2. The authz rules `(can-view-transaction? ?u ?t)` are evaluated against the historical role.
3. `as-of-valid` and `as-of-tx` together specify both "what was true on cutoff" and "what did the system know by snapshot T."
4. The result is the set of transaction eids Alice could see — reproducibly, audit-defensibly, replayable.

No other production authz system in the table can answer this query without significant custom integration with their event store. For kontor it is one query because the substrate is already bitemporal.

This is the **unique selling proposition** of `kontor-authz` in Option B.

---

## 10. Composition with Stage M (privilege-tagged audit docs)

The Stage M legal-research (in flight) is wiring `:audit-doc/privilege` ∈ `{:attorney-client | :work-product | :without-prejudice | :gdpr-restricted | :sox-controls | :none}`.

This is a **classification**, not an ACL. It says "this doc is in this category" — it doesn't say who can see what categories.

The compositional pattern with Option B authz:

```clojure
(defrule can-view-audit-doc?
  [(can-view-audit-doc? ?u ?d)
   [?d :audit-doc/privilege ?p]
   [(privilege-visible-to-role? ?p ?role)]
   (has-role? ?u ?role)])
```

Where `privilege-visible-to-role?` is a small data table:
```
:attorney-client     -> :role/in-house-counsel, :role/external-counsel
:work-product        -> :role/in-house-counsel, :role/external-counsel, :role/litigation-team
:without-prejudice   -> :role/in-house-counsel, :role/external-counsel
:gdpr-restricted     -> :role/data-protection-officer, :role/admin
:sox-controls        -> :role/internal-audit, :role/external-auditor
:none                -> :role/everyone
```

The mapping is shipped as **data** (a default seed, easily overridable per tenant), not as code. An external auditor sees `:sox-controls` and `:none` docs; in-house counsel sees the privileged set; a junior bookkeeper sees only `:none`.

This composition pattern — "classifications as keywords + a roles-to-classifications data table + datalog rule" — is small enough to ship in the same kernel ADR as the user/role primitives.

**Implication for the maintainer:** the design call for Stage M's privilege tag and ADR-053's authz model are not independent. If Option B authz lands first, Stage M's privilege tag has an enforcement layer to compose with. If Stage M lands first without authz, the privilege tag is metadata only — fine for documenting what's privileged, useless for preventing access.

---

## 11. Open questions for ADR-053

The maintainer should resolve these before ADR-053 lands:

1. **Model choice** — Option B (light, datalog-rule-extensible) is recommended; the alternative is Option A (no kernel authz; consumer pattern only) which becomes acceptable only if there's a concrete plan that consumers will own the privilege-enforcement layer.

2. **Enforcement venue** — three sub-options inside Option B:
   - **Kernel middleware** — `can?` checks live inside `kontor.core` read functions. Maximum safety; breaks the "kernel is auth-blind" property; forces all consumers through one read API.
   - **Companion layer** — `kontor-authz` is an explicit module; consumers must call its `wrap-db` to get a filtered db. Symmetric with Datomic's filter-db pattern. Recommended for ergonomics and composability.
   - **Consumer-side** — schema + rules ship in kernel; calling `can?` is the consumer's responsibility. Most flexible; easiest to forget.

3. **ListObjects strategy** — datalog-query-as-policy-translation (recommended; works at accounting-data scale) vs. denormalized materialization (work-stealing) vs. punt entirely (recommended for v1; revisit if a SaaS deployment hits scale issues).

4. **Bitemporal replay contract** — does `can?` accept `:as-of-valid` and `:as-of-tx`? Recommended: yes — it's nearly free and is the unique selling point.

5. **Field-level masking** — in scope for v1 or deferred? Recommended: defer. Add `(authz-mask db user-eid attr-kw)` as a v2 helper when a real case lands. Premature design here is expensive.

6. **Deny semantics** — additive grants only (Option B as proposed) or grant-and-explicit-deny? Recommended: additive only for v1. Add a `:user-role/deny? true` flag in v2 if a real case demands.

7. **Sudo/break-glass** — model and API for the inevitable "the auditor needs full access for two hours." Recommended: a `(with-sudo-as user reason)` context manager (Tryton-style), every sudo logs a `:status-history` row, kernel refuses sudo without a non-empty reason string. This is mostly cultural / operational.

8. **Migration path to Option C if needed** — what would need to change. Recommended: schema additions only (relation-tuple cache table, namespace config table); the existing datalog rules become the "compiled" form of the namespace config; existing API stays.

9. **Integration with the Stage M privilege tag** — should the `:audit-doc/privilege` → role mapping be shipped as default data in the kernel or as a separate l10n-style module? Recommended: kernel ships a sensible default; tenants override via their own seed.

10. **Cost-center / dept / analytic-axis scoping** — should `:user-role/scope-org` (entity-level) be the only scope, or do we also need `:user-role/scope-cost-center`, `:user-role/scope-analytic-account`, etc.? Recommended: entity-level only in v1, broader axes when concrete cases land. The datalog rule mechanism supports any axis-scoping the schema exposes.

---

## 12. Citations

**Primary source files (local):**
- Odoo `ir.rule` — `/home/christian-weilbach/Development/odoo/odoo/addons/base/models/ir_rule.py` (entire file).
- Odoo `ir.model.access` — `/home/christian-weilbach/Development/odoo/odoo/addons/base/models/ir_model.py:2059-2202`.
- Odoo ORM rule application — `/home/christian-weilbach/Development/odoo/odoo/orm/models.py:3370-3392` (field access), `4099-4192` (check_access), `5360-5392` (search-time rule injection), `5952-5996` (sudo, with_user, with_company).
- Odoo `res.groups` — `/home/christian-weilbach/Development/odoo/odoo/addons/base/models/res_groups.py:1-120`.
- Tryton `ir.rule` — `/home/christian-weilbach/Development/tryton/trytond/trytond/ir/rule.py` (entire file).
- Tryton `ir.model.access` + `ir.model.field.access` — `/home/christian-weilbach/Development/tryton/trytond/trytond/ir/model.py:514-820`.
- OFBiz `Security` interface — `/home/christian-weilbach/Development/ofbiz-framework/framework/security/src/main/java/org/apache/ofbiz/security/Security.java` (entire file).
- OFBiz security entity model — `/home/christian-weilbach/Development/ofbiz-framework/framework/security/entitydef/entitymodel.xml:61-230`.
- OFBiz `EntityPermissionChecker` — `/home/christian-weilbach/Development/ofbiz-framework/framework/entityext/src/main/java/org/apache/ofbiz/entityext/permission/EntityPermissionChecker.java`.
- Kontor `:approval-policy` shape — `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj:3108-3162`.
- Kontor bitemporal API — `/home/christian-weilbach/Development/kontor/src/kontor/bitemporal.clj:1-60`.

**Primary academic source:**
- Pang, R., Caceres, R., Burrows, M., Chen, Z., Dave, P., Germer, N., Golynski, A., Graney, K., Kang, N., Kissner, L., Korn, J. L., Parmar, A., Richards, C., & Wang, M. (2019). *Zanzibar: Google's Consistent, Global Authorization System.* USENIX ATC '19. https://research.google/pubs/pub48190/ — the canonical relation-tuple paper.

**Vendor documentation (publicly accessible):**
- SAP authorization concept — https://help.sap.com/docs/SAP_NETWEAVER_700/52a4a8c2ee5d4d4581b95cdef4fe1ba1/ — auth-objects, PFCG, profile generator.
- SAP S/4HANA Fiori security — https://help.sap.com/docs/SAP_S4HANA_CLOUD/ — business catalogs, launchpads.
- NetSuite roles and permissions — https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N272388.html
- Oracle Fusion role taxonomy — https://docs.oracle.com/en/cloud/saas/applications-common/24a/oarrm/index.html
- Salesforce security architecture — https://help.salesforce.com/s/articleView?id=sf.security_overview.htm
- Salesforce Restriction Rules (deny-style) — https://help.salesforce.com/s/articleView?id=sf.security_restriction_rules.htm
- Dynamics 365 security roles — https://learn.microsoft.com/en-us/power-platform/admin/security-roles-privileges
- OpenFGA documentation — https://openfga.dev/docs
- SpiceDB documentation — https://authzed.com/docs
- Ory Keto — https://www.ory.sh/keto/docs/
- Cerbos — https://docs.cerbos.dev/
- Casbin — https://casbin.org/docs/overview
- Oso / Polar — https://www.osohq.com/docs
- OPA / Rego — https://www.openpolicyagent.org/docs
- AWS Cedar — https://docs.cedarpolicy.com/
- ERPNext permissions — https://docs.frappe.io/framework/user/en/basics/users-and-permissions

---

## 13. Verdict

`kontor-authz` should ship as a **light companion (Option B)** — user + role + assignment schema, a datalog-rule extension point, a `can?` / `visible?` / `why` API, and bitemporal-aware lookup that composes with `kontor.bitemporal`. ~2 entity types, ~3 new namespaces, 1 new file (`kontor/authz.clj`), under 500 LOC for the v1.

The substrate's datalog-shape and bitemporal axis turn what is normally a multi-month authz project into a small, focused module. The trade-off is "consumer must wrap their reads" — same trade as Datomic, same trade as every other lightweight authz primitive — and it is acceptable because kontor's audience is already a JVM/Clojure developer or systems integrator who can read documentation.

If a multi-tenant SaaS deployment of kontor materializes, the upgrade path to Option C (Zanzibar-shaped) is open and the Option B schema is forward-compatible — the relation-tuple store would index into the same user/role/assignment facts. Until then, Option C is over-engineering.

Option A (no authz at all) is unacceptable post-Stage M. The privilege tag without an enforcement layer is theater.

ADR-053 is the right home for this decision. The research is done; the design call is the maintainer's.
