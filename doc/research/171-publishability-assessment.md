---
date: 2026-05-25
title: 171 — Publishability assessment for the first public GitHub release
audience: maintainer (release-tier decision + cutting punch list); pairs with notes 168 (tax coverage), 169 (substrate coverage, in flight), 170 (composition + API surface, in flight)
status: static-analysis sweep — no code changed
related:
  - note 159 (REPL exploration uncovered 2 silent-wrong P0s)
  - note 160 (API consistency audit — running log of every known DX wart)
  - note 161 (Phase D — Christian's two-DB walk, the canonical personal-use scenario)
  - note 168 (tax-system coverage matrix + substrate-seam audit)
  - ADR-001 (license EPL-1.0)
  - ADR-068 (every `!` has a `*-tx-data` builder — the post-Stage-P programming model)
  - ADR-095 (`kontor.book` verb facade)
  - ADR-099 .. ADR-107 (PeriodTaxProvider + statute-as-data + CIT/CGT providers)
---

# 171 — Publishability assessment

## TL;DR

kontor is **technically ready to publish but operationally not ready
to be cloned** by a stranger. The substrate is 11k+ assertions deep
across 12 jurisdictions, the value proposition is documented, the
showcases run end-to-end, and the canonical personal-use scenario
(`christian_scenario_test`) is a green integration test. None of that
matters if a Clojure developer runs `git clone` and `bb test` and
gets `Could not locate ../datahike-bitemporal-v1` because the kernel
pins **two `:local/root`** deps that don't exist outside the
maintainer's `~/Development` tree.

That one issue — alongside the absence of CONTRIBUTING.md, the
absence of a `release` tag, the absence of any guidance on how to add
an l10n module — is the difference between "publish a beacon for
feedback" and "publish a museum piece nobody can build." Everything
else (showcase polish, per-module READMEs, ADR index) is the kind of
work that's fine to defer to v0.2 once the first round of feedback
arrives.

**Recommendation: ship `v0.1.0-alpha` after closing the eight Tier 0
items in §7 (estimated 3-5 days of focused work). Do NOT cut v1.0.0.**
The substrate is correct enough; the API is documented enough; the
audience for feedback is ready. The Tier-0 blockers are about
**buildability + first-clone DX**, not substrate quality.

For Goal A (Christian's personal use): **4 P0 / 3 P1 / 5 P2** gaps,
of which 3 of the P0s are already closed by recent work (W1.2 single
install entry, treaty-de-ca helper, christian_scenario regression
test) and 1 P0 remains (cross-FY rollover not exercised end-to-end).

For Goal B (public GitHub release): **3 P0 / 6 P1 / 7 P2** gaps, of
which the SINGLE most-blocking item is **the two `:local/root` deps
in `deps.edn`** that make a fresh clone unbuildable.

---

## §1. The two acceptance criteria, restated as testable goals

### Goal A — personal use

Christian runs his actual books on kontor for FY 2026 and prepares
his actual tax filings.

| Slice | What must work | Status |
|---|---|---|
| **A.1 DE UG full year** — Hans-Tech UG: opening capital, ongoing revenue + opex, USt collection, period close, CIT (KSt + Soli + GewSt), dividend declaration + payment with KESt withhold, GuV + BS reports | `christian_scenario_test/de-ug-year-end-numbers-match-hand-calculation` PASSES end-to-end. DE preset + DE CIT provider land the numbers to the cent (€3,956.25 KSt+Soli + €4,287.50 GewSt on €25k profit @ Hebesatz 490). | **Works today** |
| **A.2 Vancouver sole-prop** — BC consulting revenue + 5% GST collected, opex categorisation, T2125 business-net → personal T1 (PIT) computation | CA preset boots. Sole-prop revenue + GST entry transacts. **Gap**: `kontor.sole-proprietor/business-net` exists (ADR-100); `CaT1PeriodTaxProvider` exists (ADR-099); **no integration test composes them end-to-end** for a real T1 (only `christian_scenario_test` exercises sole-prop revenue, not the T1 fold). | **Works with small fix** (~2-4 hours: write a CA-sole-prop-T1 composition test) |
| **A.3 Cross-border DE→CA dividend** — 4-leg balanced split via `kontor.treaty.de-ca/receive-dividend-from-de!`, with treaty 15% creditable + BZSt-refundable excess | `christian_scenario_test/hans-side-cross-db-dividend-via-treaty-helper` PASSES end-to-end on the canonical numbers (CAD 2,025 §126-creditable + CAD 1,535.62 BZSt-refundable + CAD 9,939.38 net cash on €9,000 gross @ 1.50 FX). | **Works today** |
| **A.4 Multi-year continuity** — close 2026, open 2027, run bitemporal queries that span both | Bitemporal substrate (ADR-008 / I-17 fix) supports it; `:through` window sugar (F11 / I-10) supports it; the substrate fixes from note 161 are in. **Gap**: no integration test closes 2026, opens 2027, and runs a comparative TB. `kontor.closing/close-fiscal-year!` exists but is exercised only in unit tests (DE flavor). | **Requires meaningful new code** (~1 day to write a 2026→2027 rollover test; the substrate supports it but the integration story isn't pinned) |

### Goal B — public GitHub release inviting feedback

A Clojure dev finds kontor on `github.com/replikativ/kontor` and:

| Slice | What must work | Status |
|---|---|---|
| **B.1 Understand it in 60 seconds** | README explains what kontor IS + what it ISN'T + license + a one-paragraph quickstart | **Present** — README.md is 316 lines, well-structured, includes tagline + showcase table + country coverage matrix + non-goals + "what's in the box." Above the bar for an OSS substrate. |
| **B.2 Working REPL in 5 minutes** | `git clone` → `bb nrepl` → posting + tax computation works | **MISSING** — `deps.edn:65` pins `org.replikativ/datahike` to `:local/root "../datahike-bitemporal-v1"` and `:69` pins `io.datopia/invariant` to `:local/root "../datopia/invariant"`. A fresh clone fails with `Could not locate` before any code runs. This is the single most-blocking item. |
| **B.3 `bb ci` passes** | Format check + lint + test suite (3050 tests / 11,683 assertions / 0 failures) | Passes locally per `c1143fb`. **MISSING externally**: blocked by the same `:local/root` issue. |
| **B.4 File issue or PR** | CONTRIBUTING.md + issue/PR templates + .github/ workflows | **MISSING** — no `CONTRIBUTING.md`, no `.github/` directory, no issue templates, no PR template, no CI workflow file. README §"Contributing" is one paragraph pointing at `doc/roadmap.md` + `CLAUDE.md`. |
| **B.5 Understand design choices** | `doc/decisions.md` is browsable + indexed | **Present but unwieldy** — 10,123 lines, 107 ADRs, no top-level index, no per-ADR anchor table. Searchable by Ctrl-F; browseable only by serial scroll. |
| **B.6 Find an end-to-end example** | At least one showcase is "fixture-demo grade" — paste, run, see the headline feature | **Present (sort of)** — six showcases exist. README points at showcase 05 (Apple 10-K) and showcase 06 (multi-year GmbH) as the bitemporal headline. Showcase 06 is the best end-to-end story but requires the `kontor-payroll-de-datev` companion + DATEV fixtures; not the lowest-friction first-touch. None of the six showcases has a "render-to-HTML-and-link-from-README" step set up in CI. |

---

## §2. Gap inventory — A side (personal use)

For each, severity tag + status + remediation cost.

### A.1 DE UG full year — **0 P0**

| Tag | Severity | Status |
|---|---|---|
| A.1.a — preset + chart + statute install in one call | P0 | **FIXED** — `kontor.l10n-de.preset/install-all!` ships and is the single consumer entry (W1.2 closed the dual-install P0 from note 168 S2; commit `c1143fb`). |
| A.1.b — DE CIT numbers to the cent | P0 | **WORKS** — `christian_scenario_test/de-ug-year-end-numbers-match-hand-calculation` validates €3,956.25 + €4,287.50 against hand calculation. ADR-104 reference notes 108 + 120 confirm against BMF worked examples. |
| A.1.c — multi-shareholder dividend allocation (per-posting `:partner`) | P1 | **FIXED** — I-15 closed in `fd3027c`; `christian_scenario_test/ug-dividend-per-shareholder-allocation-via-i15` is the regression. |
| A.1.d — KSt / GewSt / Dividenden-Zahlbar / KESt-Zahlbar accounts in SKR04 | P2 | **FIXED** — I-18 closed in `fd3027c` (~6 accounts added to SKR04 EDN). |
| A.1.e — `kontor.l10n-de.pnl/compute` returns sectioned GuV | P0 | **WORKS** — `christian_scenario_test/de-ug-year-end-numbers-match-hand-calculation` asserts §1 (Umsatzerlöse €40k) + §6 (sonstige Aufwendungen €15k) + Gewinn vor Steuern €25k. |

**A.1 is shippable today.** Christian can book Hans-Tech UG's FY 2026
end-to-end against the current main and get correct numbers.

### A.2 Vancouver sole-prop → T1 — **1 P0, 1 P1, 1 P2**

| Tag | Severity | Status |
|---|---|---|
| A.2.a — CA preset boots + sole-prop revenue + GST entry transacts | P0 | **WORKS** — `christian_scenario_test/hans-side-cross-db-dividend-via-treaty-helper` exercises the sole-prop revenue half (CAD 60k + 5% GST → balanced TB). |
| A.2.b — `kontor.sole-proprietor/business-net` → personal T1 (`CaT1PeriodTaxProvider`) composition end-to-end | **P0** | **MISSING** — `kontor.sole-proprietor/business-net` exists (ADR-100); `business-income-input` exists; `CaT1PeriodTaxProvider` exists (ADR-099, the pilot). But **no test composes them** for a real T1 with business net flowing into the PIT base. Note 168 §4.1 F-T3 flags CA PIT as "thin: 4 deftests / 17 assertions" — none of which exercise the sole-prop → T1 fold. **Cost: ~3 hours** to write a `christian_scenario_test/ca-sole-prop-t1-end-to-end` deftest. |
| A.2.c — GST/HST return computation (note 161 didn't exercise) | P1 | **MISSING** — `kontor.vat-return/compute-vat-return` (ADR-100) exists kernel-side; the CA GST-HST account routing is in `l10n-ca.gst-hst.clj` (9 deftests / 45 assertions per note 168). **No integration test** computes a quarterly GST return for a sole-prop. **Cost: ~3 hours.** |
| A.2.d — multi-province income allocation (BC + AB scenarios) | P2 | **WORKS for federal**; the per-province allocator is in `l10n-ca/cit-statute.clj` per ADR-107. For sole-prop on T1, the provincial slice is fed via the `t1-provincial` Schedule routing — exercised by 4 deftests in `l10n-ca/period_tax_provider_test.clj` but not in a Christian-shaped scenario. |

### A.3 Cross-border DE→CA dividend — **0 P0, 1 P1**

| Tag | Severity | Status |
|---|---|---|
| A.3.a — 4-leg balanced split via `treaty.de-ca/receive-dividend-from-de!` | P0 | **WORKS** — `christian_scenario_test/hans-side-cross-db-dividend-via-treaty-helper` is the regression. 4 deftests / N assertions in `modules/treaty-de-ca/test/`. Commit `2c745ee`. |
| A.3.b — discoverability: a Clojure dev finds the treaty helper from the README | **P1** | **MISSING** — `modules/treaty-de-ca/` has NO README. The README.md country-coverage table doesn't mention treaty helpers. `doc/architecture.md` doesn't list `modules/treaty-*/`. The only documentation of the helper is the test file + note 161 §7 + note 160 §I-19. A consumer who needs the same pattern for DE-US, CA-US, IN-US has no template to point at. **Cost: ~1-2 hours** to add `modules/treaty-de-ca/README.md` + a §"Treaty helpers" entry in the kernel README's coverage table. |
| A.3.c — reverse direction (DE resident receives CA dividend) | P2 | Not implemented; not in Christian's scenario. The shape is symmetric and could be added when needed. Out of scope for v0.1. |

### A.4 Multi-year continuity — **1 P0, 0 P1, 1 P2**

| Tag | Severity | Status |
|---|---|---|
| A.4.a — bitemporal `as-of-valid` defaults to `nil` (= "all valid time") | P0 | **FIXED** — I-17 closed in `fd3027c`; `christian_scenario_test/ug-trial-balance-balanced-and-includes-future-postings` is the regression. The 2026-12-31 dividend declaration + 2027-01-15 payment both appear in the default TB. |
| A.4.b — close FY 2026 + open FY 2027 + rollover retained earnings | **P0** | **PARTIALLY MISSING** — `kontor.l10n-de.closing/close-fiscal-year!` exists; opens FY 2027 with rolled-forward equity. **No integration test exercises the full close → reopen → comparative TB in Christian's setup.** Showcase 06 (multi-year DE GmbH) demonstrates the bitemporal-correction pattern but does NOT exercise FY rollover for Christian's specific case. **Cost: ~4-6 hours** to write `christian_scenario_test/multi-year-rollover-2026-to-2027` that closes 2026 + opens 2027 + shows comparative GuV. |
| A.4.c — bitemporal correction story (note 161 didn't exercise) | P2 | Showcase 06 demonstrates the pattern on a synthetic Acme GmbH; the same machinery applies to Hans-Tech UG. Not a regression but worth ~2 hours to demonstrate on Christian's scenario for evaluator confidence. |

### A-side summary

**4 P0** (3 already fixed; 1 outstanding: A.2.b sole-prop → T1
composition test, plus A.4.b rollover test as a soft-P0).
**3 P1** (1 fixed: A.1.c; 2 outstanding: A.2.c GST return composition,
A.3.b treaty helper discoverability).
**5 P2** (3 fixed; 2 outstanding: A.2.d multi-province slice, A.4.c
bitemporal correction on Christian's setup).

**Bottom line**: Christian's scenario is **80% there for FY 2026
real-world use**. The DE UG + cross-border dividend halves are
regression-tested. The CA sole-prop → T1 fold needs ~3-6 hours of
integration test work to be honestly shippable. Multi-year rollover
needs a similar test. None of these are substrate gaps — just the
test surface that pins them.

---

## §3. Gap inventory — B side (public GitHub release)

### B.1 — README

**Status**: PRESENT, materially above bar.

`README.md` is 316 lines, well-structured. Has tagline + bitemporal
hook + Try-It-in-60-seconds + What's-in-the-box + non-goals + country
coverage matrix + substrate seams + status + license + contributing.
The narrative quality is high — the showcase 05 (Apple 10-K/A) and
showcase 06 (multi-year GmbH) framing actually sells the bitemporal
headline.

**Two small gaps**:
1. **B.1.a (P1)** — the §"Try it in 60 seconds" snippet (README:64-104)
   will fail on a fresh clone because of the `:local/root` deps. If
   B.2 is fixed, this paragraph becomes accurate. If not, it's
   actively misleading.
2. **B.1.b (P2)** — the README cites ~98 ADRs ("98 ADRs total; 2274
   tests / 8905 assertions" in §Status) but the actual count is 107
   ADRs + 3050 tests / 11,683 assertions as of `c1143fb`. Numbers
   have drifted ~2 weeks behind. **Cost: 1 minute** to update.

### B.2 — Working REPL in 5 minutes

**Status: MISSING — this is the single most-blocking release item.**

`deps.edn:65` and `:69` pin two deps to `:local/root` paths that only
exist on the maintainer's machine:

```
org.replikativ/datahike   {:local/root "../datahike-bitemporal-v1"}
io.datopia/invariant      {:local/root "../datopia/invariant"}
```

A fresh `git clone git@github.com:replikativ/kontor.git && bb nrepl`
fails immediately. Comments in deps.edn acknowledge both as "switch
to :mvn/version once the release lands." The release hasn't landed.

**Three remediation options**:

1. **Ship as `:git/url + :sha`** to a public branch / tag on the
   datahike + invariant repos. Both projects are public. Requires:
   tag a branch on each, update the deps.edn. **Cost: ~1-2 hours**
   (assuming the branches are already pushed; otherwise add ~30
   minutes per repo to push the branch).
2. **Cut and publish a Clojars release** of datahike (the
   bitemporal-v1 branch) and datopia/invariant. **Cost: ~half-day
   to a day** (Clojars + signing + version bumps).
3. **Cherry-pick the bitemporal patches into a vendored
   subdirectory inside kontor**. Hostile to the upstream story and
   makes future updates painful. NOT recommended.

Option 1 is the path of least resistance for a v0.1.0-alpha. The
README already has the "switch to :mvn/version once the next release
ships" comment in place; the alpha can ship as `:git/url + :sha` and
the v0.1.0 (no alpha suffix) can ship once datahike's PR #828 lands
on master.

There are also two ALIAS-level `:local/root` deps (`:pg-server` →
`org.replikativ/pg-datahike`; `:notebooks` → `org.scicloj/clay`) but
these are dev-only and don't block `bb test`. They should be
documented as "requires sibling clones" but aren't release-blocking.

### B.3 — `bb ci` passes

**Status: PASSES INTERNALLY, BLOCKED EXTERNALLY by B.2.**

3050 tests / 11,683 assertions / 0 failures as of `c1143fb`. The CI
pipeline (`bb ci` = format + lint + test) is correctly wired in
`bb.edn:24-26`. Once the `:local/root` deps issue is closed, `bb ci`
runs externally.

**Two adjacent gaps**:
1. **B.3.a (P1)** — no `.github/workflows/ci.yml` runs `bb ci` on
   push to `main` and on PRs. GitHub Actions for Clojure projects is
   ~30 lines of YAML; absence here means a contributor PR has no
   visible green-check. **Cost: ~1 hour** to write + verify.
2. **B.3.b (P2)** — `bb ci` takes ≈ 4m39s per memory note "test-speed
   audit"; this is fine for OSS scale but should be noted in
   CONTRIBUTING ("expect ~5min for a full pass; use REPL for inner
   loop"). Not a release blocker.

### B.4 — File issue or PR

**Status: MISSING entirely.**

- No `CONTRIBUTING.md`. The README §"Contributing" (one paragraph)
  points at `doc/roadmap.md` and `CLAUDE.md`. CLAUDE.md is the AI
  coding agent's brief — useful but pitched at an LLM, not a human
  contributor.
- No `.github/ISSUE_TEMPLATE/` (bug / feature templates).
- No `.github/PULL_REQUEST_TEMPLATE.md`.
- No `.github/CODEOWNERS`.
- No `CODE_OF_CONDUCT.md`.

**Cost**: ~half-day to write a competent `CONTRIBUTING.md` (covers:
test-first iteration loop, ADR convention, REPL inner loop, how to
add an l10n module, license signing if needed); ~30 minutes for
issue/PR templates; ~15 minutes for a CoC (use Contributor Covenant
2.1 verbatim).

**Severity P1** — not technically blocking the release, but absence
materially reduces the quality of feedback received. A drive-by
contributor without a template will not write a useful bug report;
they'll close the tab.

### B.5 — Understand design choices

**Status: PRESENT but unwieldy.**

`doc/decisions.md` is 10,123 lines, 107 ADRs. Browseable by Ctrl-F
or by the rough chronological ordering. **No top-level table of
contents**, no per-ADR markdown anchor index.

**Two gaps**:
1. **B.5.a (P1)** — add a TOC at the top of `doc/decisions.md`
   listing all ADRs by number + title with `#adr-NNN` anchors. ~15
   minutes if scripted; ~1 hour if hand-written. The same TOC can
   be the index README.md points at (currently README.md says "94
   ADRs"; that count is also stale).
2. **B.5.b (P2)** — group ADRs by stage (Phase 1, Stage J, Stage R,
   etc.) for new readers. The current chronological + interleaved
   layout requires the reader to know which numbers belong to which
   substrate area. ~3 hours.

### B.6 — Find an end-to-end example

**Status: PRESENT but rough.**

Six showcases exist (`doc/showcases/01..06`). The Quarto HTML
renders are in `docs/showcases.*.html` (already built; ~7 files).
README points at 05 (Apple) + 06 (multi-year GmbH) as the bitemporal
headline.

**Three gaps**:
1. **B.6.a (P1)** — none of the showcase HTMLs is hosted (no
   `gh-pages`, no link from README to the rendered HTML). A reader
   has to render locally with `clojure -M:notebooks:dev` which
   transitively requires the `:local/root "../clay"` dep. **Cost:
   ~2 hours** to push the existing `docs/` directory to a `gh-pages`
   branch or to add a GitHub Pages workflow.
2. **B.6.b (P2)** — showcase 06 (the canonical bitemporal demo) is
   pitched at someone who already knows DATEV / SKR04 / DSGVO. A
   simpler "first showcase" that uses only the kernel + `kontor.book`
   (no l10n) would be a better B.2 follow-up than asking a stranger
   to install the DATEV companion. The existing
   `doc/quickstart.md` is close but doesn't include the
   "(d/valid-at db t)" bitemporal headline shot.
3. **B.6.c (P2)** — the `christian_scenario_test` (a real two-DB
   end-to-end with worked numbers) is THE most compelling demo for
   a finance evaluator but lives only as a test, not as a rendered
   notebook or a README link. Promote it: showcase 07 (or a
   doc/walkthroughs/christian-scenario.md) would punch above its
   weight. **Cost: ~half-day.**

### B-side summary

**3 P0** (B.2 `:local/root` deps; and two soft-P0s in B.4 and B.5
that are P1 by category but become P0 if you want feedback quality
above noise).
**6 P1** (B.1.a stale snippet; B.3.a no CI workflow; B.4 missing
CONTRIBUTING; B.5.a no ADR TOC; B.6.a showcases unhosted; B.6.c
Christian scenario not promoted).
**7 P2** (B.1.b stale numbers; B.3.b ci slowness disclosure;
B.4 issue/PR templates; B.4 CoC; B.5.b ADR grouping; B.6.b first
showcase friction; CONTRIBUTING-l10n recipe).

---

## §4. License + dependency hygiene

### License posture: clean and well-documented

- Kernel: EPL-1.0. Confirmed in `LICENSE` (Eclipse Public License v1
  full text) and `deps.edn` header comment.
- README §License documents the per-l10n license carve-out: "some
  charts are GPLv3 per Tryton / GnuCash provenance — each module's
  README documents its terms." The `modules/l10n-de/resources/.../
  skr04.edn` header comment explicitly states "Tryton's
  account_de_skr03 was cross-referenced for structure ... we have
  NOT lifted any of its EDN/XML verbatim — only the conventional
  Konto numbers + names (factual data, not copyrightable in EU)."
  That's the right posture and is well-documented.
- The `CLAUDE.md` "What NOT to do" rules ("Don't translate Odoo
  Python"; "Don't lift Tryton code"; "Don't bundle Avalara API keys")
  are followed in spirit and in code: `grep -lR "Avalara\|TaxJar.*key"
  modules` returns zero rate-data hits; only protocol scaffolds.

### Dependency closure: 7 direct deps, all OSS-compatible

`deps.edn` lists 7 direct deps (kernel-required) + 4 alias-only:

| Dep | Version | License | Notes |
|---|---|---|---|
| `org.clojure/clojure` | 1.12.4 | EPL-1.0 | Fine |
| `org.replikativ/datahike` | **`:local/root`** | EPL-1.0 | **BLOCKER** — pinned to `../datahike-bitemporal-v1` |
| `io.datopia/invariant` | **`:local/root`** | (assumed EPL) | **BLOCKER** — pinned to `../datopia/invariant` |
| `instaparse/instaparse` | 1.5.0 | EPL-1.0 | Fine (Beancount parser) |
| `org.clojure/data.csv` | 1.1.0 | EPL-1.0 | Fine (bank-de) |
| `org.clojure/data.xml` | 0.2.0-alpha9 | EPL-1.0 | Fine (CRA T4 XML) |
| `org.clojure/data.json` | 2.5.0 | EPL-1.0 | Fine (IN IRN payload) |
| `org.mustangproject/library` | 2.17.0 | Apache-2.0 | Compatible (Factur-X). Heavy (iText, PDFBox, XOM transitive); kernel-only consumers can drop. |

**Alias-only `:local/root`**:
- `:pg-server` → `org.replikativ/pg-datahike` (`:local/root`)
- `:notebooks` → `org.scicloj/clay` (`:local/root`)

Both are dev-only; don't block `bb test`. Document as "optional
sibling-clone deps for advanced workflows" in CONTRIBUTING.

### Per-module deps.edn

**Finding**: `find modules -name deps.edn` returns ZERO results.
Companion modules do NOT have their own `deps.edn` — they're paths in
the kernel's `deps.edn` `:paths`. This is fine for monorepo
development per ADR-006's "split when consumers need it" rule, but
**any external consumer who wants only `kontor-l10n-de` without the
other 10 jurisdictions is currently forced to pull the entire
monorepo**.

For the v0.1.0-alpha this is acceptable; the README already says
"Pull only the modules whose terms you accept" but the mechanism for
doing so doesn't exist yet. ADR-006 anticipated this; the migration
("split into its own artifact without code changes") hasn't been
needed yet and isn't release-blocking.

### LICENSE per module

**Finding**: ZERO `LICENSE` files inside `modules/`. The kernel
`LICENSE` is canonical for the whole monorepo. Per README, "each
`modules/<name>/` directory documents its license" — but the
documentation lives in the module's README (where present) or in a
namespace docstring or in a chart-EDN header comment. There's no
machine-readable per-module SPDX manifest.

**Recommendation**: not release-blocking for v0.1.0-alpha. For v0.2,
add `modules/<name>/LICENSE` symlinks (or a SPDX manifest line in
each module README) so a license audit tool picks them up. ~30
minutes per module × 35 modules = ~half-day.

### Borrowed-code audit: clean

`grep -rEni "copy from|copied from|adapted from|verbatim" doc/ src/
modules/ --include="*.clj" --include="*.edn"` finds two hits — both
the SKR04 attribution above (which explicitly says "NOT lifted
verbatim"). The "What NOT to do" rules are observed in practice.

---

## §5. Documentation gap

### Public-facing

| Doc | Status | Notes |
|---|---|---|
| `README.md` | Strong | 316 LOC; well-structured; minor stale numbers |
| `LICENSE` | Present | EPL-1.0 full text |
| `doc/quickstart.md` | Present | 146 LOC; covers `kontor.book` verbs; works once `:local/root` is fixed |
| `doc/start-here.md` | Present | 222 LOC; the 10-minute walkthrough of showcase 06; well-pitched |
| `doc/value.md` | Strong | 586 LOC; the evaluator pitch; one of the best docs in the repo |
| `doc/programming.md` | Strong | 740 LOC; the developer's three-axis model |
| `doc/accounting-model.md` | Present | 151 LOC; bridge between verbs and debits/credits |
| `doc/architecture.md` | Present | 513 LOC; layer cake + namespace map |
| `doc/decisions.md` | Bulky | 10,123 LOC / 107 ADRs; needs a TOC |
| `doc/roadmap.md` | Stale | 303 LOC; phase headings are from Phase 1-5 era; doesn't reflect the tax-completion arc or note 99/104/161 work |
| `doc/conventions.md` | Thin | 189 LOC; doesn't yet cover everything note 160 has decided (e.g. F11 `:through`, F10 `:posting/entity`) |
| `doc/handoff_de_ca_compliance.md` | Unclear purpose | Title suggests internal handoff doc; not linked from README; possibly publishable, possibly internal-only |
| `CONTRIBUTING.md` | **MISSING** | See B.4 |
| `CODE_OF_CONDUCT.md` | **MISSING** | See B.4 |
| `CHANGELOG.md` | **MISSING** | A `0.1.0-alpha` release implies a changelog entry |

### Developer-facing

| Doc | Status |
|---|---|
| `CLAUDE.md` | Strong for AI assistants; useful for humans too; doesn't substitute for CONTRIBUTING |
| `doc/research/00-index.md` | Present; 78 entries listed (now 168); needs refresh |
| ADR cross-references in code | Present per ADR-068 convention (`grep -rEn "ADR-[0-9]+" src/` returns ~200 references) — good practice in place |

### Per-companion READMEs

`find modules -maxdepth 2 -name README.md`: **14 of 46 modules** have
READMEs. Specifically:

| Has README | Doesn't have README |
|---|---|
| asset, authz, collections, commitment, expense, hr, import-edgar, import-gleif, inventory, invoice, lease, partner, people-record, modules/ (the parent) | bank-{at,ca,de,fr,us} (5), disposal, einvoice-de, l10n-{at,au,br,ca,cn,de,fr,in,jp,mx,uk,us} (12), payroll-{at,au,br,ca,cn,de-datev,fr,in,jp,mx,us-adp} (11), procurement, sales, treaty-de-ca |

**32 modules lack a README.** The companion accounting modules
(asset, lease, inventory, etc.) have them; the country-specific
l10n, payroll, bank, and treaty modules don't.

This is the largest documentation gap. For v0.1.0-alpha, the
**most-leveraged** subset to write:

1. **`modules/l10n-de/README.md`** — DE is the reference l10n; if any
   consumer reads one l10n README it'll be this one.
2. **`modules/l10n-ca/README.md`** — Canada is the second-most-built
   l10n and the one Christian uses.
3. **`modules/treaty-de-ca/README.md`** — the only treaty pair so
   far; the README sets the pattern for future pairs.
4. **`modules/payroll-de-datev/README.md`** — DATEV is the reference
   payroll adapter (showcase 06 uses it).
5. **`modules/disposal/README.md`** — kernel-tier companion for
   capital gains; no README is suspicious.

The other 27 can land in v0.2 with a CONTRIBUTING-driven template.

### ADR-101 "how to add a new jurisdiction's statute"

**Status: implicit, not documented.**

The pattern is clear from reading the existing 6 CIT statutes
(DE/FR/CA/JP/BR/IN) + 11 CGT statutes — they all follow the same
shape. But there's **no doc that explicitly says** "here's how to add
KR (Korea) CIT: write `cit_statute.clj` with N parameters + M
provisions, write `cit_provider.clj` as a thin
PeriodTaxProvider, write `cit_provider_test.clj` against a worked
example from the National Tax Service."

The closest thing is research notes 108-115 (the CIT + CGT
fit-analyses), which are pitched at the maintainer, not at an OSS
contributor.

**Cost: ~half-day** to write `doc/adding-a-jurisdiction.md` (or
extend `doc/programming.md` with an §11) walking through a concrete
"build PT (Portugal) CIT" recipe.

### Five highest-leverage doc gaps to close for v0.1.0-alpha

In rank order:

1. **`CONTRIBUTING.md`** (B.4)
2. **`modules/l10n-de/README.md`** (sets the l10n pattern)
3. **ADR TOC at the top of `doc/decisions.md`** (B.5.a)
4. **`modules/treaty-de-ca/README.md`** (A.3.b discoverability)
5. **`doc/adding-a-jurisdiction.md`** (community contribution path)

The other 27 missing READMEs, the CoC, the issue templates, the ADR
grouping, and the showcase HTML hosting are v0.2 work.

---

## §6. The "v1.0.0 vs v0.1.0" call

**Recommendation: `v0.1.0-alpha`.**

Three reasons in rank order:

1. **API stability is genuinely alpha.** Note 160 is an active log
   with 19 open inconsistencies (I-1 through I-19, with 4 fixed and
   15 open across the P0/P1/P2 ladder). The post-Stage-P ADR-068
   builder/wrapper convention is stable, but the rim — `kontor.book`
   verb shape (I-4 dual-input shape), `:account/code` vs
   `:account/path` lookup-ref discipline (I-3), provider record name
   drift (S5 in note 168) — is still evolving. A SemVer v1.0.0
   commits to breaking-changes-warrant-major-bump; we'll bump major
   3-4 times in the next 6 months at the current rate of API
   discovery. Better to label the alpha honestly and let the major
   bumps happen pre-v1.0.

2. **Test coverage is substrate-rich, consumer-scenario-thin.** 3050
   tests / 11,683 assertions sounds huge, but the breakdown (note 168
   §4) shows:
   - Per-jurisdiction depth varies 10× (UK 36 deftests, CA 205).
   - Cross-jurisdiction integration tests are sparse:
     `cgt_pit_integration_test.clj` (US-only CGT+PIT),
     `stage_r_cross_stage_test.clj` (Jane Doe payroll across 3
     jurisdictions), `integration/christian_scenario_test.clj` (DE
     UG + CA personal, just promoted to a regression).
   - The note-168 §1.1 verdict line: "structurally complete,
     operationally incomplete" — the substrate works, the breadth of
     consumer scenarios that have been exercised end-to-end is
     narrow. v1.0 would imply "we've validated every claim with a
     consumer scenario"; we haven't.

3. **Schema stability is mostly there, but the dust hasn't settled.**
   ADR-097 (`:posting-dimension`) landed 2026-05-20. ADR-099 +
   ADR-100 + ADR-101 (the tax-completion arc) landed in the same
   week. ADR-103's `DisposalSource` protocol just shipped. Schema
   doesn't break weekly anymore — but the substrate has been moving
   fast through April-May 2026 and "let's freeze and see what
   breaks" is healthier than "let's stamp v1.0.0 and pretend it's
   done."

**`v0.1.0-alpha` says all of this honestly**: "the substrate works,
the API will change before v1.0, please give feedback." It is the
right semantic for what kontor is today.

`v0.1.0` (no -alpha) implies "loose stability — minor bumps for
breaking changes are okay, but you can build on it." This is the
right tag for ~3-4 months out, after the alpha collects feedback
and notes 169 + 170 land their API consolidation pass.

`v1.0.0` is the wrong call for at least the next 12 months. The
McComb-aligned substrate seams (ADR-090/091/092) are barely
exercised; the fiscal-unit substrate (Gap #8, note 167) is designed
but not coded; the consumer apps (beleg, simmis) aren't yet
integrated. SemVer v1.0 with the substrate not yet powering a
production consumer is premature.

---

## §7. The release-ready punch list

Each item: subject + estimated effort + dependency on other items.
Tiers ranked by what gates the release.

### Tier 0 — must close before `git push origin v0.1.0-alpha`

| # | Subject | Effort | Depends on |
|---|---|---|---|
| **T0.1** | **Fix `:local/root` deps**: tag the `bitemporal-v1` branch on `replikativ/datahike` and the working branch on `datopia/invariant`; update `deps.edn` to `:git/url + :sha`. Verify `bb test` passes after a fresh clone in a scratch directory. | **~2-4 hours** | — |
| **T0.2** | **Write `CONTRIBUTING.md`** covering: iteration loop, REPL inner loop (clj-nrepl-eval), test-first convention, ADR convention, how to file an issue (link to template), how to propose a new l10n module (link to T0.5). | **~half-day** | — |
| **T0.3** | **Add `.github/workflows/ci.yml`** running `bb ci` on push to main + PRs against main. Verify it green-checks on a real PR. | **~1-2 hours** | T0.1 |
| **T0.4** | **Add ADR TOC at top of `doc/decisions.md`** — list all 107 ADRs with `#adr-NNN` anchors + 1-line summary. Update README's stale ADR count + test count. | **~1 hour** (scripted from grep) | — |
| **T0.5** | **Write `doc/adding-a-jurisdiction.md`** — concrete recipe for adding a new country (statute + provider + test + preset + chart + README). Reference the DE-CIT or BR-CIT implementation as the template. | **~half-day** | — |
| **T0.6** | **Write `modules/l10n-de/README.md`** — sets the l10n pattern; covers chart + tax + retention + invoice + DATEV + closing. | **~3 hours** | — |
| **T0.7** | **Write `modules/treaty-de-ca/README.md`** — explains the treaty-helper companion pattern; references note 161 §7 + 168 §1. | **~1-2 hours** | — |
| **T0.8** | **Validate Christian's scenario end-to-end** — close the A.2.b (sole-prop → T1 composition test) and A.4.b (2026 → 2027 rollover) gaps as regression tests in `christian_scenario_test`. | **~half-day to full day** | T0.1 |

**Total Tier-0 estimate: 3-5 days of focused work** (1 person,
single track). T0.1 + T0.2 + T0.3 + T0.4 can land in parallel.

### Tier 1 — should close, significantly affects feedback quality

| # | Subject | Effort |
|---|---|---|
| T1.1 | Issue + PR templates (`.github/ISSUE_TEMPLATE/{bug,feature,question}.md` + `PULL_REQUEST_TEMPLATE.md`) | ~1 hour |
| T1.2 | Code of Conduct (Contributor Covenant 2.1 verbatim) | ~15 min |
| T1.3 | Per-module READMEs for the 4 next-most-important modules: l10n-ca, disposal, payroll-de-datev, einvoice-de | ~2-3 hours total |
| T1.4 | Promote `christian_scenario_test` to a rendered walkthrough at `doc/walkthroughs/christian-scenario.md` (or showcase 07) | ~half-day |
| T1.5 | Host the existing `docs/showcases.*.html` Quarto renders on GitHub Pages (`gh-pages` branch); link from README | ~2 hours |
| T1.6 | `CHANGELOG.md` initialized with the v0.1.0-alpha entry summarising the ADR-001 through ADR-107 arc | ~2 hours |
| T1.7 | Update `doc/roadmap.md` to reflect 2026-05 reality (the tax-completion arc, the McComb seams, the FX substrate) — phase headings are stale | ~half-day |
| T1.8 | Add `doc/conventions.md` updates for F10 (`:posting/entity`), F11 (`:through`), I-15 (per-posting `:partner`), I-2 (commodity coercion) | ~1-2 hours |

**Total Tier-1 estimate: 2-3 days of focused work.** Can land
post-v0.1.0-alpha as point releases (v0.1.1, v0.1.2) or batched into
the v0.1.0 (no alpha) drop ~3-4 months later.

### Tier 2 — nice, doesn't gate release but raises the floor

| # | Subject | Effort |
|---|---|---|
| T2.1 | READMEs for the remaining ~27 modules using a CONTRIBUTING-driven template | ~3-5 hours |
| T2.2 | ADR grouping in `doc/decisions.md` by stage (Phase 1, Stage J, ...) for new readers | ~3 hours |
| T2.3 | A simpler "first showcase" using only kernel + `kontor.book` (no l10n dep), pitched at someone who's never seen kontor | ~half-day |
| T2.4 | SPDX manifest lines per module (machine-readable license audit) | ~half-day |
| T2.5 | Address note 160's remaining 15 open inconsistencies before v0.1.0 (no alpha) | ~1-2 weeks of API consolidation |
| T2.6 | Per-companion `deps.edn` extraction (ADR-006 follow-through) so consumers can pull a single l10n module without the monorepo | ~1 week |
| T2.7 | Cross-jurisdiction regression test (3+ providers like Jane-Doe but with tax) | ~half-day |
| T2.8 | Property/wealth tax research note + ADR (note 168 §6 W2.8) | ~1 week (research + decision) |

---

## §8. The 80/20 release plan

Ship Tier 0 in the next 3-5 working days. That is the 20% of work
that unlocks 80% of the feedback potential.

**Concrete sequence** (in dependency order):

1. **Day 1 morning**: T0.1 (fix `:local/root` deps). Verify on a
   scratch clone. This unblocks T0.3 + T0.8.
2. **Day 1 afternoon**: T0.4 (ADR TOC) + T0.2 (CONTRIBUTING.md) in
   parallel. Both are documentation-only; no code or test interaction.
3. **Day 2 morning**: T0.3 (`.github/workflows/ci.yml`) — depends on
   T0.1 being green externally.
4. **Day 2 afternoon**: T0.5 (`adding-a-jurisdiction.md`) + T0.6
   (`modules/l10n-de/README.md`) in parallel.
5. **Day 3 morning**: T0.7 (`treaty-de-ca/README.md`) + T0.8 (Christian
   regression tests for A.2.b + A.4.b).
6. **Day 3 afternoon**: smoke-test the whole release on a scratch
   clone: `git clone → bb ci → bb nrepl → run quickstart.md →
   run christian_scenario_test → render showcase 06`. Fix anything
   that breaks.
7. **Day 4**: cut `v0.1.0-alpha` tag. `git push origin v0.1.0-alpha`.
8. **Day 4 afternoon onwards**: post the announcement (Clojurians
   #kontor / #replikativ / #accounting / Hacker News if you want
   the reach + can stomach the comments / dev.to / r/Clojure).
   Include: 60-second README, link to showcase 06 + Apple 10-K
   showcase, link to `doc/value.md`, ASK for feedback explicitly
   ("we want to know: is the substrate right? is the API right? is
   the country we don't cover yet the one you need?").

Then collect feedback for **4-6 weeks**. Filter for:

- **Build failures on `git clone`** (we'll have missed something).
- **"How do I do X?" questions** that the docs should have answered
  → batch into a docs PR.
- **"Why is the API like Y?"** questions that surface a bad API
  choice → batch into note 160 + a Tier-2 API consolidation pass.
- **"My country isn't covered"** requests → triage; if 2+ requests
  for the same country, that's a v0.2 l10n target.
- **"I want to use this for X consumer scenario"** → these are gold.
  Each one is a candidate integration test (à la
  `christian_scenario_test`) that pins what works.

After the 4-6 week feedback window, plan v0.1.0 (no alpha) based on
what came back. Tier 1 + Tier 2 items get prioritised against the
real feedback signal rather than this speculative ordering.

**The bias** here is "ship a thin v0.1.0-alpha + iterate" over "ship
a perfect v1.0.0 + freeze." kontor is a substrate for a small but
serious community (Clojure devs + accounting practitioners + OSS
contributors); the right feedback loop is fast iteration with
honest version semantics. The maintainer (you) has been building in
isolation for months; the next month should be about turning that
isolation into a community that helps shape v0.2.

---

## §9. Honest scorecard

**What's right today**:
- Substrate is technically sound. 11,683 assertions and the
  bitemporal headline isn't a marketing claim — showcase 05 actually
  ingests Apple's real 10-K + 10-K/A from SEC EDGAR and returns
  the right number for `(d/valid-at db t)` at every point on the
  timeline.
- The documentation strata are well-pitched. README → start-here →
  value → programming is a clean on-ramp ladder.
- The Christian scenario is a regression test, not a one-shot
  REPL transcript. That's substantially more honest than most OSS
  projects' "look, it works on my machine" demo.
- ADR discipline is real. 107 numbered decisions with rationale +
  alternatives is the kind of paper trail that lets a contributor
  understand the substrate's shape without spelunking commits.

**What's wrong today**:
- `:local/root` deps in `deps.edn` make the project unbuildable
  for anyone but the maintainer. This is a stop-the-world bug for
  a public release.
- No CONTRIBUTING.md, no .github/, no CI workflow. A drive-by
  contributor will not file a useful bug report and an issue tracker
  with no labels + no template will fill with noise quickly.
- The ADR list is unbrowseable without a TOC.
- 32 of 46 modules have no README.

**What's good enough to ship and iterate**:
- 15 open inconsistencies in note 160 → fine for an alpha; the
  community will surface the order-of-importance.
- Per-jurisdiction depth varies 10× → fine; document the variance,
  prioritise based on feedback.
- 5 of 12 CIT providers still record-shape → fine; note 168 §6
  Tier 2 (Gap #5) is the post-alpha migration path.
- Property tax not modelled in 11 of 12 jurisdictions → fine; note
  168 §6 W2.8 is the post-alpha decision.
- Fiscal-unit / group tax substrate designed but not coded → fine;
  v0.2 work.

**The 80/20**: the substrate is correct. The publishability is
fixable in 3-5 days. The community will tell you what to do next.
Ship `v0.1.0-alpha`, collect feedback, plan v0.2 based on signal,
not guess. The single most important next action is **T0.1 —
fix the `:local/root` deps**. Without that, none of the rest
matters.
