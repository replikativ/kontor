# Research note 160 — API consistency audit (running log)

A living catalog of every API/naming/convention inconsistency found across
kontor's surface. Goal: turn an opportunistic substrate into a **regular**
one — same shapes for same concepts, same names for same things, same
failure modes for the same kinds of error.

**How to read this:** every entry has a status (`OPEN | DECIDED | FIXED`),
a severity (`P0` correctness / `P1` DX-blocker / `P2` polish), a citation
(file:line where possible), and a proposed direction.

**How to update:** add new findings as they surface during real-use REPL
exploration, code review, or test failures. When fixing, append `→ FIXED
in <commit>` to the entry rather than deleting — the history is useful for
designing the next set of conventions.

---

## §1 — Naming inconsistencies

### I-1 — Stable-identifier attr differs across substrate entities
**Status**: OPEN · **Severity**: P2 · **Surfaced**: note 159 §F1

- `:partner/external-id` — primary stable identifier on partners
- `:entity/code`         — stable identifier on entities (no `external-id`)
- `:entity/source-id`    — origin-record id (different concept)
- `:journal/code`        — stable identifier on journals
- `:account/code`        — SKR-style numeric code (NOT a primary lookup!)
- `:account/path`        — actual unique lookup-ref (PTA-style hierarchical)
- `:asset/external-id`   — on kontor-asset companion
- `:lease/external-id`   — on kontor-lease companion
- `:commodity/symbol`    — ISO-4217 code, unique identity
- `:disposal/external-id` — on kontor-disposal

A consumer reading the schema namespace-by-namespace cannot infer the
pattern; six different attrs serve essentially the same role.

**Proposed direction**: pick one (`:*/external-id` or `:*/code`) and add
companions as aliases for back-compat over a deprecation window.
Recommend `:*/code` for short stable identifiers, `:*/external-id` for
opaque consumer-supplied keys. Document in `doc/conventions.md`.

### I-2 — `:posting/commodity` ref takes a lookup-ref or eid, not a bare keyword
**Status**: FIXED · **Severity**: P1 · **Surfaced**: note 159 §F6

`:commodity :EUR` in `kontor.book` verbs threw `entid-strict: Nothing
found`. Required `[:commodity/symbol "EUR"]` (or an eid). Test fixtures
universally defined a top-level var:
```clojure
(def ^:private eur [:commodity/symbol "EUR"])
```

**Fixed**: `kontor.book` verbs now auto-coerce via `->commodity-ref`:
bare keyword (`:EUR`), short string (`"EUR"`), lookup-ref, or eid all
accepted in both `:commodity` (entry-level default) and `:commodity`
on individual postings. Test: `book-test/I-2 regression …`.

### I-3 — `:account/code` not unique; `:account/path` IS
**Status**: OPEN · **Severity**: P1 · **Surfaced**: note 159 §F5

`:account/code` says "Optional country-specific code (SKR03 \"1200\", QBO
\"1010\"). Indexed for prefix-rollup queries." — no warning that it's NOT
a lookup-ref. `:account/path` is `:db.unique/identity` and is the canonical
lookup. A consumer trying `[:account/code "1200"]` for a verb's
`:debit-account` gets a cryptic datahike error.

**Proposed direction**: docstring warning + `doc/conventions.md` section.
Possibly tighten `:account/code` to be unique-per-entity (scoped by
`:account/entity` ref) once entities are wired in.

---

## §2 — kontor.book verb shape

### I-4 — Two parallel input shapes for the same operation
**Status**: OPEN · **Severity**: P2 · **Surfaced**: REPL exploration 2026-05-25

`kontor.book/entry!` accepts EITHER:
- `:debit-account` + `:credit-account` + `:amount` + `:commodity` (the
  2-leg convenience), OR
- `:postings [{:account :amount :commodity? :dimensions?}]` (the n-leg
  builder)

The keys differ: `:account` inside `:postings` but `:debit-account` /
`:credit-account` at the top level. A consumer who reads the postings
shape then tries `:account` at the top level gets `:debit-account is
required`.

**Proposed direction**: unify to a single shape — always `:postings`.
Keep the 2-leg convenience as `:debit` / `:credit` (no `-account`
suffix) at the top level for a moment, but document `:postings` as the
canonical builder.

### I-5 — `Money` records fail opaquely
**Status**: OPEN · **Severity**: P1 · **Surfaced**: note 159 §F4

