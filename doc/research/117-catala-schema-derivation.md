---
date: 2026-05-24
title: 117 — Catala-derived `:tax-concept` / `:provision` / `:regime` schema for kontor
status: research — candidate datahike schema by analogy; no code, no commits
audience: maintainer + future Phase-N "law-as-data" implementer
---

# 117 — A Catala-derived `:tax-concept` / `:provision` / `:regime` schema for kontor

This note is the companion deep-read that note 116 §"Recommendation" called for:
take Catala's central abstractions at file:line depth, derive — by analogy,
license-clean — a candidate datahike schema for kontor that supplies what the
period-tax substrate (ADR-099, notes 102–105, 107–115) keeps asking for. The
output is a schema and a worked example, not a parser or a DSL. Nothing here
ships; the work-product is design-review fodder for an ADR call.

**License posture.** Catala is Apache-2.0 (`catala/LICENSE.txt:1-3` —
"Apache License, Version 2.0, January 2004"). Apache-2.0 is permissive and
freely combinable with EPL-1.0 from a copyright standpoint, **but** kontor's
working rule per `CLAUDE.md`/note 116 §C is the same as for Odoo: derive by
analogy, do not translate. Every Catala citation below is a *pointer for the
reviewer* to verify what kontor's design borrows from — the EDN schema is
hand-written kontor code informed by the read, not a port of OCaml types.

---

## §1. What Catala actually IS

Catala is the ICFP 2021 result of Sarah Lawsky's "definition-under-conditions"
formalisation of how lawyers reason. The implementation is an OCaml compiler
that takes literate-programming source files (`.catala_en`, `.catala_fr`)
intermixing legal prose and code blocks, and lowers them through five named
intermediate representations:
**surface → desugared → scopelang → dcalc → lcalc → scalc**. The first three
carry domain-meaningful structure; dcalc is the formal "default calculus" that
gives the semantics of exceptions; lcalc and scalc are conventional functional
and statement-form lowerings for backends (OCaml, Python, C, Java, JS).

### §1.1 The scope: a unit of computation that mirrors a section of statute

A scope is a record-shaped, side-effect-free computation declared with input
and output variables. From `compiler/scopelang/ast.ml:56-62`:

```ocaml
type 'm scope_decl = {
  scope_decl_name : ScopeName.t;
  scope_sig : scope_var_ty ScopeVar.Map.t;
  scope_decl_rules : 'm rule list;
  scope_options : Desugared.Ast.catala_option Mark.pos list;
  scope_visibility : visibility;
}
```

Each `scope_var_ty` (`ast.ml:50-54`) carries `svar_in_ty`, `svar_out_ty`, and
an `io` flag that distinguishes input / output / internal / context variables.
Concretely, a scope `Foo` with one input `y` and one output `x` (see
`tests/exception/good/groups_of_exceptions.catala_en:4-6`) is a typed function
`(input) → (output)` whose body is the set of *rules* defining `x`.

Scopes call subscopes: a `scope` may declare another `scope` as a sub-variable
(`compiler/desugared/ast.ml:246` — `scope_sub_scopes`), pass it inputs, and
read its outputs. The whole program is a directed acyclic graph of scopes, with
`scope_dependency` checked at compile time
(`compiler/scopelang/dependency.ml`).

### §1.2 The rule: definition + condition + consequence + exception

The desugared `rule` is the load-bearing data structure
(`compiler/desugared/ast.ml:130-137`):

```ocaml
type rule = {
  rule_id : RuleName.t;
  rule_just : expr boxed;             (* the condition / justification *)
  rule_cons : expr boxed;             (* the consequence, the value *)
  rule_parameter : (...) option;      (* if the def is parameterised *)
  rule_exception : exception_situation;
  rule_label : label_situation;
}
```

with `exception_situation` itself a sum type (`ast.ml:123-128`):

```ocaml
type exception_situation =
  | BaseCase
  | ExceptionToLabel of LabelName.t Mark.pos
  | ExceptionToRule of RuleName.t Mark.pos
```

A `BaseCase` rule is the "general rule." `ExceptionToLabel` or `ExceptionToRule`
records explicit precedence: rule R is an exception to a labelled group of base
rules. Exception groups can themselves carry exceptions, producing a tree
(`tests/exception/good/groups_of_exceptions.catala_en:179-185`: `base → intermediate → exception_to_intermediate`).

In the source language the writer says:

```catala
label base_x  definition x equals 0
exception base_x  definition x equals 1
```

(`tests/exception/good/exception.catala_en:7-12`) — the `label` names the
"default" definition and the `exception <label>` raises a higher-priority
definition that fires when its condition is true.

### §1.3 The default calculus: `EDefault { excepts; just; cons }`

The desugared scope language compiles every variable-as-many-rules into a single
expression in the **default calculus**, whose key constructor is `EDefault`
(`compiler/shared_ast/definitions.ml:626-639`):

```ocaml
| EDefault : { excepts : ('a, 'm) gexpr list;
               just    : ('a, 'm) gexpr;
               cons    : ('a, 'm) gexpr; } -> ...
| EPureDefault : (...)
| EEmpty       : (...)
| EErrorOnEmpty: (...)
```

