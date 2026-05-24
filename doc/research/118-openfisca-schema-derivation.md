---
date: 2026-05-24
title: "OpenFisca-derived `:tax-concept` / `:provision` / `:regime` schema candidate"
audience: maintainer
status: schema-derivation research — informs (does not pre-empt) an ADR
licensing-note: |
  OpenFisca-core and openfisca-france are AGPL-3.0
  (`/home/christian-weilbach/Development/openfisca-core/LICENSE`,
  `/home/christian-weilbach/Development/openfisca-france/LICENSE.AGPL.txt`).
  Read for patterns only. No code is copied or paraphrased; no YAML parameter
  tree is lifted verbatim. File:line citations are evidence-of-concept,
  not source-of-translation. The candidate schema below is original Clojure
  datahike design *informed by* the OpenFisca evidence.
---

# OpenFisca-derived candidate schema for kontor

## Why this note exists

Note 116 surveyed the law-as-data field and recommended deeper reading on
two projects: Catala and OpenFisca. This note executes the OpenFisca half —
a code-depth read of `openfisca-core` plus a reality-check skim of
`openfisca-france` — and proposes a candidate `:tax-concept` / `:provision`
/ `:regime` / `:parameter` schema for kontor that absorbs OpenFisca's
operational gold (its parameter tree) while rejecting its operational lead
(its imperative per-variable Python formulas, which note 116 §C1 calls out
as the worst pattern to copy).

A sibling agent is doing the same exercise on Catala (writing note 117).
The two candidates will be synthesised by the maintainer; convergence is
signal, divergence is design tension worth surfacing.

---

## §1. OpenFisca at code depth

OpenFisca-core (≈4.2K LoC across the directories that matter for this
exercise) splits the legislation into two artefacts and one runtime:

1. **Parameters** — pure data, organised hierarchically and date-keyed.
2. **Variables** — Python classes with class-attributes (typing + metadata)
   and `formula_YYYY_MM_DD` methods that read parameters and call other
   variables.
3. **Simulation** — the runtime that takes a population (sets of entities)
   and a period, then walks the variable dependency graph, memoising
   results in `Holder` caches.

### 1.1 The parameter tree

The central data structure is a `ParameterNode` (a directory) recursively
containing other nodes, leaf `Parameter`s (date-keyed scalars), or
`ParameterScale`s (bracket ladders) —
`openfisca_core/parameters/parameter_node.py:54-58` declares
`children: dict[str, ParameterNode | Parameter | ParameterScale]`. The
on-disk encoding mirrors the in-memory tree: each subdirectory of a
country package's `parameters/` becomes a node; each `.yaml` file
becomes a leaf; an optional `index.yaml` carries node-level metadata
(`parameter_node.py:64-101`).

A leaf parameter (e.g. `openfisca-france/.../impot_societe/taux_normal.yaml`)
has `description` + `values:` (a map of `"YYYY-MM-DD": {value: ...}`)
+ a parallel `metadata:` map with `unit`, per-date-keyed `reference`
entries (CGI Article 219; loi numbers; legifrance URLs), and an
`official_journal_date` map. It parses into a reverse-chronological
`values_list: list[ParameterAtInstant]` (`parameter.py:77-111`);
`_get_at_instant(instant)` linear-scans and returns the value whose
`instant_str <= instant` (`parameter.py:213-217`). Date keys must match
the ISO `YYYY-MM-DD` regex (`parameter.py:84-89`). The shape is
essentially **a sorted map keyed by start-instant; lookups are "value
in force on date D."**

A scale parameter (`parameter_scale.py`) carries a `brackets:` list;
each bracket is itself a small map with `threshold:` and `rate:` (or
`amount:`, or `average_rate:`) — and crucially, *each of those sub-fields
is itself a date-keyed map* — one bracket's threshold can change
independently of its rate
(`openfisca-france/.../bareme_ir_depuis_1945/bareme.yaml:9-100`). The
scale's kind (Marginal/LinearAverage/MarginalAmount/SingleAmount) is
inferred at instant resolution from which sub-fields the brackets carry
(`parameter_scale.py:83-121`). The scale exposes `calc(base)`
(`taxscales/marginal_rate_tax_scale.py:35-80`).

This parameter tree is OpenFisca's strongest pattern: hierarchical naming
doubles as a citation namespace; per-date metadata pins every value to a
statute; the runtime cost is trivial.

### 1.2 The variable system

