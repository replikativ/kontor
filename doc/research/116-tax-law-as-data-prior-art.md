---
date: 2026-05-24
title: "Tax-law-as-data prior art: a survey for the kontor maintainer"
audience: maintainer
status: prior-art survey — recommend whether to clone any of these for deeper code-level reading next
---

# Tax-law-as-data prior art: a survey for the kontor maintainer

## Why this note exists

Phase 3 of the tax-completion program (note 107, with notes 108-115 just landed)
exposed the same cross-jurisdiction concepts again and again: rollover relief
(DE §6b, FR Article 41, JP §51, US §1031/1033, CA s.44/s.85), participation
exemption (DE §8b KStG, FR régime mère-fille, JP juyō-kabushiki kōjo), lifetime
caps on preferential treatment (UK BADR £1m, DE §16 IV Freibetrag, JP retirement
deduction), loss-bucket compartmentalization (US §1211 capital-loss limit, DE
gesonderter Verlustverrechnungskreis, JP shotoku-betsu loss netting),
holding-period preferential rates (US LTCG, JP bunri kazei, DE Teilfreistellung).

Independent agents working on independent jurisdictions kept arriving at the
same abstractions. That is a strong signal — either the kernel's tax engine
should lift these into first-class data (`:tax-concept`, `:provision`, `:regime`
as datahike entities) before we hardcode four more statutes, or we have to
defend a deliberate choice to keep grinding out per-jurisdiction `defrecord`s.

This note surveys the prior art so the maintainer can see what functional and
non-functional requirements the field has already worked out. **It is not a
recipe to copy.** kontor will model things differently — but the survey tells
us which dimensions of the design space prior art has already explored, what
problems it found there, and what we would inherit if we cloned a particular
shape.

No code is written here. No repositories are cloned. The recommendation at the
bottom names which 1-3 projects merit deeper code-level reading next.

---

## Part 1 — Per-project surveys

### 1. Catala (catalalang.github.io / CatalaLang on GitHub, Apache 2.0)