The evaluator's operational semantics is six lines
(`compiler/shared_ast/interpreter.ml:965-987`): evaluate every `excepts`; count
how many are non-empty; if **zero**, evaluate `just`, return `cons` if true
else `EEmpty`; if **exactly one**, return it; if **two or more**, raise
`Runtime.Error Conflict` with all conflicting source positions. Catala therefore
gives "general rule, except X, unless Y" a precise, totally-defined denotation,
and turns "two exceptions at the same priority both fire" into a runtime
*conflict* with both source positions in the error — provenance and ambiguity
detection in one mechanism. The compiler can also detect conflicts statically
where conditions overlap by construction.

### §1.4 Source-location tracking: every node carries a `Mark.pos`

Every AST node is wrapped `Mark.pos` (`compiler/catala_utils/pos.ml:18` —
`type t = { code_pos : Lexing.position * Lexing.position; attr : attr list }`).
The literate-programming surface also tags every code block with its enclosing
**law heading** (`compiler/surface/ast.mli:282-287`):

```ocaml
type law_heading = {
  law_heading_name : string Mark.pos;
  law_heading_id : string option;
  law_heading_is_archive : bool;
  law_heading_precedence : int;
}
```

`law_heading_id` is a stable identifier the author writes in the literate text;
`law_heading_precedence` lets the lexer stack headings hierarchically
(`compiler/surface/parser_state.ml:50-62`). Combined with `Mark.pos`, every
computed value can be traced back to the article and to the source span that
introduced its defining rule — Catala's answer to "why is this number what it
is?"

### §1.5 Types: money is integer cents; rounding is banker's

`shared_ast/definitions.ml:222` lists the literal types:
`typ_lit = TUnit | TBool | TInt | TMoney | TRat | TDate | TDuration | TPos`.
At runtime (`runtimes/ocaml/catala_runtime.ml:21`) `type money = Z.t` — money is
big-integer **cents**, never a float. The standard `round` function
(`catala_runtime.ml:109-123`) is the canonical banker's rounding on `Q.t`
(rational decimals); `money_round` snaps to nearest cent. The `Q.t` decimal
type makes intermediate computations exact, so the only place rounding bias
can enter is the explicit `round` boundaries; this is the same hygiene kontor
already enforces with `BigDecimal` + HALF-EVEN (`kontor.money`, ADR-013).

### §1.6 Temporal validity: file-level archives + date guards

Catala does **not** have first-class "this rule is valid from D1 to D2"
machinery. The convention is two-pronged: (a) `law_heading_is_archive`
(`compiler/surface/ast.mli:285`) marks a heading as "this is the law as it
was, archived"; (b) ordinary `under condition date_courante in [...]` guards
inside rules express "this rate applies only in [..]" — the rule reaches the
default calculus as just another `EDefault { just; ... }`. The intercommunal
convention in the deployed CNAF/DGFIP corpora is one file per legislative
version, an outer `LawHeading { is_archive = true; ... }` for archives, and an
explicit-date condition for the boundary case. This pushes "law as it stood"
into file organisation + literate prose rather than into the type system.

This is exactly where kontor's bitemporality (ADR-008, ADR-048) is **stronger**
than Catala: kontor already has `:tx/valid-from` and an `:as-of-valid` query
axis, so a kontor `:provision` can be a single entity whose effective dates are
expressed in metadata, and "law as it stood on 2024-12-31" is a query parameter,
not a file boundary. We don't need to copy Catala's archival pattern.

---

## §2. The candidate kontor schema

Three new substrate-tier namespaces — `:tax-concept`, `:provision`,
`:regime` — and one extension to the period-tax components carry the design.
Every attribute is datahike-compatible (ref types, cardinality, EDN-serialisable),
namespacing is the kontor convention (`CLAUDE.md` §"Namespacing"), and the
schema is *additive* on top of what ADR-099 + note 105 already shipped.

### §2.1 `:tax-concept` — the cross-jurisdiction catalogue

A `:tax-concept` is the abstract "thing the law talks about" — *participation
exemption*, *rollover relief*, *non-refundable credit*, *lifetime cap*. It is
the lightweight catalogue the maintainer asked for in note 116 §D1 and the
shared dictionary that notes 108-115 kept rediscovering. Concepts compose with
ADR-090's `:concept-iri` seam so they can bind to XBRL / FIBO / gist URIs and
participate in the existing concept-IRI story.

```clojure
;; ---------- :tax-concept ----------
{:db/ident       :tax-concept/code
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/identity
 :db/doc         "Stable kernel-level catalogue key —
                  :rollover-relief, :participation-exemption, :lifetime-cap,
                  :loss-bucket, :holding-period-preference, :non-refundable-credit,
                  :refundable-credit, :surtax, :minimum-tax, :base-transform,
                  :elective-regime. Keyword form because the set is small,
                  closed-by-ADR-extension (note 101 discipline), and we want
                  enum-style equality."}

{:db/ident       :tax-concept/label
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Short human label — \"Participation exemption (e.g. DE §8b
                  KStG)\". For display only."}

{:db/ident       :tax-concept/family
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Coarse grouping — :base-adjustment | :credit | :surtax |
                  :rate-modifier | :temporal-deferral | :election. Lets
                  reports / explain-* group concepts by mechanism."}

{:db/ident       :tax-concept/concept-iri
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/index       true
 :db/doc         "Optional IRI into an external taxonomy (XBRL filing
                  taxonomy, FIBO tax module, internal gist URI).
                  ADR-090 seam — same shape as :account/concept-iri."}

{:db/ident       :tax-concept/description
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Multi-paragraph prose describing what the concept names.
                  This is the dictionary entry, not a statute citation
                  (the latter lives on :provision)."}
```