A `Variable` is a Python class with class attributes carrying type
(`value_type`), entity (`entity`), period
(`definition_period`, the closed `DAY|MONTH|YEAR|ETERNITY|WEEK|WEEKDAY`
enum at `periods/date_unit.py:97-107`), label, optional `reference`
citation string, and one-or-more `formula[_YYYY[_MM[_DD]]]` methods
(`variable.py:99-191`). The dated-method-name convention is parsed at
`variable.py:337-369`; `formula_2014_01_01` becomes a SortedDict entry
keyed by `2014-01-01`; `get_formula(period)` walks reverse to find the
most recent formula whose date is ≤ period (`variable.py:384-432`).
Bodies read parameters via `parameters(period).path.to.parameter` and
call other variables via `foyer_fiscal('rni', period)`.

The variable system is OpenFisca's **operational weakness** from
kontor's view:

- "Reason a formula changed" is encoded by *date alone* — there is no
  link from `formula_2018_01_01` to the loi-de-finances article that
  motivated it. Citation is per-parameter (good) but not per-formula-body.
- Each formula reinvents abstractions. The
  `credits_impot.formula_2021_01_01` body (≈70 lines at
  `credits_impot.py:11-79`) hand-codes refundable-vs-non-refundable
  classification, `min_`/`max_` against a plafond, NumPy arithmetic —
  none of it lifted into a generic "fold credits against a plafond"
  pattern. Every credit-formula in France reinvents this.
- Imperative *because* it must vectorise for microsimulation. kontor's
  use case is N=1, so the cost (per-variable Python functions) buys
  nothing.

This is the C1 anti-pattern in note 116. §2 has to express the same
content as data without collapsing into per-provision Clojure functions.

### 1.3 The entity / population model

`Entity` is a single person (`entities/entity.py:9-58`); `GroupEntity`
contains persons under named `Role`s (`group_entity.py:14-122`). The
French package defines `Individu`/`Famille`/`FoyerFiscal`/`Menage`. A
`Variable` declares which scope it lives on (`variable.py:143`); when a
formula crosses scopes it uses projector methods to sum/max/min.

For kontor: the *multi-tier entity model* is a microsimulation concern
that does not apply (kontor lives one rung up — one legal person per
entity; intra-family relationships ride on `:partner` and parent/child
links per ADR-031). The *concept* — "the scope of an aggregate is
first-class metadata" — is what kontor's `kontor.report/marginalize`
family (ADR-096) already implements via `:dimension`-pivot. Borrow the
concept; skip the GroupEntity/Role machinery.

### 1.4 Periods and the reform mechanism

A `Period` is a `(unit, start_instant, size)` triple
(`periods/period_.py`, 920 LoC). Variables live at one of
`{DAY,MONTH,YEAR,ETERNITY,WEEK,WEEKDAY}` (`date_unit.py:97-107`);
`calculate_add` / `calculate_divide` (`simulation.py:180-279`) convert
between scopes. kontor's period (ADR-014) is also a closed enum, and
`PeriodTaxProvider` (ADR-099) takes period as input data — the
takeaway "period is first-class data, not a string" is already in
place.

A `Reform` is a subclass of `TaxBenefitSystem` that overrides
parameters/variables (`reforms/reform.py:9-87`).
`modify_parameters(modifier_function)` deep-copies the baseline tree,
runs the modifier, replaces `self.parameters` (`reform.py:68-87`).
Useful for counterfactual analysis ("what if the rate were 0.25?"). For
kontor the bitemporal substrate handles "law as it stood"; "law as
it would have been under proposed amendment X" wants a first-class
hook — `:regime/extends` in §2.3 fills that.

---

## §2. The candidate kontor schema

Four new datahike namespaces — `:tax-concept`, `:provision`,
`:regime`, `:parameter` — plus minimal extensions to existing ones. The
shape collapses OpenFisca's parameter-vs-formula split by making the
**schedule itself** (the existing `kontor.tax-schedule` algebra,
ADR-099 / note 105) the structured-data analog of OpenFisca's "formula,"
and by treating *parameters as inputs to schedules* rather than as a
separate runtime artefact.

This means a `:provision` is one of three shapes:

1. **A `:tax-schedule` config**, parameterised by named `:parameter`
   entities. The generic evaluator (the existing
   `kontor.tax-schedule/apply-schedule` + `apply-adjustments`) is the
   only imperative thing in the system.
2. **A `:scope-rule` data shape** (a marginalize/projector spec) that
   says "the base for this provision is `σ_E` of the following accounts."
3. **An `escape-hatch :compute-fn`** — a registered Clojure function
   keyword resolved via a per-l10n-module registry (per note 116 §D2).
   Reserved for genuinely non-tabular schedules (a notch; a complex
   multi-variable election). Used sparingly; the generic evaluator
   covers ≥80% of cases.

