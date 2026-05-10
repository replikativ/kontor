# `datahike-accounting`: Reuse map (research notes)

A practical "what to lift, what to fork, what to write" assessment for a German/EU SMB-first double-entry accounting kernel built on datahike, informed by Odoo 19 (LGPLv3), Tryton (GPLv3), the plain-text-accounting lineage (MIT/GPL), and the EU e-invoicing stack.

## 1. Odoo Community LGPLv3 — what it actually permits

Odoo Community (incl. `addons/account`, `addons/l10n_*`) is LGPLv3
([odoo.com/documentation/19.0/legal/licenses.html](https://www.odoo.com/documentation/19.0/legal/licenses.html)).
The FSF's official stance is unambiguous on language ports: "translation of a work is considered a kind of modification", so a Clojure port of Odoo Python *is a derivative work* and must remain LGPLv3
([gnu.org/licenses/gpl-faq.html](https://www.gnu.org/licenses/gpl-faq.html#TranslateCode)). LGPLv3's "linking" exemption helps users of the library; it does not transform a translated source into independent work.

Conservative reading for `datahike-accounting`:

- (a) Reading and learning from Odoo source: **fine**, no license obligation from reading.
- (b) Translating algorithms verbatim to Clojure: **derivative; must be LGPLv3**. Safer route is the classic clean-room: write a spec from the source, then implement against the spec without re-reading. SCOTUS *Google v. Oracle* notwithstanding, FSF's interpretation is what most German legal departments will apply.
- (c) Lifting XML/CSV data files (CoA, tax tags): **ambiguous but usable** — the `data/*.xml` records carry the Odoo headers (see file inventories at [odoo/odoo addons](https://github.com/odoo/odoo/tree/19.0/addons)) and are functionally *part of* the LGPLv3 module. The underlying *facts* (account codes, statutory tax rates) are uncopyrightable in DE/EU; the *selection and arrangement* may attract sui generis database protection (EU Database Directive 96/9/EC). Treat the XML files themselves as LGPLv3, but the extracted facts (re-keyed as EDN) as essentially free. Re-derive from the regulatory source (BMF/DATEV) where possible.
- (d) Lifting docstrings/comments verbatim: **derivative — same as code**. Don't.
- (e) Same-repo vs separate: irrelevant to the license question per se, but a separate `datahike-accounting-l10n-odoo` module that is plainly LGPLv3 and is loaded as data keeps the kernel itself free of the obligation.

**Tryton context.** Tryton was forked from TinyERP 4.2 in late 2007 by Cédric Krier and Bertrand Chenal; it has shipped under GPL-3.0-or-later from day one, and the "modules-account-*" tree is mature ([Wikipedia: Tryton](https://en.wikipedia.org/wiki/Tryton); [tryton.org](https://www.tryton.org/)). The German plug `account_de_skr03` is **GPLv3**, not LGPL ([github.com/tryton/account_de_skr03](https://github.com/tryton/account_de_skr03); [docs.tryton.org/latest/modules-account-de-skr03/](https://docs.tryton.org/latest/modules-account-de-skr03/)), with `account_de.xml` and `tax_de.xml`. There is also a virtualthings community SKR04 ([github.com/virtualthings/account_de_skr04](https://github.com/virtualthings/account_de_skr04)). GPLv3 is *worse* than LGPLv3 for our purposes — it forces the consumer of the library, not just the modifier, into GPL obligations. Use Tryton as a **reference design**, not as code we lift.

**OCA.** ~250 repos, ~1000 modules, all LGPL-3 or AGPL-3 ([odoo-community.org/resources/faq](https://www.odoo-community.org/resources/faq); [github.com/oca](https://github.com/oca)). For accounting in particular: `OCA/account-financial-tools`, `OCA/account-financial-reporting`, `OCA/account-invoicing`, `OCA/l10n-germany`. Same LGPL constraints as core Odoo. Most useful as a *reading list* showing what enterprise actually demanded but didn't get in Community (asset management, MIS reports, advanced reconciliation).

## 2. Chart-of-accounts and tax data

The Odoo `addons/l10n_de_skr03` and `_skr04` packages contain `data/*.xml` files (`account_data.xml`, `account_tax_data.xml`, fiscal-position and reconcile-template data) — a complete out-of-the-box CoA + VAT mapping ([Odoo DE docs](https://www.odoo.com/documentation/19.0/applications/finance/fiscal_localizations/germany.html)).

DATEV's SKR03/SKR04 themselves: **not in the public domain** in any clean way. DATEV publishes them as templates for end customers and explicitly does not license them for resale to IT vendors. Account *numbers and titles* are facts and not protectable, but the published Kontenrahmen documents are. The pragmatic path the OSS world has converged on:

- GnuCash ships German account templates incl. SKR49 (non-profits) under GPL; SKR03/04 community versions exist ([baltpeter/skr-json](https://github.com/baltpeter/skr-json), GnuCash German wiki).
- Tryton ships SKR03 (official) and SKR04 (community), GPLv3.
- Odoo ships SKR03/04 with tax mappings, LGPLv3.

**Recommendation:** ingest the *facts* (account number, name, type, default tax tag) from one or two of the above (Tryton's SKR03 file is small and well-curated; baltpeter/skr-json is a clean JSON projection of GnuCash) and re-publish them as EDN inside a *separately licensed* module (e.g. `datahike-accounting-l10n-de` under the source license — pick GPLv3 if sourced from Tryton/GnuCash, LGPLv3 if sourced from Odoo). Do not try to call this part "MIT".

For the 90-country footprint Odoo provides, the same pattern works: maintain an `l10n` module per country, license-tagged to its source.

## 3. The plain-text-accounting kernel

ledger-cli (BSL/MIT-ish), hledger (GPLv3), Beancount (GPLv2) all share a tiny, beautiful kernel ([plaintextaccounting.org](https://plaintextaccounting.org/); [hledger accounting-pta](https://hledger.org/accounting-pta.html); [beancount.github.io/docs](https://beancount.github.io/docs/index.html)):

- A **transaction** has 2+ **postings** (account, amount, optional cost/lot).
- Postings sum to zero; one posting may be elided and inferred.
- **Balance assertions** at points in time pin reality.
- **Commodities/lots/prices** are first-class (multi-currency for free).
- Plugins/derivatives compute reports; the store is append-only.

This maps almost 1:1 onto datahike: each posting is a datom-rich entity, transactions get the natural `:db/txInstant`, and datahike's branching becomes the killer feature for "what-if year-end close" scenarios. **You should write this kernel from scratch in <1k LOC of Clojure** — translating Beancount Python would just buy you GPLv2.

Existing Clojure prior art (all studyable, all small):

- `juxt/juxt-accounting` — Datomic-backed double-entry, BigDecimal/Joda-Money, throws on imbalance ([github.com/juxt/juxt-accounting](https://github.com/juxt/juxt-accounting)). Apache-ish licensed, last touched years ago — good design, good naming, not maintained.
- `dgknght/clj-money-datomic` ([github.com/dgknght/clj-money-datomic](https://github.com/dgknght/clj-money-datomic)).
- `greglook/merkledag-ledger` — ledger-syntax importer over DataScript ([github.com/greglook/merkledag-ledger](https://github.com/greglook/merkledag-ledger)). Useful as parser reference.
- Lucas Cavalcanti's Clojure/conj 2016 talk "Building a Powerful Double Entry Accounting System" (Nubank) — the canonical talk on this exact pattern ([2016.clojure-conj.org/powerful-accounting](http://2016.clojure-conj.org/powerful-accounting/)).

## 4. E-invoicing: ViDA, XRechnung, Factur-X, Peppol

ViDA was adopted 11 March 2025 ([taxation-customs.ec.europa.eu](https://taxation-customs.ec.europa.eu/news/adoption-vat-digital-age-package-2025-03-11_en); [vatcalc.com](https://www.vatcalc.com/eu/eu-2028-digital-reporting-requirements-drr-e-invoice/)). **From 1 July 2030**: structured e-invoices mandatory for intra-EU B2B/B2G in EN 16931 format, issuance within 10 days, transaction reporting within 5 days. Member-state domestic regimes must align by 1 January 2035. Germany's domestic B2B mandate is already phasing in (B2G has been mandatory since 27 Nov 2020).

The interchange standard is **EN 16931**, expressed in two syntaxes: **OASIS UBL 2.1** (default in Peppol BIS Billing 3.0) and **UN/CEFACT CII** (used by XRechnung-CII and Factur-X/ZUGFeRD) ([docs.peppol.eu/poacc/billing/3.0/](https://docs.peppol.eu/poacc/billing/3.0/)).

Java libraries we can call directly from Clojure (all JVM-native, no JNI):

- **Mustang / mustangproject** — Apache 2.0, reads/writes/validates ZUGFeRD 1+2/Factur-X and CII XRechnung, embeds the KoSIT validator, on Maven Central, JRE 11+ ([mustangproject.org](https://www.mustangproject.org/); [github.com/ZUGFeRD/mustangproject](https://github.com/ZUGFeRD/mustangproject)). **This is the obvious DE-first pick.**
- **KoSIT validator** — the official German B2G acceptance gate, open source ([github.com/itplr-kosit](https://github.com/itplr-kosit), `validator-configuration-xrechnung`). Use this in CI to guarantee XRechnung conformance.
- **phax/ph-ubl + phax/peppol-commons** — Apache 2.0 by Philip Helger, JAXB models for UBL 2.0–2.4, Peppol identifier/SMP/SML clients ([github.com/phax/ph-ubl](https://github.com/phax/ph-ubl); [github.com/phax/peppol-commons](https://github.com/phax/peppol-commons)). The standard route once you need Peppol network delivery.
- **phax/en16931-cii2ubl** — converts CII↔UBL when you straddle Factur-X and Peppol BIS.

Realistic floor for "compliant" in DE 2026–2028: emit XRechnung-CII (or UBL) that passes the KoSIT validator, and emit Factur-X (PDF/A-3 with embedded CII) for the hybrid case. Mustang covers both. Peppol AP integration is a separate project; defer.

Compliance neighbours we should be aware of but probably not implement v1: GoBD/GDPdU "DATEV export" (14-file ASCII bundle + INDEX.XML for tax audit, defined by AO §§146/147; [Microsoft Learn GDPdU overview](https://learn.microsoft.com/en-us/dynamics365/finance/localizations/germany/emea-deu-gdpdu-audit-data-export)). This is what differentiates a "real" DE accounting tool from a toy — plan a `datahike-accounting-gobd-export` module.

## 5. Strategic shape — what `datahike-accounting` should and shouldn't be

**Be:**

- A small (<2k LOC), MIT/EPL-licensed double-entry kernel on datahike: `account`, `transaction`, `posting`, `commodity`, `lot`, `price`, `balance-assertion`, `period-close`. Branching = scenario/forecast/year-end-draft. History = audit log for free.
- An **ingestion layer** that imports Beancount/ledger files, GnuCash XML, DATEV CSV, OFX/CAMT.053. Each importer is a tiny module.
- A **localization data layer** as separate, license-tagged modules: `l10n-de-skr03` (GPLv3 if from Tryton/GnuCash, LGPLv3 if from Odoo), `l10n-de-skr04`, etc. Re-key facts as EDN, drop Odoo XML wrapping where not needed.
- An **e-invoicing adapter** that wraps Mustang (Apache 2.0) for read/write/validate of XRechnung + Factur-X. Optional `peppol` module wrapping ph-ubl/peppol-commons.
- A **GoBD/DATEV export** module — table of differentiation in the German market.

**Don't be:**

- An ERP. No CRM, no inventory, no HR. (That's where Odoo/Tryton already win and you cannot catch up.)
- A UI. Leave that to consumers (beleg, simmis, Fava-style web frontends).
- A reimplementation of Odoo's `account.move` engine in Clojure. The PTA kernel pattern is cleaner and license-clean.
- A Peppol Access Point. Wrap a Java AP later if needed; do not implement the network.

**Concrete reuse map:**

| Asset | Source | License | Action |
|-------|--------|---------|--------|
| Double-entry kernel semantics | Beancount/hledger/ledger + JUXT talk | conceptual | **Write from scratch** in Clojure on datahike |
| SKR03/04 account list | Tryton `account_de_skr03` + virtualthings SKR04 | GPLv3 | Re-key facts to EDN; ship as separate GPLv3 `l10n-de` module |
| German VAT tax codes / tags | Odoo `l10n_de_skr03/04` `data/*.xml` | LGPLv3 | Cross-check against Tryton; ship as LGPLv3 module if any structure is lifted |
| 90-country CoAs | Odoo `addons/l10n_*` | LGPLv3 | Per-country LGPLv3 modules, lazy-build |
| Factur-X / XRechnung read/write/validate | Mustang | **Apache 2.0** | Embed directly, MIT-compatible |
| KoSIT XRechnung validation rules | KoSIT | Apache 2.0 (gov) | Use via Mustang in tests |
| Peppol BIS UBL | phax/ph-ubl, peppol-commons | Apache 2.0 | Optional add-on module |
| CII↔UBL conversion | phax/en16931-cii2ubl | Apache 2.0 | Optional |
| GoBD export structure | AO §§146/147 + DATEV docs | regulatory | **Write from scratch** |
| Reference architectures | Tryton account, JUXT, Nubank Conj 2016 talk | study | Read, design, do not lift |

## Gaps / ambiguities flagged

- **DATEV SKR licensing:** I could not find an explicit "SKR03 is public domain" statement. Conservative read: the *list of accounts as published* by DATEV is protected; the *facts* are not. Sourcing from GPL'd OSS projects (Tryton, GnuCash) and re-licensing forward as GPL is the safest path.
- **EU sui generis database right** on l10n data could matter if you redistribute the whole Odoo CoA verbatim. Re-encoding as EDN with attribution is fine in practice but not formally tested.
- **Translation as derivative work** is the FSF view, not a settled court holding everywhere; German Urheberrecht treats Bearbeitung (§3 UrhG) similarly though, so the conservative interpretation is the right one in your jurisdiction.
- **Mustang relicensing risk:** Apache 2.0, single maintainer (Jochen Stärk). Pin a version, vendor if needed.
- I did not find a Clojure-idiomatic e-invoicing wrapper that already exists; this is a unique contribution opportunity.

## TL;DR

Write a tiny PTA-style double-entry kernel on datahike yourself (it's <2k LOC and the license is yours). Lift CoA *facts* from Tryton/GnuCash/Odoo into per-country, license-tagged data modules. Wrap Mustang (APL2) for German e-invoicing. Add a GoBD/DATEV export module. Defer Peppol AP, defer UI. Don't translate Odoo Python — both because LGPLv3 makes it sticky and because the PTA kernel is genuinely a better foundation than Odoo's `account.move`.