Five attributes, ten or so kernel-level catalogue entries — `:tax-concept` is
*deliberately* a tiny vocabulary. The rationale follows the McComb-aligned
substrate-seam pattern: a small fixed catalogue indexed by IRI gives downstream
queries a stable handle without legislating doctrine. By analogy with Catala:
this is the level at which two `EDefault` trees from two jurisdictions can
"talk" — a DE §8b provision and an FR régime mère-fille provision both
reference `:tax-concept/code :participation-exemption`, even though their
`:provision`-level rules differ. Catala has no such level (each scope is its
own thing); kontor benefits from the layer because its goal is multi-jurisdiction
substrate, not single-jurisdiction codification.

### §2.2 `:provision` — the jurisdiction-bound rule

A `:provision` is the kontor analog of a Catala *rule* (`desugared/ast.ml:130-137`):
one named, condition-guarded, signed contribution to a tax computation, with
explicit priority/exception precedence and full source-citation metadata.
Provisions compose with the existing adjustment-layer (note 105) by *being* the
data source for adjustment items: a `kontor.tax-schedule/apply-adjustments`
fold over the set of provisions whose `:provision/condition-fn` returns true
for the context is exactly Catala's `EDefault` resolver re-expressed as a fold.
The schema does NOT replace the adjustment algebra; it gives it a richer,
auditable input.

```clojure
;; ---------- :provision ----------
{:db/ident       :provision/code
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/identity
 :db/doc         "Jurisdiction-qualified identity:
                  \"DE-KStG-8b-95pct\" | \"US-IRC-1031-like-kind\" |
                  \"FR-CGI-219-I-b-PME-15pct\" | \"CA-ITA-44-replacement-property\".
                  String (not keyword) because we want jurisdiction prefixes
                  and section markers without re-quoting."}

{:db/ident       :provision/concept
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "The abstract :tax-concept this provision is an instance
                  of — every concrete provision belongs to exactly one
                  catalogue entry. Lets a cross-jurisdiction query
                  \"all participation exemptions\" enumerate them."}

{:db/ident       :provision/jurisdiction
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         ":de | :fr | :jp | :us | :us-ca | … . Coarse country /
                  sub-country jurisdiction tag; mirrors the country-code
                  pattern already used on :tax / :account."}

{:db/ident       :provision/authority
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Issuing authority — :de-bzst, :us-irs, :fr-dgfip, :jp-nta.
                  Same vocabulary as :tax/authority (schema.clj:1599 ff.)
                  so multi-jurisdiction filings join naturally."}

{:db/ident       :provision/statute
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Human citation: \"KStG §8b Abs. 1 Satz 1\" |
                  \"IRC §1031(a)(1)\" | \"CGI Art. 219-I-b\". The reviewer's
                  first stop. Compare Catala's law_heading metadata
                  (surface/ast.mli:282-287)."}

{:db/ident       :provision/statute-iri
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/index       true
 :db/doc         "Optional stable IRI into a legislative document base
                  (Akoma Ntoso Work IRI, EUR-Lex, BMJ Gesetze-im-Internet,
                  GovTrack). The cite is the human-readable form; this is
                  the machine-resolvable form."}

{:db/ident       :provision/effective-from
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "Inclusive lower bound of this provision's legal effect.
                  Conceptually duplicates kontor's bitemporal :tx/valid-from
                  (ADR-048), but kept explicit on the entity so a single
                  query can answer \"which provisions were in force on date
                  D?\" without joining transaction metadata."}

{:db/ident       :provision/effective-to
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "Exclusive upper bound. nil ⇒ still in force."}

{:db/ident       :provision/condition-fn
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Registered fn keyword that resolves to a pure
                  (fn [ctx] boolean) — Catala's rule_just
                  (desugared/ast.ml:132). The fn lives in code; the entity
                  stores only its identity. The default-evaluator looks it
                  up via a registered fn-table the same way
                  :schedule/type :formula already does (tax_schedule.clj:113).
                  Absent ⇒ unconditional (BaseCase)."}

{:db/ident       :provision/condition-args
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "EDN-encoded parameter map for the condition fn — e.g.
                  {:min-participation 0.10 :holding-period-days 365}.
                  Lets one fn serve many provisions that share the shape
                  (a participation-exemption fn parameterised by threshold)."}

{:db/ident       :provision/consequence
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "EDN-encoded consequence — Catala's rule_cons
                  (desugared/ast.ml:133), expressed as a tagged data form
                  the evaluator interprets. The same closed tag set as
                  the adjustment items (note 105) extended with rate and
                  base shapes:
                    {:op :credit       :refundable? false :amount-fn :de-bpa-credit}
                    {:op :surtax       :amount 0.055}
                    {:op :base-deduct  :amount-fn :de-8b-add-back :sign -1}
                    {:op :rate-elect   :sub-schedules [...]}
                    {:op :exempt}
                  Mirrors the adjustment-item vocabulary so existing fold
                  applies unchanged."}

{:db/ident       :provision/priority
 :db/valueType   :db.type/long
 :db/cardinality :db.cardinality/one
 :db/doc         "Integer precedence — higher wins. The default-evaluator
                  groups provisions by (concept, applicability), evaluates
                  conditions, and selects the highest-priority matching
                  provision. Two matches at the same priority raise
                  :kontor.tax/ambiguous with both :provision/code values —
                  the kontor analog of Catala's Conflict
                  (interpreter.ml:980-987). Default 0 (the base case)."}

{:db/ident       :provision/exception-of
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "Optional ref to another :provision this one carves an
                  exception to — Catala's exception_situation
                  (desugared/ast.ml:123-128). When set, this provision is
                  considered ONLY against the referenced base, and its
                  priority is local to the base's exception tree (Catala
                  groups_of_exceptions:174-185). The explicit ref makes
                  the exception tree queryable."}

{:db/ident       :provision/regime
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "Optional ref to a :regime — the elective container
                  this provision belongs to (FR PFU-vs-barème, BR Simples
                  vs Lucro Real). When set, the provision only applies if
                  the taxpayer has elected this regime for the period.
                  See §2.3."}

{:db/ident       :provision/audit-doc
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc         "ADR-038 :audit-doc refs — the original-language statute
                  PDF, a CPA opinion letter, a regulator FAQ. Multiple
                  docs may attach (statute + commentary + tax-ruling).
                  Mirrors the existing :audit-doc seams on other
                  governance entities."}

{:db/ident       :provision/citation
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/many
 :db/doc         "Multilingual citation strings — store one per language
                  using language-tagged JSON-shaped strings, e.g.
                  '{\"de\":\"§8b Abs. 1 KStG\",\"en\":\"Sec. 8b para. 1 CIT Act\"}'.
                  Cardinality-many lets the entity carry several aliases
                  (BMF guidance number, BFH ruling reference)."}
```