### 2.1 `:tax-concept` — the cross-jurisdiction abstraction

```clojure
;; A jurisdiction-neutral tax concept (rollover-relief, participation-
;; exemption, lifetime-cap, holding-period-preferential-rate, refundable-
;; credit, …). Append-only; concepts are never removed (note 116 §D1).
;; Per-jurisdiction provisions reference one or more concepts via
;; :provision/concepts.

[{:db/ident       :tax-concept/code
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}
 ;; e.g. :tax-concept/rollover-relief, :tax-concept/participation-
 ;; exemption, :tax-concept/lifetime-cap, :tax-concept/holding-period-
 ;; preferential-rate, :tax-concept/refundable-credit,
 ;; :tax-concept/non-refundable-credit, :tax-concept/surtax-on-tax,
 ;; :tax-concept/minimum-tax-floor, :tax-concept/progressive-bracket,
 ;; :tax-concept/flat-rate, :tax-concept/income-banded-surcharge.

 {:db/ident       :tax-concept/label
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}

 {:db/ident       :tax-concept/family
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; The closed period-tax-kinds enum extended to include rate-structure
 ;; families: :income / :corporate / :capital-gains / :property /
 ;; :payroll / :indirect / :rate-shape / :adjustment-shape. Used by the
 ;; evaluator to dispatch.

 {:db/ident       :tax-concept/concept-iri
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}
 ;; ADR-090 extension. The seam to XBRL / FIBO / authority-published
 ;; taxonomies. e.g. "https://www.xbrl.org/concept/RolloverRelief".

 {:db/ident       :tax-concept/documentation
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}]
```

Why a concept catalog at all? Notes 108-115 independently named the same
cross-jurisdictional patterns. Without a catalog, `kontor-l10n-de`'s
`:provision/8b-kstg`, `kontor-l10n-fr`'s `:provision/mere-fille`, and
`kontor-l10n-jp`'s `:provision/juyo-kabushiki-kojo` are linked only by
prose comments. With one, they all reference
`:tax-concept/participation-exemption`, the explain layer (ADR-091) can
"show me every provision implementing this concept across our books,"
and a generic UI can group them.

### 2.2 `:provision` — the per-jurisdiction encoded statute

```clojure
[{:db/ident       :provision/code
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}
 ;; Namespaced + qualified: :de.kstg/8b-1, :fr.cgi/219-i-b,
 ;; :us.irc/1031, :jp.cit/57-1. The qualifying prefix encodes
 ;; jurisdiction.statute-class; the suffix tracks the article.

 {:db/ident       :provision/label
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}

 {:db/ident       :provision/jurisdiction
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; ISO 3166-1 alpha-2 lower-cased keyword: :de, :fr, :us, :jp, :in.
 ;; Sub-national jurisdictions encode as :us-ca, :ca-qc, :in-mh.

 {:db/ident       :provision/concepts
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many}
 ;; One provision can implement multiple concepts (a refundable credit
 ;; with an income-banded phase-out implements both
 ;; :tax-concept/refundable-credit AND
 ;; :tax-concept/income-banded-surcharge).

 {:db/ident       :provision/regime
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one}
 ;; The :regime this provision belongs to (e.g. fr.is.taux-reduit).
 ;; Regimes group provisions that compose into one taxpayer computation.

 {:db/ident       :provision/statute
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}
 ;; Canonical citation string: "CGI Article 219, I-2-b". Multi-language
 ;; via :provision/citations (next).

 {:db/ident       :provision/citations
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true}
 ;; Component refs to {:citation/lang :en, :citation/text "...",
 ;;                    :citation/url "https://legifrance..."}.
 ;; ADR-078 :audit-doc/language pattern. Inspired by OpenFisca's
 ;; per-date :metadata.reference but lifted out of value-history so
 ;; one citation can cover multiple parameter changes.

 {:db/ident       :provision/concept-iri
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}
 ;; ADR-090 seam directly on the provision — the *jurisdiction-specific*
 ;; IRI, distinct from :tax-concept/concept-iri which is the
 ;; cross-jurisdiction one. Lets DE GAAP/FIBO/CGI URIs all attach.

 ;; --- The computational shape (see §2.4) ---
 {:db/ident       :provision/shape
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; Closed enum: :scope-rule | :schedule-rule | :adjustment-rule
 ;;            | :compute-fn-rule. Dispatch hint for the evaluator.

 {:db/ident       :provision/scope-rule
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one
  :db/isComponent true}
 ;; When :shape = :scope-rule: the base-selector spec
 ;; (the marginalize axis + filter; see §2.3).

 {:db/ident       :provision/schedule
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one
  :db/isComponent true}
 ;; When :shape = :schedule-rule: a kontor.tax-schedule config
 ;; (a :flat / :progressive-bracket / :capped / :elect / :sum tree,
 ;; whose leaves point at :parameter entities for the actual numbers).

 {:db/ident       :provision/adjustment
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one
  :db/isComponent true}
 ;; When :shape = :adjustment-rule: an adjustment-item config
 ;; (note 105 §1 :code/:op/:refundable?/:amount), with :amount possibly
 ;; pointing at a parameter or a compute-fn.

 {:db/ident       :provision/compute-fn
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; When :shape = :compute-fn-rule: the registered fn keyword (the
 ;; escape hatch per note 116 §D2). Resolved by the l10n module's
 ;; compute-fn registry. Reserved for genuinely irregular shapes.

 {:db/ident       :provision/priority
  :db/valueType   :db.type/long
  :db/cardinality :db.cardinality/one}
 ;; Catala-style ordered priority. Within a regime, provisions at
 ;; the same priority either compose (additive surtaxes/credits) or
 ;; conflict (raise kontor.tax/ambiguous). Higher priority = applies
 ;; first / wins as exception (see :provision/exception-of).

 {:db/ident       :provision/exception-of
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many}
 ;; If this provision is an exception to one or more others, the
 ;; evaluator skips the others when this one applies. Empty for
 ;; "ordinary" provisions.

 {:db/ident       :provision/conditions
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true}
 ;; Component refs to {:condition/code :ca-pme-status,
 ;;                    :condition/predicate {:tag :and :clauses [...]}}.
 ;; A data-shaped predicate over :tax-context (the
 ;; (entity, period, return) tuple). Closed predicate vocabulary so
 ;; the evaluator can interpret without compute-fn.

 {:db/ident       :provision/effective-from
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}
 ;; Authority-published start of applicability. Note: kontor's
 ;; :tx/valid-from (ADR-048) handles "the day we entered this
 ;; provision into our books"; :provision/effective-from is the
 ;; SEMANTIC date the law says the provision applies from. They are
 ;; different concerns; both are needed.

 {:db/ident       :provision/effective-to
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}
 ;; Nil = open-ended.
 ]
```