`(money/money 50000M :EUR)` is the canonical Money record; passing it to
a book verb's `:amount` throws `No matching ctor found for class
java.math.BigDecimal`. Consumers must pre-extract `(money/amount …)`.

**Proposed direction**: `kontor.book/->bigdec` recognises `Money` and
unwraps; the entry-level `:commodity` defaults from `(money/commodity x)`
when missing.

### I-6 — Verbs don't accept `:entity` for per-entity reports
**Status**: IN-FLIGHT (Phase A3, task 338) · **Severity**: P1 · **Surfaced**: note 159 §F10

Postings are stamped via `kontor.book` verbs but never carry a `:posting/
entity` ref because the verbs don't accept `:entity` (or `:postings
[{:entity …}]`). Trial-balance / BS / GuV with `:entity` filter return
empty for the user's actual entity.

**Proposed direction (Phase A3)**: accept `:entity` at the top level (one
ref applied to every posting in the entry) AND per-posting via
`:postings [{:entity …}]` (the n-entity carve-out for cross-entity
intercompany entries). Both stamp `:posting/entity`.

---

## §3 — Install / bootstrap surface

### I-7 — No default journals in `core/create-test-db`
**Status**: OPEN · **Severity**: P2 · **Surfaced**: note 159 §F3

Every consumer hits `kontor.book: no :journal of type :general in the db
— create one, or pass :journal explicitly` on their first verb call.

**Proposed direction**: ship `(install-default-journals! conn)` that
adds GJ / CR / CD as defaults; `create-test-db` calls it. Document
journal codes per jurisdiction (some prefer GEN/CASH, some GJ/CR).

### I-8 — Multi-step prerequisite-aware install dance
**Status**: PARTIAL-FIX (A2 closed F8; Phase B addresses the broader pattern) · **Severity**: P1 · **Surfaced**: note 159 §F9

DE example: CIT statute → CGT statute → IC install-statute → SKR04 →
journals → commodity. 6 calls in the right order to get a working DE
setup. Failure modes are silent (F8) or cryptic.

**Proposed direction**: `kontor.preset.de/install-all! conn` —
one-shot installer. Same for each of the 11 jurisdictions. Documented
in `doc/getting-started-{de,ca,us,…}.md`.

### I-9 — Split install paths (FIXED for DE, audited elsewhere)
**Status**: FIXED (A2 commit 041a0fb for DE; 10 others verified clean) · **Severity**: P0 · **Surfaced**: note 159 §F8

---

## §4 — Bitemporal window semantics

### I-10 — `:to` in report windows is exclusive; not obvious
**Status**: OPEN (Phase A4, task 339) · **Severity**: P1 · **Surfaced**: REPL 2026-05-25

`(de-pnl/compute conn {:from #inst "2026-01-01" :to #inst "2026-12-31"})`
silently drops every posting effective Dec 31 — because `:to` is half-
open (exclusive end). The user must pass `:to #inst "2027-01-01"` to get
"the 2026 fiscal year". The off-by-one looks like real zero income.

The `compute-cash-flow` docstring says "between `:from` and `:to`"
without specifying open/closed — and the `report/in-window?` source
uses `(dec end)` to make `:to` exclusive: cleanly *implementing* the
half-open convention but not surfacing it to consumers.

**Proposed direction**: either:
1. Change to inclusive `:to` (idiomatic for "the FY ends Dec 31") and
   adjust the half-open math.
2. Keep half-open, but add `:through` as inclusive sugar
   (`{:from "2026-01-01" :through "2026-12-31"}` = `{:from … :to "2027-01-01"}`).
3. Loud-fail when `:to` is exactly midnight (probably user error) and
   emit a warning suggesting `:through` or +1 day.

Recommend (2) — it's additive, doesn't break the substrate, and matches
how legal periods are written ("FY 2026" = "through Dec 31, 2026").

---

## §5 — Provider record / constructor shapes

### I-11 — Constructor opts vary across providers
**Status**: OPEN · **Severity**: P2 · **Surfaced**: code-review 2026-05-25

Examples:
- `(de-inv/de-investment-income-provider {})` — no required args
- `(us-inv/us-investment-income-provider {:emit-niit? true})` — required-feeling flag
- `(jp-cit/jp-cit-provider {:source jp-source})` — required DisposalSource ref

Some providers register compute-fns on namespace load (DE/JP/CA/IN/US),
others don't (FR/UK/AT/AU/BR/MX/CN). Some expose `register!` as a public
fn, others as private.

**Proposed direction**: protocol-level convention. Every provider exposes:
- Constructor `<jur>-<concept>-provider [opts-map]`
- Implements `kontor.period-tax-provider/PeriodTaxProvider`
- Auto-registers compute-fns on namespace load (no manual `register!`)
- Required opts documented in the constructor's docstring's "Required:"
  section

---

## §6 — Status-machine + commitment

### I-12 — `commit/record-commitment!` requires status-transition schema seed
**Status**: FIXED · **Severity**: P1 · **Surfaced**: REPL 2026-05-25

First call to `commit/record-commitment!` after `core/create-test-db`
threw "Illegal status transition" because the per-companion status-
transition seeds weren't installed. The error pointed at status-
machine, not commitment — making the diagnosis costly.

**Fixed**: `record-commitment-tx-data` now pre-checks for the
`:status-transition/entity-type :commitment` seeds and throws a
targeted `kontor.commitment: status-transition seeds not found in
the DB. Did you call '(kontor.commitment/install! conn)'?` with
`{:hint :missing-status-transition-seeds}`.

---

## §7 — Findings from the two-DB scenario walk (Phase D, 2026-05-25)

### I-14 — `:entity/legal-form` is `string?`, not a keyword/enum
**Status**: OPEN · **Severity**: P2 · **Surfaced**: Phase D REPL

`(d/transact conn [{:entity/legal-form :ug-haftungsbeschränkt …}])` throws
schema validation. Must be a free-form string. Reasonable for v1 (allows
arbitrary jurisdictional types) but no enum-like attr enables consumers
to query "all UG/GmbH/AG/sole-prop/etc" across jurisdictions.

**Proposed direction**: keep `:entity/legal-form` as string for the free-
form description; add a `:entity/legal-form-kind` `:db.type/keyword` with
a closed enum (`:gmbh | :ug | :ag | :ohg | :kg | :sole-prop | :inc | :ccpc
| :llc | :s-corp | :partnership | …`) for queryable cross-jurisdiction
filtering. Optional, no breaking change.

### I-15 — Per-posting `:partner` silently dropped by `kontor.book` verbs
**Status**: OPEN · **Severity**: P1 · **Surfaced**: Phase D REPL

The transaction-level `:partner` is stamped on `:transaction/partner`.
But `:postings [{:account … :amount … :partner …}]` silently drops the
per-posting partner — `->posting` (book.clj) destructures only
`{:account :amount :commodity :entity :dimensions}`. Critical for
multi-shareholder dividend allocation (the EXACT case I hit): a 4-leg
"Cr Dividenden-Zahlbar 9000 (CW) / Cr Dividenden-Zahlbar 6000 (PB) /
Dr Gewinnvortrag 15000" entry — the per-shareholder allocation is lost
silently from the GL.

**Proposed direction**: extend `->posting` to also accept `:partner`
(symmetric with `:entity`). Document that per-posting `:partner`
overrides the entry-level one (same intercompany pattern).

### I-16 — F10 fix doesn't retro-apply (only NEW entries get :posting/entity)
**Status**: OPEN · **Severity**: P2 · **Surfaced**: Phase D REPL

Obvious in hindsight — postings transacted before the F10 fix carry no
`:posting/entity`. Per-entity trial-balance / reports filter them out.
The substrate has no "backfill entity" helper for existing data.

**Proposed direction**: ship `kontor.entity/backfill-postings!` —
takes `(conn entity-ref account-paths-to-claim)` and stamps
`:posting/entity` on matching pre-existing postings via `:db/add`
(bitemporally — keeps the original `:tx/valid-from`). Useful for
adopting consumers who started without entity refs.

### I-17 — `trial-balance` defaults `:as-of-valid` to wall-clock now;
silently drops future-dated postings.
**Status**: OPEN · **Severity**: P0 · **Surfaced**: Phase D REPL

For a substrate aimed at being the deterministic forward model (θ) for
[[kontor-vision]] / simmis simulations and what-if analyses, defaulting
to wall-clock now BREAKS the use case. A consumer modeling a 2026
fiscal year today (2026-05-25) gets a trial balance with only postings
effective ≤ today; the Dec 31 year-end entries, the Jan 15 dividend
distribution, the 2027 forward-looking accruals — all silently missing.

Verified live: in Phase D, posting €40k revenue effective Jun 30 2026,
opex effective Dec 15 2026, dividend declare Dec 31, distribute Jan 15
2027 → `(trial/trial-balance conn)` returns only the Jan 2 opening
entry. €0 of revenue visible. Looks correct on the surface.

**Proposed direction**: change the default `:as-of-valid` from `now` to
`nil` (= "all valid time"). For point-in-time queries, the consumer
passes an explicit date. Same for `kontor.balance/account-balance` and
anything else with the same default. Loud-fail when the implicit-now
default would silently exclude data is a non-starter for simulations.

**This is the single most dangerous default in the substrate right now.**

### I-18 — Tax accounts (KSt / GewSt / Soli / Dividenden-Zahlbar / KESt-Zahlbar) not in shipped SKR04
**Status**: OPEN · **Severity**: P2 · **Surfaced**: Phase D REPL

SKR04 chart (modules/l10n-de/resources/kontor/l10n_de/skr04.edn) ships
~44 accounts covering basic income, opex, balance-sheet items. Missing
the corporate-tax expense + liability side:
- `Aufwendungen:Steuern:KSt`         (7610)
- `Aufwendungen:Steuern:GewSt`       (7681)
- `Verbindlichkeiten:Steuern:*-Rückstellung`
- `Verbindlichkeiten:Dividenden-Zahlbar`
- `Verbindlichkeiten:KESt-Zahlbar`

Anyone running a DE GmbH/UG via kontor has to add these manually.

**Proposed direction**: extend SKR04 EDN with ~6 standard tax-side
accounts. Keep them under the conventional Aufwendungen / Verbindlich-
keiten branches. Same for CA (T2 line items) and US (Form 1120 lines).

### I-19 — Cross-DB FX + cross-DB partner refs are consumer plumbing
**Status**: OPEN · **Severity**: P1 · **Surfaced**: Phase D REPL

When booking the DE-UG dividend on Christian's CA personal DB, the
consumer manually:
- Looks up the EUR/CAD rate at the value-date (no `kontor.fx` call
  pulled the ECB rate; the consumer hard-coded 1.50)
- Decides how to map the DE WHT 26.375% into CA's §126 FTC slot
  (the treaty-15% cap is the consumer's responsibility)
- Decides where to book the BZSt-refundable excess (11.375% over
  treaty) — there's no `:asset/foreign-tax-refundable` convention

Each of these is a per-treaty-pair business rule. The substrate has
ADR-074 (cross-DB saga) but no `kontor.treaty.de-ca/dividend-receive!`
or similar helper.

**Proposed direction**: ship per-treaty helper namespaces in a new
`modules/treaty-{src}-{dst}/` companion (e.g. `treaty-de-ca`). Each
ships:
- The treaty rate per income type (dividend / interest / royalty)
- A helper that, given the FX rate at value-date, builds the CA-side
  receipt entry with the right split (treaty-creditable, BZSt-
  refundable-excess, net-cash)
- The reverse helper for outbound (e.g. CA Inc paying dividend to a
  DE resident)

This is exactly the "two-sided" McComb framing from [[kontor-vision]]
— both sides of a treaty-pair compose.

## §8 — Test fixture conventions

### I-13 — Journal codes differ across tests
**Status**: OPEN · **Severity**: P2 · **Surfaced**: code-review 2026-05-25

Different fixtures use different codes: `"GJ"/"CR"/"CD"` (general,
cash-receipts, cash-disbursements) vs `"GEN"/"CASH"` (2-journal
shorthand) vs `"INV"/"BNK"/"GEN"` (older convention). No standard.

**Proposed direction**: convention in `doc/conventions.md` —
recommended codes per jurisdiction (`"GJ"/"CR"/"CD"/"SJ"/"PJ"` is the
US 5-journal canonical; DE conventionally uses `"EB"/"PK"/"BK"`).

---

## How this list is maintained

- Add entries as inconsistencies surface during REPL exploration or
  code review. Always cite file:line and severity.
- When an entry is fixed, append `→ FIXED in <commit-sha>` rather than
  deleting — the history teaches the next set of conventions.
- Cluster fixes: when 3–5 entries in a section are P1+ and can be fixed
  together, batch them into a single ADR + commit (the C2.5-style
  round-2 sweep is the model).
- This note is a living document; the substrate's regularity is its
  most testable property over time.