The schema has 14 `:provision` attrs. The shape is a literal translation of
Catala's `rule` record (`desugared/ast.ml:130-137`) into datahike's reference
+ scalar discipline — `rule_just` → `:provision/condition-fn` + `condition-args`,
`rule_cons` → `:provision/consequence` (EDN-tagged data, not a closure, so it
survives serialisation and bitemporal queries), `rule_exception` →
`:provision/exception-of` + `:provision/priority`, `rule_label` is implicit in
the unique `:provision/code`. The mark/position machinery is replaced by
`:provision/statute`, `:provision/statute-iri`, `:provision/audit-doc`, and
the existing kontor bitemporal substrate — *those four together* exceed what
Catala's `Mark.pos` provides because kontor can answer "law as it stood on date
D" as a query rather than as a file boundary.

### §2.3 `:regime` — the elective container

A `:regime` is the optional tax-treatment "container" a taxpayer elects: the
Brazilian Simples Nacional, the French micro-BNC vs. réel, the German
Kleinunternehmerregelung, the US S-corp election. The kontor period-tax
substrate already has a `:regime` *value slot* on `TaxReturnFacts` components
(`period_tax_provider.clj:94-95`); this schema lifts the slot into a
first-class entity so the *eligibility rules* and the *consequence set* are
explicit data, not buried in a per-jurisdiction provider.

```clojure
;; ---------- :regime ----------
{:db/ident       :regime/code
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/identity
 :db/doc         ":br-simples-nacional | :fr-micro-bnc | :fr-pfu |
                  :de-kleinunternehmer | :us-s-corp-election | :ca-spi |
                  :in-presumptive-44ad. The jurisdiction-scoped elective
                  containers a taxpayer chooses among."}

{:db/ident       :regime/jurisdiction
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         ":de | :fr | :br | :us | :us-state-ca | :ca | :in.
                  Same vocabulary as :provision/jurisdiction."}

{:db/ident       :regime/label
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Short human label."}

{:db/ident       :regime/eligibility-fn
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Registered fn keyword — (fn [ctx] boolean) — that
                  determines whether the entity qualifies (the
                  :de-kleinunternehmer revenue cap, the :br-simples
                  revenue-and-activity test). Distinct from
                  :regime/elected? — eligibility is the legal precondition,
                  election is the taxpayer choice."}

{:db/ident       :regime/eligibility-args
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "EDN-encoded parameters — {:max-revenue 22000M
                  :commodity :EUR}."}

{:db/ident       :regime/audit-doc
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc         "ADR-038 refs — the election form, the eligibility
                  attestation, the consenting tax-advisor opinion."}

{:db/ident       :regime/statute
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Citation, same shape as :provision/statute."}

{:db/ident       :regime/statute-iri
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/index       true}

;; ---------- :regime-election — the per-entity, per-period choice ----------
{:db/ident       :regime-election/entity
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "The :entity (ADR-031) electing the regime."}

{:db/ident       :regime-election/regime
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "The :regime elected."}

{:db/ident       :regime-election/effective-from
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "Inclusive lower bound. The election applies from this
                  date forward, subject to :effective-to."}

{:db/ident       :regime-election/effective-to
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "Exclusive upper bound. nil ⇒ open-ended."}

{:db/ident       :regime-election/election-doc
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "ADR-038 ref to the election filing (Form 2553 for
                  US S-corp, FR option-PFU letter, etc.) — the
                  taxpayer-side evidence of the choice."}
```