### 2.3 `:regime` — a group of provisions composing one computation

```clojure
[{:db/ident       :regime/code
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}
 ;; e.g. :fr.is, :de.kstg, :us.irc-1031, :ca.t1.federal.
 ;; A regime is the composable unit a PeriodTaxProvider consumes.

 {:db/ident       :regime/label
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}

 {:db/ident       :regime/jurisdiction
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}

 {:db/ident       :regime/kind
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; The period-tax-kinds enum (ADR-099): :income / :corporate-income /
 ;; :capital-gains / :standalone-payroll / :property / :wealth /
 ;; :remittance / :return. Drives which provider consumes it.

 {:db/ident       :regime/extends
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one}
 ;; The OpenFisca-Reform analog. A regime can extend a baseline regime:
 ;; provisions resolved against this regime first look here, then fall
 ;; back to :regime/extends's provisions. Lets us model "the regime as
 ;; it stands" (no :extends), "the regime as it WOULD HAVE been under
 ;; proposed amendment X" (:extends with overriding provisions), and
 ;; "the regime as it might be next year" (a draft :extends used for
 ;; planning).

 {:db/ident       :regime/effective-from
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}

 {:db/ident       :regime/effective-to
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}

 {:db/ident       :regime/provider-id
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; The registered PeriodTaxProvider that evaluates this regime,
 ;; per the kontor.tax-rate-provider provider-registry pattern.
 ]
```

### 2.4 `:parameter` — the value (with its history) referenced by a schedule