**What it is.** A domain-specific *programming language* for transcribing
legislative texts into executable code. The central abstraction is the
**scope** — a unit of computation that mirrors a section of statute, containing
typed variables whose values can be **defined under conditions** with explicit
**default logic** and **exceptions**. Catala compiles a scope language to a
default calculus, then to an exception-aware language, with built-in OCaml and
Python backends ([Catala docs](http://book.catala-lang.org/en/index.html);
[arXiv 2103.03198](https://arxiv.org/pdf/2103.03198)).

**Scope.** Originally French income tax (M language replacement at DGFIP) and
French family benefits (CNAF). Also evaluated on §121 of US federal income tax
(home-sale gain exclusion) ([Inria CATALA story](https://www.inria.fr/en/catala-software-dgfip-cnaf)).

**Production status.** Two government POCs in progress. Since June 2023, French
income tax is being rewritten in Catala from the legal texts (not from the
M source); the projected horizon for "official system" is 5-6 years. CNAF
benefits rewrite is in flight. **Listed as "awesome" by the French digital
gov't OSS office December 2023.** Compiler is pre-1.0 ("1.0.0 in testing"
per release notes). Active GitHub org, 16 repositories, regular commits
through mid-2025 ([Catala GitHub](https://github.com/CatalaLang/catala);
[Northwestern news](https://news.law.northwestern.edu/news/sarah-lawsky-worked-on-a-tax-law-code-that-the-french-government-deemed-officially-awesome/)).

**Architecture summary.** A scope `S` declares structured types and variables;
each variable can have multiple **definitions** guarded by **conditions**
(`under condition X consequence Y`). The semantics is Sarah Lawsky's
"definition-under-conditions" formalization. Compilation desugars to a **default
calculus** where each scope variable resolves to either (a) the unique
applicable exception, (b) a conflict-error if more than one exception applies,
or (c) a default consequence ([Bob Atkey's semantics post](https://bentnib.org/posts/2023-01-16-catala.html);
[Catala book §5.4](https://book.catala-lang.org/en/5-4-definitions-exceptions.html)).
Scopes call other scopes; `context` variables let an outer scope override an
inner scope's definitions. Versions-over-time are *not* a first-class language
feature — they live in literate-programming structure (separate `.catala_en`
files per statutory version) and in the `under condition date in [..]`
guards.

**Strengths.** (a) The default-logic primitive matches how lawyers think —
"the rule is X, *except* when Y, *unless* Z" decomposes into prioritised
exceptions instead of nested `if`. (b) Literate-programming format intermixes
the legal prose with the code, giving lawyers a reviewable artifact and the
compiler proof of coverage. (c) The ICFP paper found a bug in CNAF's existing
implementation just by encoding the statute carefully. (d) Government uptake
proves the model scales past toy problems.

**Weaknesses.** (a) Each statute is hand-written by a programmer-lawyer pair;
authoring is not democratised. (b) Pre-1.0 compiler, OCaml-heavy toolchain.
(c) "Law as it stood" is solved by file-level versioning + date conditions, not
a first-class temporal data model. (d) No notion of cross-jurisdiction
abstraction — every statute is its own scope tree. (e) Scopes are procedural in
the sense that a *recompile* is required to ship a change; you cannot edit law
without redeploying the compiler output.

**License + JVM compatibility.** Apache 2.0 (compatible with EPL-1.0 for
inspiration; mixing source isn't relevant since we wouldn't import the
compiler). Compiler is OCaml; not JVM-runnable. We would borrow concepts,
not artifacts.

**Adaptability to kontor.** The default-logic shape *fits* what we are seeing
in CIT/CGT — "the standard rate applies, except for §X case, unless the §Y
super-exception." A Clojure data representation of "definition-under-conditions"
is straightforward (`{:concept ... :base ... :conditions [...] :consequence ...
:exception-of <ref>}`); the resolver is a small priority-aware evaluator. We
would not adopt Catala's surface syntax. The literate-programming discipline
(legal-text + code side-by-side) is something kontor's per-jurisdiction
files already lean toward in spirit — making it first-class would strengthen
the "law-as-it-stood" audit story.

---

### 2. OpenFisca (openfisca.org, AGPL-3.0)

**What it is.** A Python *framework* for modelling tax-and-benefit systems
as data. The central abstraction is the **TaxBenefitSystem** holding two
collections: **Parameters** (global values that change over time, stored
in YAML directory trees) and **Variables** (Python classes with `formula()`
methods that compute values for an `Entity` over a `Period`) ([OpenFisca
architecture](https://openfisca.org/doc/architecture.html);
[Variables docs](https://openfisca.org/doc/openfisca-python-api/variables.html)).

**Scope.** 30+ country packages: France, Tunisia, Senegal, Côte d'Ivoire,
Morocco, Spain (Barcelona), New Zealand, etc. Per-jurisdiction (`openfisca-france`)
and sub-jurisdiction (`openfisca-paris`) packages compose. Tax + benefits +
welfare ([country-template repo](https://github.com/openfisca/country-template)).

**Production status.** Mature (10+ years). Used by IDB (Inter-American
Development Bank), national governments, NGOs, think tanks. Active core
development; PolicyEngine forked it (see project 8). Per-country activity
varies enormously — France is heavily maintained, others are skeleton-only.

**Architecture summary.** A `Variable` is a Python class with class attributes
(`value_type`, `entity`, `definition_period`, optional `default_value`) and
one or more `formula(entity, period, parameters)` methods. The method name
**encodes when this formula is active**: `formula_2014_01` means "use this
formula from Jan 2014 onward." Earlier methods cover earlier periods
([Legislation evolutions docs](https://openfisca.org/doc/coding-the-legislation/40_legislation_evolutions.html)).
**Parameters** live as YAML files in a directory tree
(`parameters/tax_on_salary/rate.yml`), with `values:` keyed by date:
`"2015-01-01": {value: 0.25, metadata: {reference: "Loi 2014-X"}}`. A parameter
ends at a date by assigning `null`. Reforms are programmatic overrides
that fork a TaxBenefitSystem. Tests are YAML golden cases.

**Strengths.** (a) Versioning-over-time is first-class for parameters
(YAML date keys) and for variable formulas (method-name suffix). (b) YAML
parameter trees can be edited by domain experts without Python expertise.
(c) Reform overlays are a clean composition model for "what if the rate
changed?" simulations. (d) Per-jurisdiction packaging matches the kontor-l10n
artifact split. (e) Entities (`Person`, `Household`, `Family`) are first-class
and orthogonal to variables.

**Weaknesses.** (a) Python imperative formulas — the law-as-data claim only
goes as far as the parameter trees; the *logic* is still procedural code.
(b) AGPL-3.0 is contagious for any consumer (problematic for kontor's
EPL-1.0 + commercial-friendly posture). (c) NumPy-vectorized formulas
optimize for microsimulation (many fictitious households) at the cost of
GL-natural single-entity evaluation. (d) The `formula_YYYY_MM` naming is
clever but doesn't represent the *reason* a formula changed (which statute,
which amendment) — only the *date*. (e) No notion of cross-country concept
catalog; each country reinvents `basic_income`, `tax_rate`, etc.

**License + JVM compatibility.** AGPL-3.0 — **disqualifying for code reuse**.
Concepts (parameter tree, date-keyed values, reforms-as-overlays, entities-as-types)
can be borrowed without touching code.

**Adaptability to kontor.** The parameter-tree + date-keyed-values pattern
maps directly onto datahike (a `:tax-parameter` entity with `:tax-parameter/effective-from`
and `:tax-parameter/effective-to`, queried bitemporally via kontor's existing
`:as-of-valid`). The Variable-as-class pattern is closer to kontor's current
`PeriodTaxProvider` record — but OpenFisca's discipline of separating
*parameters* (data) from *formulas* (logic) is sharper than ours and worth
adopting. Reforms-as-overlays could be a useful primitive for stress-testing
tax-law changes.

---

### 3. LegalRuleML (OASIS standard, finalised 2021)

**What it is.** An XML-schema *standard* for representing legal rules,
extending RuleML with legal-specific constructs (deontic operators —
obligation/permission/prohibition; defeasibility; jurisdiction metadata;
provenance; temporal qualifications). Not an engine — a wire format
([OASIS LegalRuleML v1.0](https://www.oasis-open.org/standard/legalruleml-core-specification-version-1-0-oasis-standard/);
[GitHub legalruleml](https://github.com/oasis-tcs/legalruleml)).

**Scope.** General-purpose legal rules (not tax-specific). Designed to be
domain-neutral.

**Production status.** Academic / standards-body. Few production deployments;
mostly research projects citing it. Compare with Akoma Ntoso (the document
side), which has government uptake in Brazil, EU, Africa; LegalRuleML's
rule-side adoption is much thinner.

**Architecture summary.** Each rule is XML with deontic markup, an antecedent,
a consequent, and multiple annotation blocks (provenance, jurisdiction, time
qualification, defeater relationships). Multiple semantic interpretations of
the same rule live in separate annotation blocks (acknowledging that lawyers
disagree about meaning). Rules link to source documents via Akoma Ntoso IRIs.

**Strengths.** (a) First-class deontic operators are honest about the legal
nature of obligations (something tax engines usually wash away). (b) Provenance
+ jurisdiction + time as first-class metadata. (c) Acknowledges that
*interpretation* is plural — multiple readings of the same provision can
co-exist.

**Weaknesses.** (a) XML-heavy; verbose authoring. (b) No standard reasoning
engine — the spec is data-only. (c) Adoption is thin outside academia.
(d) The defeasibility model is academically rigorous but hard to compute
efficiently at scale.

**License + JVM compatibility.** The spec is open; implementations exist
in various languages. Not directly JVM-tied.

**Adaptability to kontor.** The *metadata model* (provenance, jurisdiction,
time-qualification, multiple-interpretations) is exactly what we would want
on a `:provision` entity. The XML serialization is wrong for us. The
deontic-operator framing maps poorly onto tax computation (taxes are
arithmetic obligations, not deontic ones in the LegalRuleML sense). Borrow
the metadata shape; ignore the XML.

---

### 4. Akoma Ntoso (OASIS LegalDocML, standardised 2018)

**What it is.** An XML *vocabulary* for legislative, parliamentary, judicial,
and soft-law documents. Sister standard to LegalRuleML (Akoma Ntoso is the
document; LegalRuleML is the rules atop the document) ([Akoma Ntoso v1.0
spec](https://docs.oasis-open.org/legaldocml/akn-core/v1.0/cs01/part1-vocabulary/akn-core-v1.0-cs01-part1-vocabulary.html);
[Wikipedia](https://en.wikipedia.org/wiki/Akoma_Ntoso)).

**Scope.** Any legal document. Production deployments: Brazil legislative
portal, EU's EUR-Lex, UN, several African parliaments, Italian legislative
portal.

**Production status.** Mature standard with real government adoption. UK
National Archives, Brazil, EU.

**Architecture summary.** Document is structured by FRBR layers:
**Work** (the abstract legislative act — "Act 3 of 2005"), **Expression**
(a specific language/version — "Act 3 of 2005 as amended on 2006-07-03"),
**Manifestation** (a specific file format — "PDF of that expression"),
**Item** (a specific copy of the file). Each level has a canonical URI
([Akoma Ntoso naming convention](https://docs.oasis-open.org/legaldocml/akn-nc/v1.0/akn-nc-v1.0.html)).
Sections/articles/subsections nest naturally in the XML.

**Strengths.** (a) **FRBR layering is the canonical model for "law-as-it-stood"** —
the Expression layer captures "law on date D in language L." (b) Each Work
has a stable IRI; cross-jurisdiction references can resolve. (c) Real-world
adoption proves the model handles the messy reality of amendments,
consolidations, repeals.

**Weaknesses.** (a) Document-side only — says nothing about what the rules
*mean*. (b) XML serialization is overkill for a Clojure shop. (c) FRBR
layering is conceptually clean but operationally heavy.

**License + JVM compatibility.** Open standard. Implementations exist
in various languages.

**Adaptability to kontor.** **The FRBR layering is the single most valuable
concept in this entire survey for kontor's law-as-it-stood story.** A `:statute`
entity could decompose into `:statute/work` + `:statute/expression` (the
period-valid version) + a stable `:statute/concept-iri` (extending ADR-090).
Bitemporal kontor already has `:tx/valid-from`; the Expression-layer notion
fits naturally on top.

---

### 5. L4 / SMU Centre for Computational Law (Apache 2.0)

**What it is.** An open-source functional specification language with
controlled-natural-language (CNL) syntax for laws and contracts. Compiles
to multiple targets (logic programming, natural-language explanations,
visual diagrams). Defeasible-semantics design ([SMU CCLaw GitHub](https://github.com/smucclaw);
[Defeasible semantics for L4](https://ink.library.smu.edu.sg/cclaw/5/)).

**Scope.** Multi-domain (contracts, statutes, regulations); Singapore
contract law has had the most attention.

**Production status.** Active research, S$15M NRF grant from Singapore.
Prototype-stage. **No production deployments at the scale of Catala-CNAF
or OpenFisca-France.** Several papers, a Docassemble integration, a
type-checker, an NLG pipeline.

**Architecture summary.** Specifications in L4 are translated to a backend
that uses defeasible logic (a rule can be defeated by a more-specific rule).
NLG layer generates natural-language verbalizations of the rules so
non-programmer reviewers can audit. The DSL is closer to a logic-programming
sublanguage than to Catala's literate-programming style.

**Strengths.** (a) Defeasible logic is a clean formal model for "general
rule + exceptions" (similar terrain as Catala's default logic, different
formalism). (b) NLG pipeline addresses the "lawyer review" problem
explicitly. (c) Multi-target compilation (logic, NLG, visualizations) is
ambitious.

**Weaknesses.** (a) Less mature than Catala. (b) Surface CNL syntax has
the usual CNL trade-offs (rigid grammar, hard to author). (c) No
production stamp — Singapore has been publishing about it for years
without an obvious "this is now the official tax computer" announcement.
(d) Smaller ecosystem.

**License + JVM compatibility.** Open source; toolchain mixed.

**Adaptability to kontor.** The *concept* of defeasible logic is the same
terrain Catala covers and that kontor's adjustment-layer (note 105)
already models in a small way. We do not need to adopt L4 specifically;
Catala has the production proof points.

---

### 6. Drools / KIE rules engine (Apache 2.0)

**What it is.** A general-purpose business-rules engine (BRMS) for Java.
The central abstraction is the **rule** (LHS pattern matches against
a working-memory fact base; RHS asserts or modifies facts). Decision
tables, complex event processing, BPMN integration are layered on top
([Drools rule engine docs](https://docs.drools.org/latest/drools-docs/drools/rule-engine/index.html)).

**Scope.** General-purpose. Used in financial-services compliance, insurance
underwriting, healthcare, and some tax-compliance contexts (especially for
indirect-tax routing — figuring out which tax code applies given product
category × jurisdiction × customer type).

**Production status.** Mature, JBoss/RedHat-backed. Real deployments
across many industries. Specifically for tax: vendors like SAP, Oracle,
and various ERP-tax integrations use rules-engine patterns (not always
Drools) for tax-determination logic.

**Architecture summary.** Forward-chaining Rete-style matcher; rules
live as `.drl` files (text DSL) or in decision tables (spreadsheets).
KIE (Knowledge Is Everything) wrappers package rules into deployable
units; rules can be updated at runtime without code redeploy.

**Strengths.** (a) Mature JVM rules engine with proven scale. (b)
Decision-table format is editable by non-programmers. (c) Runtime rule
updates are first-class. (d) Audit trail of rule firings.

**Weaknesses.** (a) General-purpose; nothing tax-specific or legally
literate. (b) Imperative RHS actions encourage side-effects that are
hard to audit. (c) Forward-chaining is a hammer; legal reasoning isn't
always naturally forward-chaining. (d) Adds a heavy second runtime
(Drools itself is millions of LOC, with its own classloader and
threading model) — disqualifying for kontor's single-dep posture.

**License + JVM compatibility.** Apache 2.0; JVM-native; would compile
into kontor's dependency closure. **But ADR-001 single-dep would die
the day we add it.**

**Adaptability to kontor.** Concepts only. The fact-base + rule-match
pattern is a possible internal architecture for a future
`kontor.tax-rule-evaluator`, but writing a small Clojure-native
priority-resolver (Catala-style) is cheaper than importing Drools.

---

### 7. Tax-Calculator (PSLmodels, MIT)

**What it is.** A Python *microsimulation model* of the US federal income
and payroll tax. Tax policy parameterized via ~200 JSON-defined parameters;
the calculation logic is procedural Python over a `Records` dataframe
([Tax-Calculator docs](http://taxcalc.pslmodels.org/);
[Tax-Calculator parameters](https://taxcalc.pslmodels.org/api/parameters.html)).

**Scope.** US federal income + payroll only. State taxes are out of scope.

**Production status.** Active since ~2015; used by think tanks (CBO-adjacent,
TPC-adjacent, Heritage, Mercatus). Validated against IRS aggregate data
and against other models (NBER, TPC). Production for *policy analysis*,
not for *taxpayer filings*.

**Architecture summary.** A `Records` object holds a sample of filing units
(from IRS PUF + matched survey data). A `Policy` object holds parameters
(JSON-loaded). The `Calculator` iterates Records under Policy and computes
tax liabilities through ~30 Python functions corresponding to roughly the
1040 form's sections. Reform analysis: instantiate two `Calculator`s, one
baseline, one reform, diff.

**Strengths.** (a) Parameter JSON is editable and version-controlled
alongside legislation. (b) Reforms-as-JSON is a clean primitive. (c)
Validation discipline is exemplary — cross-model comparison, golden cases,
full code-coverage tests. (d) CPI-indexing of parameters is built in.

**Weaknesses.** (a) US federal only. (b) Procedural Python; the logic
isn't lifted into data. (c) Not designed for per-taxpayer accounting —
it's a population-level simulator. (d) No notion of cross-jurisdiction
concepts (it's mono-jurisdiction by design).

**License + JVM compatibility.** MIT — clean. Python; no JVM story.

**Adaptability to kontor.** The JSON-parameter pattern + CPI-indexing
+ validation-discipline are all worth borrowing. The procedural-Python
shape of the formulas is not.

---

### 8. PolicyEngine (policyengine.org, AGPL-3.0)

**What it is.** A *fork-and-evolution* of OpenFisca, focused on tax-benefit
microsimulation with a web frontend, REST API, and growing per-country
coverage (US, UK; others scaffolded) ([PolicyEngine GitHub](https://github.com/policyengine);
[PolicyEngine Core docs](https://policyengine.github.io/policyengine-core/)).

**Scope.** US (federal + 50 states with growing depth) and UK as the
flagship implementations. State coverage is the most aggressive of any
open-source tax model.

**Production status.** Active nonprofit (~$1M-ish annual funding). Used
by think tanks, journalists, and ordinary users via the web UI. Not used
for actual taxpayer filings; used for analysis and personal "what would
my tax change be" estimation.

**Architecture summary.** Same core as OpenFisca (Variable + Parameter +
Entity + Period) but with a substantially expanded API, web simulation
layer, structured-reform DSL, and a documentation/citation infrastructure
that ties each parameter back to a specific code citation. Recent additions:
MCP server for AI-assisted modelling, AI-assisted state-by-state coverage
expansion.

**Strengths.** (a) Inherits OpenFisca's strengths and matures them.
(b) State-level depth — proves the per-jurisdiction packaging pattern
scales to 50 sub-jurisdictions. (c) Citation discipline — every parameter
links to a statute. (d) The web-front-end + API exposure proves the
"law-as-data → simulate any reform" loop end-to-end.

**Weaknesses.** (a) AGPL-3.0 (same problem as OpenFisca). (b) Inherits
OpenFisca's "logic is still imperative Python" weakness. (c) Microsimulation
focus is orthogonal to GL accounting needs.

**License + JVM compatibility.** AGPL-3.0; Python. Same disqualification.

**Adaptability to kontor.** Validates that the OpenFisca-style data
model scales to many jurisdictions and to state-level granularity.
Citation-back-to-statute is exactly the provenance we want for
explainability.

---

### 9. IRS Modernized e-File (MeF) — XML schemas

**What it is.** A government-mandated XML *schema family* for transmitting
US tax returns to the IRS. Not a computation engine — a *wire format* and
a set of validation rules ("business rules") that returns must satisfy
([IRS MeF overview](https://www.irs.gov/e-file-providers/modernized-e-file-overview);
[MeF schemas and business rules](https://www.irs.gov/e-file-providers/modernized-e-file-mef-schemas-and-business-rules)).

**Scope.** All US federal returns (1040, 1041, 1065, 1120, 990, 2290, etc.)
and many state piggyback returns.

**Production status.** Mandatory production system, used for the vast
majority of US tax filings.

**Architecture summary.** Every IRS form line gets an XML element name.
Schema-validation catches structural errors; "business rules" (separately
maintained) catch arithmetic/logical errors ("if line 12 > 0 then line 13
must = line 12 * 0.15"). Returns flow through validation; failures get
rejected back to the transmitter.

**Strengths.** (a) Stable, versioned schemas — every tax year has its own
schema versions. (b) Business rules separate from schema validation
(layered validation). (c) Massive interoperability — every commercial tax
package targets MeF.

**Weaknesses.** (a) XML schemas are *output-shaped*, not law-shaped — they
describe what the return looks like, not why. (b) Business rules are
imperative checks, not declarative law. (c) Closed-source rule definitions
in some cases.

**License + JVM compatibility.** Public-domain (US government work).

**Adaptability to kontor.** The output-shape-versus-law-shape distinction
is important: kontor's `:tax-return` entities are output-shaped (forms),
while what we're now considering (`:provision`, `:concept`) is law-shaped.
**Both will be needed.** kontor's existing return-builder pattern is
output-shaped; the new concept catalog is law-shaped.

---

### 10. HMRC Making Tax Digital (MTD)

**What it is.** The UK tax authority's mandate that all VAT-registered
businesses and (eventually) self-assessment taxpayers submit data via a
REST API, not via web forms ([VAT MTD API docs](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/vat-api/1.0);
[MTD end-to-end guide](https://developer.service.hmrc.gov.uk/guides/income-tax-mtd-end-to-end-service-guide/)).

**Scope.** UK VAT (live since 2019, ~7 years), Income Tax Self Assessment
(rolling out 2024-2027).

**Production status.** **In production at national scale.** Every VAT-registered
UK business must use compatible software.

**Architecture summary.** OAuth2-protected REST endpoints. Software sends
structured submissions; HMRC validates and stores. The "law-as-data" is
*on the HMRC side* (their internal systems compute the actual liability);
on the taxpayer side, the API is purely transactional.

**Strengths.** (a) Forces all UK accounting software to integrate via
a single API. (b) Drives the entire ecosystem toward digital record-keeping.

**Weaknesses.** (a) Same as MeF — the API describes submissions, not law.
(b) Tax computation logic remains opaque on HMRC's side. (c) Per-tax-domain
API surface (separate VAT API, Income Tax API, etc.) — no unified concept
catalog.

**Adaptability to kontor.** The MTD API is a *target* for kontor (we should
be able to emit MTD-VAT submissions from a kontor tax return) more than a
*source* of data-model ideas. The interesting thing it teaches us is that
tax authorities are moving toward API-first, which raises the bar for
"my tax return computation is reproducible from these inputs."

---

### 11. Commercial transactional tax engines (Avalara, TaxJar, Stripe Tax,
Thomson Reuters ONESOURCE, Wolters Kluwer CCH)

**What it is.** SaaS engines that compute indirect tax (sales/VAT/GST) in
real time on transactions, plus filing/return automation. Proprietary
([Avalara](https://www.avalara.com/); [TaxJar](https://www.taxjar.com/);
[Stripe Tax](https://stripe.com/tax); [ONESOURCE](https://tax.thomsonreuters.com/en/onesource);
[ONESOURCE Indirect Tax Determination](https://tax.thomsonreuters.com/en/products/onesource-determination)).

**Scope.** Avalara: 14,000+ US jurisdictions + 60+ countries.
TaxJar: 14,000+ US jurisdictions, narrower internationally. Stripe Tax:
50+ countries. ONESOURCE: 56,000+ jurisdictions, 200 countries.

**Production status.** All enterprise-grade production at massive scale.

**Architecture summary (from public material).** Each presents as a
**REST API** (calculate-tax, validate-address, file-return). Internally,
**rate tables** (jurisdiction × product-category → rate) drive the
calculations; a **rules engine** routes products through taxability
determination ("is software taxable in Texas?"); **content teams**
(humans) maintain the rate tables and rule changes. ONESOURCE explicitly
mentions tax-expert teams "continuously update tax rules and regulations
from 56,000+ jurisdictions and 200 countries."

**Strengths.** (a) The content-team operational model is the *real* story:
keeping rate/rule tables current is a full-time job for dozens of analysts.
(b) Address-validation + jurisdiction-resolution as a first-class concern.
(c) Audit-trail of the rate used for each transaction is a regulatory
requirement they all satisfy.

**Weaknesses.** (a) Proprietary; the internal data model is opaque.
(b) Per-customer licensing — tax data is not portable. (c) Per ADR-005,
kontor explicitly does NOT compete here; we provide the protocol and let
customers integrate Avalara/TaxJar.

**License + JVM compatibility.** Proprietary; not directly applicable.

**Adaptability to kontor.** The architectural lesson is **the content
team**, not the data model. Whatever schema kontor adopts for
`:tax-concept`/`:provision`, the operational question is *who keeps it
current*. Commercial vendors solve this with paid analysts; OpenFisca/Catala
solve it with government partnership; kontor would need an explicit answer
(probably: "the per-jurisdiction l10n module maintainer, with optional
authority feed-ins from XBRL taxonomies"; see ADR-090 + note 78).

---

### 12. DataLex / YSH (AustLII, ~1985 onward, "non-commercial" license)

**What it is.** A *legal expert-system shell* from AustLII (Australasian
Legal Information Institute). YSH ("Y-shell") is a quasi-natural-language
rule-based inferencing language; both forward- and backward-chaining;
integrates with full-text legal-corpus retrieval and hypertext
([DataLex on AustLII Communities](https://austlii.community/wiki/DataLex);
[YSH manual](https://austlii.community/foswiki/pub/DataLex/WebHome/ys-manual.pdf)).

**Scope.** Australian + NZ legal domain primarily; used in law-school
clinics and for some advisory deployments.

**Production status.** ~40 years of continuous development, but adoption
is academic + advisory-tool scale, not government-rewriting-the-tax-system
scale. Recent rebranding as "DataLex AI."

**Architecture summary.** YSH rules look close to natural-language English
("`employment-relationship exists if person is employed`"). Both forward
and backward chaining by default. Integrates with text retrieval to
auto-cite the source authority. AustLII hosts deployed applications in a
shared environment.

**Strengths.** (a) Long track record — proves quasi-NL rule representation
is durable. (b) Hypertext-to-source integration is exactly the "explainability"
discipline kontor's `:audit-doc` already aspires to. (c) Combination of
forward + backward chaining handles both "compute this benefit" and
"why does this benefit apply?" queries.

**Weaknesses.** (a) Quasi-NL is hard to author rigorously. (b) Not
broadly adopted outside AU/NZ legal academia. (c) Non-commercial license
limits use.

**License + JVM compatibility.** Non-commercial use license; not
JVM-compatible.

**Adaptability to kontor.** Mostly inspiration — the
hypertext-to-source pattern + quasi-NL goal are aligned with where kontor
might want to go for `:provision` audit-doc citations. Don't use the code.

---

### 13. TurboTax / Intuit Tax Knowledge Graph

**What it is.** A *knowledge graph* representation of US (and Canadian)
tax compliance logic — calculations + rules + user-data linkages — described
in a 2020 Intuit research paper ([arXiv 2009.06103](https://arxiv.org/pdf/2009.06103);
[Decision Management Community summary](https://dmcommunity.org/2020/08/20/intuit-tax-knowledge-engine/)).

**Scope.** US + Canadian personal income tax.

**Production status.** Production-scale (TurboTax serves ~40M filers/year).
The paper describes the architectural shift from procedural code to a
knowledge-graph model.

**Architecture summary.** A graph where nodes are tax concepts (deductions,
credits, line items, eligibility conditions) and edges are dependencies.
The graph is *instantiated* per user with their actual data; the
"explanation" feature walks the graph to surface why a number is what it
is. AI/LLM layers atop do not compute tax — they explain it.

**Strengths.** (a) Decoupling **representation** (the graph) from
**evaluation** (the engine that walks it) is the lesson. (b) "Reason from
the graph to find missing data" is an inversion — instead of asking the
user every possible question, the engine walks the graph and asks only
the questions whose answers would change the result. (c) Explainability
is a first-class output, not bolted on.

**Weaknesses.** (a) Proprietary; the precise schema is not published.
(b) US/Canada only.

**License + JVM compatibility.** Proprietary.

**Adaptability to kontor.** The **representation-vs-evaluation decoupling**
is the right shape for what kontor would build. A `:tax-concept`/`:provision`
schema is the representation; `kontor.tax-rule-evaluator` (hypothetical)
would be the evaluator. Explainability (`kontor.explain` per ADR-091) is
already shaped right for this — extending it to walk provisions would be
natural.

---

## Part 2 — Synthesis

### §A. Functional requirements the prior art reveals

**A1. A concept catalog must exist, and the choice is global-versus-per-jurisdiction.**
Cross-jurisdiction concepts (rollover relief, participation exemption,
lifetime cap, loss-bucket compartmentalization, holding-period preferential
rate) are real — they appeared independently in notes 108-115. Prior art is
split. OpenFisca treats every country as an island, no shared concept
catalog → leads to per-country reinvention. XBRL/IFRS taxonomies *do* have
a shared concept catalog (per ADR-090 + note 78). Catala has no concept
catalog at all (each scope is its own thing). The right shape for kontor
is **a shared concept catalog** (kernel-level `:tax-concept` entities,
with `:concept-iri` extension of ADR-090) **plus per-jurisdiction
instantiations** (`:provision` entities that reference a `:tax-concept`
and bind it to a specific statutory text + parameters).

**A2. Versioning over time must be first-class.** Every prior project that
touches actual tax handles this. OpenFisca: date-keyed YAML values +
`formula_YYYY_MM` method names. Akoma Ntoso: FRBR Expression layer.
Catala: literate-programming files-per-version + date guards. The cleanest
model for kontor is **bitemporal**, which we already have — a `:provision`
entity stamped with `:tx/valid-from` (the doctrine of "law-as-it-stood")
queried via `:as-of-valid`. Combine with Akoma Ntoso's FRBR Work/Expression
distinction: a `:statute/work` is stable, a `:statute/expression` is
period-bound.

**A3. Composition of provisions ("section A applies if B and C") must be
explicit.** Catala's default logic is the most elegant model in the
survey — definitions-under-conditions with explicit exception priorities,
with a runtime conflict-error if two exceptions tie. OpenFisca punts this
into Python `if` statements. kontor should adopt **prioritised
conditional definitions** as a `:provision` shape: `{:provision/condition ...
:provision/consequence ... :provision/priority ... :provision/exception-of <ref>}`.

**A4. Provenance / explainability must trace from numbers to provisions.**
TurboTax's knowledge-graph walk + ADR-091's `kontor.explain` are aligned.
The right shape: every computed tax-related number carries a reference
trail back to the `:provision`(s) used. Akoma Ntoso's stable Work IRIs
+ ADR-090's `:concept-iri` give us the citation primitive.

**A5. Authoring + change management must have a clear owner.** Survey
answers: government partnership (Catala-DGFIP, OpenFisca-France),
volunteer maintainers (Tax-Calculator, PolicyEngine country packages),
paid content teams (Avalara/ONESOURCE), single-jurisdiction lab teams
(SMU L4, AustLII DataLex). For kontor, the natural answer is **the
per-jurisdiction l10n module maintainer**, with the `:provision` schema
designed to be editable as EDN + (optionally) ingested from authority-published
data (XBRL filing taxonomies, government open-data publications).

**A6. Internationalization (statute names in DE/FR/JP/EN).** OpenFisca
+ Akoma Ntoso both handle multi-language metadata. kontor already has
`:audit-doc/language` (ADR-078). A `:provision` should carry
`:provision/citation` as a map keyed by language tag.

**A7. Test infrastructure (validating that law-as-data computes correctly).**
OpenFisca uses YAML golden tests; Tax-Calculator uses cross-model
validation + golden cases; Catala's literate-programming embeds
example computations alongside the rules. For kontor: **golden-case
test files per jurisdiction**, with the test format ideally human-readable
(EDN) and CI-validated. The current `*_fit.clj` files (notes 108-115) are
already close to this shape; lifting them into pure data + a generic
runner is a small refactor.

### §B. Non-functional requirements

**B1. Auditability.** OpenFisca: parameter changes are git commits in a
public repo. Akoma Ntoso: every Expression has a stable URI. Catala:
literate-programming makes review trivial. For kontor, the bitemporal
substrate + sealing (ADR-007) + audit-doc (ADR-038) + commit-hash
(ADR-003) already provide the infrastructure; we need the *content* —
provisions carrying source citations.

**B2. Performance at scale.** OpenFisca optimises for microsimulation
(NumPy vectorization over millions of households). Tax-Calculator similar.
kontor's use case is per-taxpayer GL accounting, not population
microsimulation — so we are not constrained by their NumPy-shaped
designs. A clean Clojure record-and-protocol evaluator suffices for
N=1 taxpayer × M provisions, even with hundreds of provisions per
jurisdiction.

**B3. Maintainability.** When a jurisdiction issues an amendment, the
change should land in a single artifact (one `kontor-l10n-XX` module).
OpenFisca's parameter-tree model + Tax-Calculator's JSON-reform model
both demonstrate this; kontor's per-l10n-module artifact split (ADR-072
+ kontor-l10n-* convention) already supports it. The new requirement
is: changes to `:provision` data should be small commits with clear
diffs (favoring EDN data over Clojure code for the law-as-data part).

**B4. Adoption friction per jurisdiction.** OpenFisca's country-template
is the gold standard — a skeleton repo with placeholder parameters
that a new-country contributor fills in. kontor should ship an
analogous template once the schema stabilises (a `kontor-l10n-template`
artifact with provision-skeletons for income tax, indirect tax,
withholding).

**B5. Interop with GL/ERP.** Commercial engines integrate via REST
APIs; OpenFisca via Python imports. kontor's distinguishing position
is **the tax engine IS the GL** — provisions resolve to postings that
are first-class in the same datahike database. This is structurally
better than the API-integration models, and we should keep it that way.

**B6. Trust + legal liability.** Every commercial vendor disclaims:
"the calculation is the customer's responsibility." Government-deployed
Catala/OpenFisca shift liability to the state. kontor follows the
commercial-vendor pattern (the customer is responsible; we provide
the substrate). The `:audit-doc`/`:approval-policy` design (ADR-038)
already encodes the right discipline — every material posting can be
traced to a documented authority.

### §C. What does NOT fit kontor

**C1. Imperative-Python formulas (OpenFisca, PolicyEngine, Tax-Calculator).**
The law itself is data; the *evaluator* should be the only place with
imperative logic. Resist the temptation to write provisions as Clojure
functions; write them as data (EDN maps) and have a single evaluator.

**C2. XML serialization (LegalRuleML, Akoma Ntoso, IRS MeF, e-invoice
formats).** kontor is a Clojure data shop. EDN is the right serialization.
We can *emit* XML for filings (and the kernel already does, e.g.
`xml/t4.clj`), but the internal representation must be Clojure data.

**C3. A new DSL with its own surface syntax (Catala, L4, YSH).** Each
project's surface syntax is a multi-year toolchain investment (parser,
type-checker, IDE, language server). kontor cannot afford that and
does not need it — EDN-as-DSL gives us 80% of the benefit at 5% of
the cost. The shape of `:provision` data can be Catala-inspired without
inventing a parser.

**C4. A second runtime (Drools, Catala-OCaml, OpenFisca-Python).** ADR-001
single-dep ends the day we add any of these. The evaluator must be
Clojure.

**C5. Vendor content teams (Avalara, ONESOURCE).** kontor cannot operate
a paid analyst team that tracks 56,000 jurisdictions. The l10n module
maintainer model + community contributions + XBRL ingestion is the
realistic answer for the foreseeable future.

**C6. Microsimulation vectorization (OpenFisca-NumPy).** Our N=1
taxpayer-at-a-time use case does not justify the complexity.

### §D. Open questions for the maintainer

**D1.** Is the concept catalog **global** (a shared kernel-level set of
`:tax-concept` entities — rollover, participation-exemption, etc.) or
**per-jurisdiction** (each l10n module owns its own concepts)? Prior art
splits; the cross-jurisdiction patterns from notes 108-115 argue for
global, but global means kernel changes every time a new concept is
discovered, which has migration cost. Tentative recommendation: **global,
but additive only** (concepts are append-only; never removed).

**D2.** Are `:provision` records **configuration that a generic evaluator
consumes**, or are they **records that delegate to per-provision Clojure
code**? Catala+OpenFisca split the field: Catala is "configuration + one
evaluator"; OpenFisca is "data parameters + per-variable Clojure-like
code." For kontor, the bias should be **configuration-first**: a generic
evaluator covers 80% of provisions, and the remaining 20% (complex
formula computations) escape into a `:provision/compute-fn` keyword that
resolves to a registered function. Defer the escape hatch until needed.

**D3.** Is the law-as-data **hand-written in Clojure EDN** or
**ingested from authority sources** (XBRL filing taxonomies, government
open-data publications, Akoma Ntoso documents)? Prior art is hand-written
across the board (OpenFisca YAML, Catala scopes, Tax-Calculator JSON)
because authority-published rates are usually not in a structured form
that fits a tax engine. **Hand-written initially; design the schema so
ingestion from authority sources is possible later** when XBRL/AKN
sources are available.

**D4.** Do `:provision` entities have a **scope** (Catala's notion — a
unit of computation) or do they compose more freely? Catala's scope
notion is powerful but heavy (each scope is a compilation unit).
A lighter design: provisions reference each other via concept IRIs;
the evaluator computes a closure on demand.

**D5.** How are **default + exception priorities** represented? Catala
makes this first-class with a default-calculus semantics. For kontor:
**ordered priority numbers + an explicit exception-of reference** suffice
(no need for the full default calculus). Conflict detection: if two
provisions at the same priority apply, raise a `kontor.tax/ambiguous`
exception with both citations.

**D6.** Where does **temporal validity** live? Three options:
(a) On the `:provision` itself (one entity per period); (b) Via a separate
`:provision-version` entity (one Work, many Expressions in Akoma Ntoso
parlance); (c) Via kontor's bitemporal `:tx/valid-from` (mutate the
provision; old version is recoverable via `:as-of-valid`). Recommendation:
**(c)** because it reuses kontor's bitemporal substrate; (b) if we
want explicit Work-vs-Expression separation for citation purposes.

**D7.** What is the **golden-test format** for provisions? Tax-Calculator
JSON, OpenFisca YAML, Catala embedded literate, kontor's current `*_fit.clj`
Clojure. **Lift the `*_fit.clj` patterns into pure EDN data files** that a
generic runner consumes; keep the Clojure test wrappers only for
non-data-driven cases.

**D8.** Should we adopt **deontic markers** (LegalRuleML's
obligation/permission/prohibition) for non-tax legal data the engine
might handle later (e.g. payroll deductions are obligations; foreign-tax
credit is a permission)? Probably not — adds conceptual surface for
limited gain. Tax computation is arithmetic; the deontic dimension can
live in `:audit-doc` if ever needed.

**D9.** How do we handle **multiple semantic interpretations** of the
same provision (LegalRuleML's plural-interpretation model)? Realistically,
kontor commits to one interpretation per provision (the one the
maintainer's CPA agrees with). Alternative interpretations would be
out-of-band notes, not first-class data.

**D10.** Do we expose a **public API** for `:provision` reads (an analog
of HMRC's MTD or a Catala-style web frontend)? Probably out of scope
initially — kontor is a library, not a service. But the data shape should
not preclude a future service wrapper.

---

## Recommendation

**Clone and read at code-level depth:**

1. **Catala** (`https://github.com/CatalaLang/catala`). The default-logic
   semantics is the cleanest formal model for "general rule + exceptions"
   in the survey. The compiler's scope-language → default-calculus →
   exception-calculus translation is well-documented and small enough to
   read in a few hours. **High-priority read** — it informs §A3 (composition
   of provisions) most directly.

2. **OpenFisca-Core** (`https://github.com/openfisca/openfisca-core`)
   *plus* one country package (`openfisca-france` or `policyengine-us`).
   The Parameter-tree + date-keyed-values pattern is the operational
   gold standard; the Variable + formula_YYYY_MM pattern is the
   anti-pattern (we should not adopt it). Reading the country package
   gives us a sense of what "300 provisions encoded" actually looks
   like in practice — a humbling exercise before we commit to a schema.

**Optional second-pass read:**

3. **PolicyEngine-core + policyengine-us** — for the citation-back-to-statute
   discipline and the state-level coverage pattern. Useful for §A4
   (provenance) and §B4 (per-jurisdiction adoption).

**Dismiss from further reading:**

- **LegalRuleML / Akoma Ntoso** — borrow concepts (FRBR layering, metadata
  shape) from the specs; do not read XML schemas at code depth.
- **L4 / YSH-DataLex** — Catala covers the same terrain with more production
  proof points; reading L4 too gives diminishing returns.
- **Drools** — disqualified by ADR-001 single-dep; do not study.
- **Tax-Calculator** — narrow scope (US federal only); the JSON-parameter
  pattern is already understood from the search above; deeper read
  unnecessary.
- **TurboTax knowledge graph** — proprietary; the 2020 paper has been read;
  no source to study.
- **Commercial engines (Avalara/ONESOURCE/CCH)** — proprietary; the lesson
  (content-team operational model) does not require code reading.
- **IRS MeF / HMRC MTD** — wire-format specifications, not law-as-data
  models; relevant only as filing targets.
- **Swiss cantonal calculators** — small in scope; useful as benchmarks
  for what a per-canton minimal model looks like, but not architecturally
  novel.

**Concrete next step the maintainer might take:** spawn two per-project
agents — one each on Catala and OpenFisca-core — with the brief
*"derive a candidate `:tax-concept` / `:provision` schema for kontor by
analogy to this project's central abstraction, citing file:line evidence."*
The two candidate schemas should converge or diverge in instructive ways;
synthesis becomes the basis for an ADR.

---

## Sources

- [Catala: A Programming Language for the Law (arXiv)](https://arxiv.org/pdf/2103.03198)
- [Catala GitHub](https://github.com/CatalaLang/catala)
- [Catala book](http://book.catala-lang.org/en/index.html)
- [Catala definitions and exceptions](https://book.catala-lang.org/en/5-4-definitions-exceptions.html)
- [Bob Atkey: simple semantics for defaults in Catala](https://bentnib.org/posts/2023-01-16-catala.html)
- [Inria: CATALA translates law into code](https://www.inria.fr/en/catala-software-dgfip-cnaf)
- [Northwestern Pritzker: Sarah Lawsky on Catala](https://news.law.northwestern.edu/news/sarah-lawsky-worked-on-a-tax-law-code-that-the-french-government-deemed-officially-awesome/)
- [OpenFisca documentation](https://openfisca.org/doc/)
- [OpenFisca architecture](https://openfisca.org/doc/architecture.html)
- [OpenFisca: Variables](https://openfisca.org/doc/openfisca-python-api/variables.html)
- [OpenFisca: Legislation evolutions](https://openfisca.org/doc/coding-the-legislation/40_legislation_evolutions.html)
- [OpenFisca country-template](https://github.com/openfisca/country-template)
- [LegalRuleML Core Spec v1.0 (OASIS)](https://www.oasis-open.org/standard/legalruleml-core-specification-version-1-0-oasis-standard/)
- [OASIS LegalRuleML GitHub](https://github.com/oasis-tcs/legalruleml)
- [Akoma Ntoso v1.0 vocabulary](https://docs.oasis-open.org/legaldocml/akn-core/v1.0/cs01/part1-vocabulary/akn-core-v1.0-cs01-part1-vocabulary.html)
- [Akoma Ntoso naming convention](https://docs.oasis-open.org/legaldocml/akn-nc/v1.0/akn-nc-v1.0.html)
- [Akoma Ntoso (Wikipedia)](https://en.wikipedia.org/wiki/Akoma_Ntoso)
- [SMU CCLaw GitHub](https://github.com/smucclaw)
- [Defeasible semantics for L4 (Governatori + Wong)](https://ink.library.smu.edu.sg/cclaw/5/)
- [Drools rule engine docs](https://docs.drools.org/latest/drools-docs/drools/rule-engine/index.html)
- [Tax-Calculator (PSLmodels)](http://taxcalc.pslmodels.org/)
- [Tax-Calculator Parameters API](https://taxcalc.pslmodels.org/api/parameters.html)
- [Tax-Calculator GitHub](https://github.com/PSLmodels/Tax-Calculator)
- [PolicyEngine GitHub org](https://github.com/policyengine)
- [PolicyEngine Core docs](https://policyengine.github.io/policyengine-core/)
- [PolicyEngine-US](https://github.com/PolicyEngine/policyengine-us)
- [IRS Modernized e-File (MeF) overview](https://www.irs.gov/e-file-providers/modernized-e-file-overview)
- [IRS MeF schemas and business rules](https://www.irs.gov/e-file-providers/modernized-e-file-mef-schemas-and-business-rules)
- [HMRC VAT MTD API](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/vat-api/1.0)
- [HMRC MTD Income Tax end-to-end guide](https://developer.service.hmrc.gov.uk/guides/income-tax-mtd-end-to-end-service-guide/)
- [Avalara](https://www.avalara.com/)
- [TaxJar](https://www.taxjar.com/)
- [Stripe Tax](https://stripe.com/tax)
- [Thomson Reuters ONESOURCE](https://tax.thomsonreuters.com/en/onesource)
- [ONESOURCE Indirect Tax Determination](https://tax.thomsonreuters.com/en/products/onesource-determination)
- [DataLex on AustLII Communities](https://austlii.community/wiki/DataLex)
- [YSH manual (Mowbray)](https://austlii.community/foswiki/pub/DataLex/WebHome/ys-manual.pdf)
- [Tax Knowledge Graph for TurboTax (arXiv 2009.06103)](https://arxiv.org/pdf/2009.06103)
- [Service Innovation Lab: Better Rules and Legislation as Code (NZ)](https://serviceinnovationlab.github.io/projects/legislation-as-code/)
- [Stanford CodeX](https://law.stanford.edu/codex-the-stanford-center-for-legal-informatics/)
- [Symbium (Computational Law in Complaw Corner)](http://complaw.stanford.edu/blog/symbium.html)
- [Swiss Federal Tax Administration calculator (ti&m)](https://www.ti8m.com/en/success-stories/public-and-e-government/steuerrechner)
- [Open-source Swiss tax calculator](https://github.com/devbrains-com/swisstaxcalculator)