`:regime` plus `:regime-election` together replace what is currently a
loose `:regime` keyword on the `TaxReturnFacts` component
(`period_tax_provider.clj:94-95`) with a triplet — *the regime as a
catalogue entry*, *the eligibility rule as a fn*, *the taxpayer's election
as a dated event*. By analogy with Catala: a `:regime` is the rough
counterpart of a *subscope* (`scopelang/ast.ml:56-62`) — a self-contained
computation unit a parent computation calls into. Kontor's structure is
lighter because we don't need the full "scope as compilation unit" weight;
we only need the "this set of provisions is gated by an election" semantics.

### §2.4 The `:tax-return-component/provisions` extension

One small addition to the existing `:period-tax-provider` component map
(documented in `period_tax_provider.clj:74-100`) makes provisions
*observable in the output*:

```clojure
;; New optional key in the TaxReturnFacts component map:
;;   :provisions [<{:provision :code :evaluated-condition? :consequence
;;                  :resolved-amount}>]
;;     — the audit log of which :provision entities fired during this
;;       component's evaluation, with the condition outcome and the
;;       resolved consequence. Drives the kontor.explain story.
```

This is metadata, not schema — the existing `TaxReturnFacts` record already
admits opaque map shapes per component, so no `defrecord` edit is needed.
The change is purely a documented convention so `kontor.explain` (ADR-091)
can walk components → provisions → statute citations without invented
machinery.

---

## §3. Worked translation — DE KStG §8b participation exemption

Take the DE §8b 95 % participation exemption (note 108 §1.1 + note 107):
dividends received by a GmbH from another corporation are tax-free, with 5 %
added back as a non-deductible fictitious operating expense. The threshold for
the KSt arm is a 10 % participation at the start of the year (KStG §8b Abs. 4);
the GewSt arm requires 15 % (GewStG §9 Nr. 2a).

Two `:provision` entities, two `:tax-concept` references, full EDN:

```clojure
;; ---- Catalogue entry (kernel-level, shared) ----
{:tax-concept/code        :participation-exemption
 :tax-concept/label       "Participation exemption (dividends from significant corporate shareholdings excluded from taxable income)"
 :tax-concept/family      :base-adjustment
 :tax-concept/concept-iri "https://www.kontor.tax/concept/participation-exemption/v1"
 :tax-concept/description "A tax doctrine treating dividends received by a
                           corporate shareholder above some participation
                           threshold as exempt from the recipient's income
                           tax, typically with a small add-back (5 % in DE,
                           5 % in FR, 100 % in NL, 100 % in JP). Mitigates
                           economic double taxation across the corporate
                           chain. Instances: DE-KStG-8b, FR-CGI-216,
                           NL-Wet-VPB-13, JP-juyo-kabushiki."}

;; ---- DE KSt-arm provision ----
{:provision/code           "DE-KStG-8b-Abs1-Satz1-KSt"
 :provision/concept        [:tax-concept/code :participation-exemption]
 :provision/jurisdiction   :de
 :provision/authority      :de-bzst
 :provision/statute        "KStG §8b Abs. 1 Satz 1 (i.V.m. Abs. 4)"
 :provision/statute-iri    "https://www.gesetze-im-internet.de/kstg_1977/__8b.html"
 :provision/effective-from #inst "2008-01-01T00:00:00.000-00:00"
 :provision/effective-to   nil
 :provision/condition-fn   :de.kstg-8b/qualifying-participation?
 :provision/condition-args "{:min-participation 0.10M
                             :measure :start-of-year
                             :recipient-form #{:gmbh :ag :ug :se :kgaa}
                             :payer-form     #{:corporation}}"
 :provision/consequence    "{:op :base-deduct
                             :amount-fn :de.kstg-8b/dividend-95pct-exempt
                             :amount-args {:exempt-pct 0.95M
                                            :addback-pct 0.05M}
                             :sign -1
                             :affects [:corporate-income-tax]}"
 :provision/priority       100
 :provision/exception-of   nil
 :provision/regime         nil
 :provision/audit-doc      [[:audit-doc/title "BMF-Schreiben vom 28.04.2003 IV A 2 — KStG §8b"]
                            [:audit-doc/title "BFH-Urteil I R 31/13"]]
 :provision/citation       ["{\"de\":\"§ 8b Abs. 1 Satz 1 KStG\",\"en\":\"Sec. 8b para. 1 sent. 1 Corporate Income Tax Act\"}"]}

;; ---- DE GewSt-arm provision (the 15 % cousin) ----
{:provision/code           "DE-GewStG-9-Nr2a-GewSt"
 :provision/concept        [:tax-concept/code :participation-exemption]
 :provision/jurisdiction   :de
 :provision/authority      :de-municipal
 :provision/statute        "GewStG §9 Nr. 2a"
 :provision/statute-iri    "https://www.gesetze-im-internet.de/gewstg/__9.html"
 :provision/effective-from #inst "2008-01-01T00:00:00.000-00:00"
 :provision/condition-fn   :de.gewstg-9-2a/qualifying-participation?
 :provision/condition-args "{:min-participation 0.15M
                             :measure :start-of-year
                             :payer-form #{:corporation}}"
 :provision/consequence    "{:op :base-deduct
                             :amount-fn :de.gewstg-9-2a/dividend-exempt
                             :amount-args {:exempt-pct 1.0M}
                             :sign -1
                             :affects [:gewerbesteuer]}"
 :provision/priority       100
 :provision/citation       ["{\"de\":\"§ 9 Nr. 2a GewStG\",\"en\":\"Sec. 9 No. 2a Trade Tax Act\"}"]}
```