```clojure
[{:db/ident       :parameter/code
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}
 ;; A fully-qualified keyword: :fr.is/taux-normal,
 ;; :fr.is/seuil-superieur-benefices-taux-reduit,
 ;; :de.kstg/koerperschaftsteuersatz, :us.irc-1031/like-kind-window-days.
 ;; The keyword namespace is the path; the name is the leaf.

 {:db/ident       :parameter/label
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}

 {:db/ident       :parameter/unit
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}
 ;; Closed enum: :rate (a /1 fraction) | :money (a BigDecimal in some
 ;; commodity) | :count | :days | :years | :iso-date.
 ;; Inspired by OpenFisca's metadata.unit (parameter.py:36) but
 ;; closed-vocabulary instead of free-text.

 {:db/ident       :parameter/commodity
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one}
 ;; Required when :unit = :money; nil otherwise. Ties into kontor.money.

 {:db/ident       :parameter/values
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true}
 ;; Component refs to :parameter-value entities (next). The temporal
 ;; series, equivalent to OpenFisca's values_list.

 {:db/ident       :parameter/concept-iri
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}
 ;; ADR-090 seam. e.g. an IPP-CSV id, an XBRL concept, an FRBR
 ;; expression URI.

 ;; --- :parameter-value (a component entity) ---
 {:db/ident       :parameter-value/from
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}
 ;; First instant at which this value applies — the "instant_str" key
 ;; in OpenFisca's values_list (parameter.py:111).

 {:db/ident       :parameter-value/value
  :db/valueType   :db.type/bigdec
  :db/cardinality :db.cardinality/one}
 ;; BigDecimal per kontor money discipline. A nil :value (modelled
 ;; here by an explicit :parameter-value/null? boolean) is OpenFisca's
 ;; "parameter ends at this date" sentinel (parameter.py:142-160).

 {:db/ident       :parameter-value/null?
  :db/valueType   :db.type/boolean
  :db/cardinality :db.cardinality/one}

 {:db/ident       :parameter-value/citation
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one}
 ;; Optional pointer at a :citation entity carrying the specific
 ;; statute/amendment that introduced THIS value. Per-value citation
 ;; was OpenFisca's reference map keyed by date
 ;; (the taux_normal.yaml file shows this clearly:
 ;; metadata.reference."2022-01-01" carries the Loi 2017-1837 art. 84
 ;; citation). kontor lifts the per-date citation up to first-class.

 ;; --- :parameter-bracket (for ladder parameters) ---
 ;; A bracket scale is one :parameter entity with :unit = :bracket-ladder
 ;; (additional enum value) and :parameter/brackets pointing at an
 ;; ordered collection of :parameter-bracket components, each itself
 ;; holding a :parameter-bracket/threshold (:parameter ref) and a
 ;; :parameter-bracket/rate (:parameter ref). Both threshold and rate
 ;; are themselves :parameter entities with their own date-keyed
 ;; histories — that lets a single bracket's threshold and rate evolve
 ;; independently, mirroring the bareme.yaml shape.
 ]
```

### 2.5 Why this collapses the parameter-vs-formula split

OpenFisca's split is *parameter-data + formula-code*. kontor collapses
to *parameter-data + schedule-data + (occasional) compute-fn-escape*:

- **Flat rate × base**: `:shape :schedule-rule` + `{:base :flat :rate
  <param-ref>}`. No formula body anywhere.
- **Progressive-bracket income tax**: `:shape :schedule-rule` +
  `{:base :progressive-bracket :brackets <ladder-ref>}`; existing
  `apply-schedule` runs it. No per-provision Clojure.
- **Income-banded refundable credit** (IN §87A, US CTC, FR décote, CA
  BPA phase-out): `:shape :adjustment-rule` + an item
  `{:op :credit :refundable? true :amount <pattern>}` where `<pattern>`
  is a small closed-vocabulary spec (`:flat-amount`/`:phase-out`/
  `:phase-in`/`:band-stepped`, ~dozen tags). The evaluator interprets;
  outliers fall back to `:compute-fn`.
- **Genuinely irregular** (a notch; multi-input non-monotone election):
  `:shape :compute-fn-rule` is the only place per-provision Clojure
  appears. Bounded; each `:compute-fn-rule` is a candidate for later
  promotion to a closed pattern.

The invariant: **a generic evaluator interprets data-shaped provisions;
per-provision Clojure is a documented exception, not the norm.**

### 2.6 Composition with the existing adjustment layer (note 105)

`kontor.tax-schedule/apply-adjustments` is unchanged. A
`:provision/adjustment` is just a data shape the evaluator *converts*
into an adjustment-item before calling `apply-adjustments`. The
base-aware-fn escape from note 105 (an `:amount` Clojure closure)
becomes the closed `:phase-out`/`:phase-in` patterns above —
expressed as data, not as a function value. Existing `*_fit.clj`
tests (notes 108-115) keep working with a thin wrapper around the
new evaluator.

### 2.7 Composition with ADR-090 `:concept-iri`

Three IRI slots, deliberately distinct:

- `:tax-concept/concept-iri` — cross-jurisdiction (XBRL, FIBO,
  kontor-published taxonomy).
- `:provision/concept-iri` — jurisdiction-specific (CGI URI, authority
  statute reference).
- `:parameter/concept-iri` — published-rate identifier (IPP-CSV id,
  BIS-published rate stream).

ADR-090's seam composes naturally; each layer carries its own
authority's identifier.

---

## §3. Worked translation: FR IS PME reduced 15% bracket

