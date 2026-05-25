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
**Status**: OPEN · **Severity**: P1 · **Surfaced**: note 159 §F6

`:commodity :EUR` in `kontor.book` verbs throws `entid-strict: Nothing
found`. Must be `[:commodity/symbol "EUR"]` (or an eid). Test fixtures
universally define a top-level var:
```clojure
(def ^:private eur [:commodity/symbol "EUR"])
```

**Proposed direction**: `kontor.book` verbs auto-coerce bare keyword /
short-string `:commodity` to a `[:commodity/symbol …]` lookup-ref —
mirrors what consumers naturally write.

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
**Status**: OPEN · **Severity**: P1 · **Surfaced**: REPL 2026-05-25

First call to `commit/record-commitment!` after `core/create-test-db` +
`commit/install!` throws "Illegal status transition" until you ALSO call
`kontor.commitment.schema/install!` to seed the transition table.

Confusingly:
- `kontor.commitment/install!` is `(def install! schema/install!)` — yes!
- But in the REPL the namespace alias `commit` was set up before `schema`
  was loaded, and `install!` resolved to the schema-only one (no journal
  install).

Investigated: actually `commit/install!` DOES call `schema/install!`
(line 31 of commitment.clj). But in the REPL session it wasn't being
called before `record-commitment!`. The error message points at status-
machine, not commitment.

**Proposed direction**: `commit/record-commitment!` should sanity-check
that the status-transition seeds exist and throw a clearer error
("commitment.schema not installed; call commit/install! first") if
not.

---

## §7 — Test fixture conventions

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