How it composes with the existing engine: when
`kontor.l10n-de.period-tax-provider/de-corporate-income-tax-provider` builds
its `TaxReturnFacts` for a GmbH that received €1m of dividends from a 12 %
participation, the new step is

1. **load applicable provisions** — datalog `(find ?p :where [?p :provision/jurisdiction :de] [?p :provision/effective-from <= period-end] ...)` plus `(or [?p :provision/effective-to nil] [?p :provision/effective-to > period-end])`.
2. **evaluate `condition-fn`s** in the period × entity context.
3. **for the firing provisions**, materialise the `:consequence` items via the
   registered `amount-fn` (`:de.kstg-8b/dividend-95pct-exempt` →
   `{:op :base-deduct :amount #money 950000.00M :sign -1}` for KSt;
   `:de.gewstg-9-2a/dividend-exempt` → `{:op :base-deduct :amount #money 1000000.00M
   :sign -1}` for GewSt — different rates encoding the 95 % vs. 100 %
   asymmetry that's the whole point of the two arms).
4. **inject the resolved items as adjustment items** into the existing
   `apply-adjustments` fold (note 105 / `tax-schedule/apply-adjustments`),
   distinguished by `:affects` so KSt sees only KSt-affecting consequences and
   GewSt sees only GewSt-affecting ones.

Note that `:op :base-deduct` is an *extension* of the note-105 adjustment
vocabulary, which today only knows `:credit` and `:surtax`. Provisions
expressed as base adjustments instead of tax-on-tax adjustments are the
generalisation note 105 §2 frontier-2 ("the carry") implicitly demands; the
schema here makes the extension explicit and ADR-trackable. This is the
*one substrate change* the provisions schema asks of the engine, and it stays
within the existing fold shape.

What the evaluator does NOT do: it does not reinvent the schedule, the
marginalisation, or the posting machinery. The whole point of the schema is
that `:provision` plugs into the existing adjustment-fold lane; the
`:tax-schedule` algebra (`tax_schedule.clj:64-110`) stays exactly as is. By
analogy with Catala: the schedule is the "computation that EDefault wraps"; the
provisions are the EDefault branches that gate it.

---

## §4. What was good — worth adopting conceptually

**4.1 The default + exception structural separation.** Catala's `EDefault`
node (`shared_ast/definitions.ml:626-631`) factoring "the conditional base
rule" from "the prioritised exceptions to it" produces a denotational semantics
in six interpreter lines (`interpreter.ml:965-987`). kontor adopts the shape
via `:provision/priority` + `:provision/exception-of` — same idea, expressed
in datahike's ref-and-keyword vocabulary, with the conflict-detection story
(two provisions at the same priority that both fire → `:kontor.tax/ambiguous`)
intact.

**4.2 Provenance is metadata, not commentary.** `Mark.pos` plus `law_heading`
(`surface/ast.mli:282-287`) make every Catala value traceable to a source span
inside a named law section, automatically. kontor's analog is the
`:provision/statute`, `:provision/statute-iri`, `:provision/audit-doc`,
*and* the existing bitemporal substrate — the four together give us a more
queryable trace ("which provisions fired for this number, in what statutes,
under which version of the law as it stood, and what audit docs support
them?") than a single `Mark.pos` field can. ADR-091's `kontor.explain` becomes
a richer walk.

**4.3 The catalogue layer is missing in Catala — and we should add it.**
Catala has no `:tax-concept`-style cross-jurisdiction dictionary: each scope
is its own world. The independent observation across notes 108-115 that the
same concepts keep showing up (participation exemption, rollover, lifetime
cap, holding-period preference) is the empirical case for the layer. The
layer is cheap (5 attrs, ~10-30 catalogue entries) and gives downstream tools
an enumerable inventory.

**4.4 Conflict as a typed runtime signal.** Catala raises `Runtime.Error
Conflict` (`interpreter.ml:987`) with both source positions; this turns a
modelling error into an immediately-diagnosed legal-interpretation question.
kontor adopts the discipline as `:kontor.tax/ambiguous` with both
`:provision/code` values + statute citations in the ex-info.

**4.5 Source-and-rules-in-one-artifact.** Catala's literate-programming
discipline (`.catala_en` files) keeps statute prose and computation
side-by-side. kontor doesn't adopt the file format — but the schema admits
the same discipline by making `:provision/statute` + `:provision/audit-doc`
mandatory-in-practice on every provision the maintainer accepts. A
review-checklist for the l10n module maintainer.

---

## §5. What was bad — worth rejecting (and why)

**5.1 The OCaml surface DSL.** Catala has *seven* lexers
(`compiler/surface/lexer_en.cppo.ml`, `lexer_fr.cppo.ml`, `lexer_pl.cppo.ml`,
plus locale-specific token spellings), a Menhir grammar (`parser.mly` —
~1100 lines), and a parser-state machine for tracking law-heading nesting
(`parser_state.ml`). All of it solves the problem "how do we let
lawyer-programmers write rules in pseudo-English with rich syntax?" — a
problem kontor doesn't have, because we expect EDN-as-DSL and a Clojure-savvy
l10n maintainer. Note 116 §C3 already drew the line; this concrete read
confirms there is nothing to revisit. We never need a parser.

**5.2 The full compiler pipeline.** Catala lowers through five IRs
(surface → desugared → scopelang → dcalc → lcalc → scalc), each with its own
AST and well-formedness invariants (`compiler/dcalc/invariants.ml`, etc.).
The pipeline is the cost of compiling to "real" backends (OCaml, Python).
kontor needs zero of this — `:provision` data is interpreted in-process by a
small Clojure evaluator that thunks the `condition-fn` and the `amount-fn`
through a registered fn-table. ADR-001 single-dep stays intact.

**5.3 Bindlib / higher-order abstract syntax.** Catala uses Bindlib
(`compiler/shared_ast/definitions.ml:659-674` — `boxed_gexpr`) for
binder-management — a sensible OCaml choice given alpha-equivalence
requirements in the AST. Datahike entities don't have binders; kontor's
`:provision/condition-args` is a flat EDN map. Borrowing nothing here is
straightforwardly correct.

**5.4 OCaml-specific runtime types.** `Z.t` (zarith big integers) and
`Q.t` (rationals) at `runtimes/ocaml/catala_runtime.ml:21` are the right
choice for OCaml; `java.math.BigDecimal` is the right choice for kontor and
already shipped (`kontor.money`, ADR-013). No work to do, just noting we
are not in deficit.

**5.5 `law_heading_is_archive` + file-per-version.** Catala's archive flag
(`surface/ast.mli:285`) is a workaround for the absence of bitemporality at
the language level. kontor doesn't need it — `:tx/valid-from` +
`:provision/effective-from`/`-to` cover "law as it stood" with a query, not
a file boundary. We borrow zero from the archive mechanism.

**5.6 Scope as compilation unit.** Catala's `scope_decl` (`scopelang/ast.ml:56`)
is both a domain concept ("the section of law this corresponds to") and a
compiler structure ("the unit we typecheck and lower"). We don't need the
latter, only a *thin* version of the former — `:regime` is the kontor analog
and is intentionally lighter: no inputs/outputs declaration, no subscope
machinery, just (eligibility-fn, election-event, governing-provisions-by-ref).
A regime is a *bag* of provisions an election gates; the dependency graph
between them is left to the evaluator.

---

## §6. Open questions for the maintainer

**Q1. Is the `:tax-concept` catalogue truly closed-by-ADR, or open-ended
per-l10n?** I proposed closed (note 116 §D1 tentative recommendation), but
that means a new concept (say, "patent box") forces a kernel commit. The
alternative — letting l10n modules define new concepts — risks the same
per-jurisdiction reinvention the layer is meant to prevent. **Suggestion:**
closed-by-ADR with a fast path (concept additions are 1-attr-row schema
changes, not API changes), and a quarterly review of l10n-suggested additions.

**Q2. `:provision/condition-fn` as a registered keyword vs. a sandboxed
EDN expression language.** Today the schema stores a keyword that resolves
to a Clojure fn via a registered fn-table (the same pattern
`tax_schedule.clj :formula` already uses). Alternative: define a tiny EDN
expression sub-language (`[:and [:>= :base 100000M] [:in :recipient-form #{:gmbh :ag}]]`)
the evaluator interprets directly, so provisions are pure data and survive
serialisation cleanly. **Trade-off:** EDN-expr keeps data discipline strict
but reinvents an expression language (note 116 §C3 mild warning); registered
fns are smaller and faster but blur "data vs code" — the same wart Catala
debates have. **My lean:** start with registered fns; design an EDN-expr
sub-language only when there's evidence of cross-jurisdiction expression
duplication.

**Q3. Catala's scope vs. kontor's `:provision` — is the difference
structural or just terminological?** Catala's scope is a typed function from
inputs to outputs; a `:provision` is one rule contribution to a base or a
tax. They are NOT the same — a Catala scope is closer to kontor's
*entire* `PeriodTaxProvider` (it's a unit of computation, inputs to outputs),
while a `:provision` is more like a single Catala rule inside a scope. I
think this terminology gap matters: a future "kontor `:scope` of provisions"
entity might be useful (a named bag of provisions evaluated together), but
it's premature before we have ≥3 jurisdictions exercising the schema. Leave
the namespace open (`:provision-scope/*`) as a designed-but-unused expansion.

**Q4. How does the evaluator detect ambiguity statically?** Catala can
sometimes prove statically that no two `excepts` overlap, by symbolic
condition analysis. In kontor's setup with `condition-fn` as opaque fns,
static detection is unavailable — ambiguity is a runtime trap only. **Is
that adequate?** Probably yes — note 105 + ADR-099 already commit kontor to
runtime-detection for related issues, and a regression test per jurisdiction
that asserts "no ambiguity for these example contexts" gives most of the
practical safety static analysis would offer.

**Q5. Should `:provision/consequence` carry the `:affects` tag, or should
that be reified as a `:provision/affects` ref-many edge?** A ref-many edge
to `:tax-concept` or to a `:period-tax/kind` keyword set is more queryable
("which provisions affect GewSt?") but adds a second attribute that
duplicates the inline value. **My lean:** carry it inline today; lift to a
ref-many later if the query is demanded.

**Q6. Does the `:regime-election` story belong in this schema, or in the
governance / status-machine substrate?** ADR-034 status-machines exist for
exactly this pattern (a dated decision that transitions an entity into a
different mode). The `:regime-election` schema I propose duplicates some of
that. **Resolution:** check whether `:status-history` + a new
`:status-machine` value `:regime-election` would do, before shipping the
parallel `:regime-election/*` namespace. If so, drop the parallel namespace.

**Q7. Should the catalogue ship with a starter set, or be empty-on-day-one?**
A starter set seeds the convention but commits the kernel to choices; an
empty catalogue forces every l10n migration to ship its concept(s) and
review them upstream. **My lean:** ship the 10-15 cross-jurisdiction concepts
notes 108-115 made visible (`:participation-exemption`, `:rollover-relief`,
`:loss-bucket`, `:lifetime-cap`, `:holding-period-preference`, `:non-refundable-credit`,
`:refundable-credit`, `:surtax`, `:minimum-tax`, `:base-transform`,
`:elective-regime`, `:like-kind-exchange`, `:replacement-property`) as a
starter set; everything else is l10n-driven additions reviewed against the
discipline.

**Q8. Schema-shape vs. database-shape — should `:provision/consequence` and
`:provision/condition-args` be stringified EDN, or first-class
`:db.type/edn` (datahike) attrs?** I wrote them as `:db.type/string` for
maximum portability (they round-trip through any backing store and need no
special read-string fence). If datahike's tuple / heterogeneous-value
support is solid for our use cases, this could be reconsidered. **Lean:**
string today, revisit when datahike EDN-typed attrs hit production
fitness.

**Q9. Cross-statute interaction — should provisions reference other
provisions for "applies notwithstanding §X"?** Catala expresses this via
exception-of; kontor's `:provision/exception-of` covers same-jurisdiction
interactions. The harder case is the cross-jurisdiction "notwithstanding any
treaty provision" — a US treaty-override clause. **Suggestion:** declare it
out of scope for the kernel; treaty overrides are an l10n concern with their
own attrs (e.g. `:provision/treaty-override` boolean) added per l10n
module if needed.

**Q10. Does the schema need a `:provision/derivation-rationale` field for
the human "why this priority is 100 not 200" story?** I omitted it; the
implicit answer is that priority is an authoring discipline the maintainer
documents in an `:audit-doc`. Add the field only if the priority numbers
start needing per-provision commentary that doesn't fit a one-line doc.

---

## Deliverable

- **File:** `/home/christian-weilbach/Development/kontor/doc/research/117-catala-schema-derivation.md`
- **Word count:** ~3,750 words.
- **Other writes:** none. **Commits:** none. **Code changes:** none.

**If I had another day on this:**

I would dig into how Catala's elaboration of state-machined variables
(`tests/variable_state/good/simple.catala_en` — `foo state bar / state baz /
state fizz`) turns a multi-stage scope variable into a sequence of dependent
`EDefault` evaluations. The pattern is the natural Catala expression of
multi-step computations (think "first compute taxable income, then compute
gross tax, then apply credits, then add surcharges") and would tell us
whether the kontor period-tax pipeline (`(scope, carry-in) → base-selector
→ base-transform → schedule → adjustment-fold → posting`, note 105 §0) maps
cleanly onto Catala's state machine and whether the `:provision/state` axis
deserves a place in this schema. I would also pull one of the actually
deployed French statutes from the separate `CatalaLang/catala-examples`
repository — the family-allowance code or the housing-benefit code — and
trace one numeric result end-to-end through `EDefault` to feel where the
abstraction wears thin under real-statute complexity (the cases note 116 §A3
hinted at but didn't quantify). That second read would also help validate
the answer to Q3 (scope vs. provision) with empirical evidence rather than
just terminology.