The French corporate income tax (impôt sur les sociétés, IS) has a
reduced-rate PME provision: for companies with revenue ≤ €10M, the first
€42 500 of profit is taxed at 15% instead of the standard 25%. This is
encoded across four YAML files in
`/home/christian-weilbach/Development/openfisca-france/openfisca_france/parameters/taxation_societes/impot_societe/`
(`taux_normal.yaml`, `taux_reduit.yaml`, `plafond_ca_taux_reduit.yaml`,
`seuil_superieur_benefices_taux_reduit.yaml`) plus an `index.yaml` that
declares the parent node and ordering.

In kontor's candidate schema, the same provision becomes (abbreviated;
omitting `:label`, intermediate-historical-values, citation bodies and
the regime/concept entities that surround them — those follow the §2
shapes mechanically):

```clojure
;; Parameters — each replaces one OpenFisca YAML file.
{:parameter/code      :fr.is/taux-normal
 :parameter/unit      :rate
 :parameter/values    [{:parameter-value/from  #inst "2022-01-01T00:00:00Z"
                        :parameter-value/value 0.25M
                        :parameter-value/citation
                        {:citation/lang :fr
                         :citation/text "Article 219 CGI ; Loi 2017-1837 art. 84"
                         :citation/url  "https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000042908577"}}
                       ;; earlier values reverse-chronological, mirroring
                       ;; OpenFisca's values_list
                       ]}
{:parameter/code  :fr.is/taux-reduit
 :parameter/unit  :rate
 :parameter/values [{:parameter-value/from  #inst "2002-01-01T00:00:00Z"
                     :parameter-value/value 0.15M}
                    {:parameter-value/from  #inst "2001-01-01T00:00:00Z"
                     :parameter-value/value 0.25M}]}
{:parameter/code     :fr.is/seuil-pme
 :parameter/unit     :money
 :parameter/commodity :commodity/eur
 :parameter/values   [{:parameter-value/from  #inst "2022-01-01T00:00:00Z"
                       :parameter-value/value 42500M}
                      ;; earlier values...
                      ]}
{:parameter/code     :fr.is/plafond-ca-pme
 :parameter/unit     :money
 :parameter/commodity :commodity/eur
 :parameter/values   [...10M-modern, then earlier history...]}

;; The two provisions that together encode the PME reduced-rate rule.
{:provision/code        :fr.cgi/219-i-baseline
 :provision/jurisdiction :fr
 :provision/regime      :fr.is
 :provision/concepts    [:tax-concept/flat-rate]
 :provision/statute     "CGI Article 219, I, alinéa 1"
 :provision/shape       :schedule-rule
 :provision/schedule    {:base :flat
                         :rate {:parameter-ref :fr.is/taux-normal}}
 :provision/priority    100
 :provision/effective-from #inst "1948-01-01T00:00:00Z"}

{:provision/code        :fr.cgi/219-i-b-pme
 :provision/jurisdiction :fr
 :provision/regime      :fr.is
 :provision/concepts    [:tax-concept/preferential-rate-on-band
                         :tax-concept/conditional-eligibility]
 :provision/statute     "CGI Article 219, I-2°)b"
 :provision/shape       :schedule-rule
 ;; Two-bracket progressive ladder: 15% on the first €42 500, 25% above.
 ;; Encoded as data; kontor.tax-schedule/apply-schedule runs it.
 :provision/schedule
 {:base :progressive-bracket
  :brackets [{:rate  {:parameter-ref :fr.is/taux-reduit}
              :upper {:parameter-ref :fr.is/seuil-pme}}
             {:rate  {:parameter-ref :fr.is/taux-normal}
              :upper nil}]}
 :provision/conditions  ; closed-vocabulary predicate over :tax-context
 [{:condition/code :fr.is/pme-revenue-ceiling
   :condition/predicate
   {:tag :leq :left {:fact :gross-revenue}
              :right {:parameter-ref :fr.is/plafond-ca-pme}}}
  ;; further CGI 219-I-2°)b clauses (capital structure, etc.) as
  ;; additional :condition entries...
  ]
 :provision/exception-of [{:provision/code :fr.cgi/219-i-baseline}]
 :provision/priority    200
 :provision/effective-from #inst "2002-01-01T00:00:00Z"}
```

The evaluator (`kontor-l10n-fr/cit-provider`, a `PeriodTaxProvider`)
runs this for a taxpayer × year by: collecting all `:fr.is` provisions
active in the period sorted by `:provision/priority` desc; checking
each `:provision/conditions` against the tax-context; the first
applicable provision wins (its `:exception-of` chain prunes baselines,
Catala-style); resolving parameter-refs at the period (the datahike-
native analog of OpenFisca's `_get_at_instant`); calling
`apply-schedule` on the resolved schedule; wrapping in
`TaxReturnFacts`. Existing machinery, unchanged.

Per-provision Clojure code: zero. A 2024 amendment (the seuil moves to
€50 000) is a one-line addition to `:fr.is/seuil-pme`'s
`:parameter/values`. This is what note 116 §A1-A4 asked for:
cross-jurisdiction concept catalog (`:tax-concept/preferential-rate-on-band`
also fits DE Mittelstandsregelung, JP small-company carve-outs);
first-class temporal versioning; prioritised conditional definitions;
provenance trace from `:liability` back through `:provision` and
`:parameter-value/citation` to legifrance.gouv.

---

## §4. What was good (worth adopting)

**G1. The parameter tree as a sorted-by-date map.** `values_list` +
`_get_at_instant` (`parameter.py:77-111`, `213-217`) is the strongest
operational pattern in the survey. Cheap to evaluate, easy to author,
audit-friendly. `:parameter` + `:parameter-value` reproduce it
datahike-natively.

**G2. Per-value (not per-parameter) provenance.** OpenFisca's
`metadata.reference."2022-01-01": {title:..., href:...}` pattern
(`taux_normal.yaml:25-65`) lifts to `:parameter-value/citation`.
Each value carries its specific authoring authority.

**G3. Bracket scales with per-field independent histories.** Each
bracket's `threshold` and `rate` are independently date-keyed in
`bareme.yaml:9-100` (`parameter_scale.py:83-121`). Reflects how
legislation actually evolves; `:parameter-bracket` preserves the shape.

**G4. Reform-as-overlay.** `Reform.modify_parameters` deep-copies the
baseline tree and applies a modifier (`reform.py:68-87`). kontor's
`:regime/extends` captures the same shape; useful for counterfactual
"what would happen under proposed amendment X" planning.

**G5. Closed period enum.** `DateUnit` (`date_unit.py:97-107`) is small
and complete. kontor (ADR-014) already follows the discipline.

**G6. Per-jurisdiction packaging.** `openfisca-france` as one PyPI
artefact maps cleanly onto `kontor-l10n-*` (ADR-072). Each l10n module
ships its own `:tax-concept` / `:provision` / `:regime` / `:parameter`
data plus its `:compute-fn` registry. Shared concepts live in the kernel.

---

## §5. What was bad (worth rejecting)

**B1. Per-variable Python formulas — the C1 anti-pattern.** Every
`Variable` is a small Python function reading parameters and calling
other variables. `credits_impot.formula_2021_01_01` (`credits_impot.py:11-79`)
hand-codes refundable/non-refundable classification, plafond min/max,
NumPy arithmetic — no "credit-fold against a plafond" abstraction. Every
credit-formula reinvents it. The candidate schema makes the *schedule
+ adjustment algebra* (closed `:flat`/`:progressive-bracket`/`:capped`/
`:elect`/`:sum`/adjustment-item vocabulary) the analog of formula,
evaluated once by the kernel.

**B2. Formula dispatch by date alone.** `formula_2018_01_01` says
*when* but not *why* (`variable.py:337-369`). kontor pairs
`:provision/effective-from` with `:provision/statute` + `:provision/citations`;
each computational shape change is itself a statute reference.

**B3. AGPL contamination.** Both repos AGPL-3.0; code reuse impossible
for kontor's EPL-1.0 + commercial-friendly posture. Concepts and
schema-shape borrowable; not a single line of Python or YAML lifted.
File:line citations here are evidence-of-concept, not license laundering.

**B4. Microsimulation vectorization.** `numpy.array(...)` arithmetic
over arrays of households (`variable.py:437-474`) buys nothing for
kontor's N=1 use case. `apply-schedule` returns a BigDecimal per call.

**B5. Variable-as-class boilerplate.** ~15 class attributes per variable
(`variable.py:99-191`). A kontor "variable" is just a marginalize-spec
(`kontor.report` per ADR-096) feeding a schedule; no parallel ontology
needed.

**B6. Multi-tier entity model.** `Individu`/`Famille`/`FoyerFiscal`/
`Menage` (`group_entity.py:14-122`) is needed for welfare-law multi-
person unitization. kontor stays at one entity per legal person
(ADR-031); borrow the *first-class scope metadata* concept (already
in `marginalize`), skip the GroupEntity/Role machinery.

**B7. YAML as authoring format.** EDN is strictly better (homoiconic,
comments, instant literals, no whitespace traps).

**B8. No cross-jurisdiction concept catalog.** Each OpenFisca country
package reinvents `basic_income`, `tax_rate`, etc. Notes 108-115
surfaced the same gap for kontor; `:tax-concept` fills it.

---

## §6. Open questions for the maintainer

**Q1. Closed vs open `:condition/predicate` vocabulary?** §2.2 assumes
closed (`:leq`/`:geq`/`:eq`/`:and`/`:or`/`:not`/`:status-is` plus
small handful). Closed-with-escape-hatch (same pattern as
`:provision/shape`) is probably right; which predicates belong inside
needs a scan of notes 108-115's actual eligibility clauses.

**Q2. Bracket parameter shape — inline values vs per-field
`:parameter` refs?** §2.4 picks per-field refs (most expressive, matches
OpenFisca's per-bracket-field date keys). Inline values would be
simpler. Defer until a real `kontor-l10n-fr` encoding pressures the
choice.

**Q3. Where does the `:compute-fn` registry live?** Per-module: each
`kontor-l10n-*` registers its escape-hatch fns at load time via
multimethod or atom. A cross-module registry is overkill.

**Q4. Cascading vs flat `:regime/extends`?** OpenFisca reforms are
flat. Cascading would let multi-step legislative simulation
("the 2027 reform of the 2026 reform of current law"). Probably worth
it, with cycle detection raising `kontor.tax/cyclic-regime`.

**Q5. Typed `:tax-fact` entities vs free-form `{:fact ...}` lookups in
`:condition/predicate`?** Free-form initially; promote to typed-facts
when ≥3 jurisdictions reuse the same fact-code.

**Q6. Ingestion from authority-published XBRL/AKN taxonomies into
`:tax-concept`/`:provision` skeletons?** Schema is shaped to allow
this (every entity carries `:concept-iri`); defer the importer until
the first authority publishes a usable taxonomy.

**Q7. Static `:tax-context` schema per provision (what facts it
requires)?** OpenFisca chains forward via DAG resolution
(`simulation.py:121-169`). For kontor, a static "this provision needs
:gross-revenue and :employee-count" attribute lets validation surface
missing facts up front. Defer until provision counts demand it.

**Q8. Lift `*_fit.clj` tests into pure-EDN golden cases consumed by a
generic runner?** OpenFisca's YAML test cases prove the pattern; the
existing `*_fit.clj` files (notes 108-115) are already close. Do the
lift in the same ADR as the schema, so it's part of iteration 1.

**Q9. Non-scalar parameter values (strings, dates, booleans)?**
OpenFisca's `ALLOWED_PARAM_TYPES` includes non-numerics. Add
`:parameter-value/string-value` / `:parameter-value/instant-value` /
`:parameter-value/boolean-value` as alternate cardinality-one
attributes, selected by `:parameter/unit`.

**Q10. `:provision/effective-from` vs `:tx/valid-from`?** Two
semantics: `:tx/valid-from` (ADR-048) is *when we entered the
provision into our books*; `:provision/effective-from` is *the
statutory date the law applies from*. Both needed; document the
distinction in the ADR.

---

## Deliverable

- **Filename**: `/home/christian-weilbach/Development/kontor/doc/research/118-openfisca-schema-derivation.md`
- **Word count**: ≈4 100 words (target band 2 500-4 000; landed at the
  upper edge — the schema-attribute listing in §2 is the load-bearing
  bulk and the brief asked for 15-30 attrs spelled out)

---

## If I had another day on this

I would dig deeper into three things this note had to skim:

1. **PolicyEngine-core's citation discipline**, mentioned in note 116
   §A4 as superior to OpenFisca's. The PolicyEngine fork specifically
   improves the citation-back-to-statute story; comparing its
   `MetadataEntry` shape against my candidate `:citation` design would
   tighten the `:provision/citations` design call.
2. **OpenFisca's `SimulationBuilder` and the `Holder` cache**
   (`simulations/simulation_builder.py` plus `holders/holder.py`). The
   memoised DAG-walk is interesting if kontor ever needs cross-provision
   dependency resolution (e.g. one provision's output feeding another's
   base — note 105 Frontier 3, the tax-graph). I'd document what
   minimal subset of the DAG-walk machinery kontor would need when
   Frontier 3 lands, vs what it can skip.
3. **The full openfisca-france `taxation_societes` model + parameter
   tree** (not just the rate files I read). The reality check of "how
   complex does a full IS encoding actually get" would either validate
   the candidate schema (if 80% of provisions fit `:schedule-rule` +
   `:adjustment-rule`) or expose `:compute-fn-rule`'s
   true incidence rate. The shape of the schema does not change either
   way — only the maintainer's confidence in "this evaluator can really
   carry the load" does.
