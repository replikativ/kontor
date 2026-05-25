# Research note 159 — C3 REPL exploration: usability gaps in the IC stack

## Use case 1: Solo freelancer receives dividend from own GmbH

The story:
- Hans owns 100% of his GmbH ("Hans-Tech UG").
- The UG had a profitable year, decides to distribute €50,000 dividend to Hans.
- The UG must apply KESt 25% + Soli 5.5% withholding (Germany).
- Hans personally must declare the dividend on his Anlage KAP.
- His marginal income-tax rate is 30% (< 25% Abgeltungsteuer flat) — so Günstigerprüfung should give him the lower bracket rate.

What kontor surfaces:
- `kontor.book/declare-dividend!` on the UG side?
- `kontor.book/distribute-dividend!` on the cash-out side?
- The DE CGT + investment-income providers handle KESt + the personal tax side.

Friction:
- _to be filled as I explore_

## Friction findings

### F1 — Naming inconsistency: `:partner/external-id` vs `:entity/source-id`
Severity: P2 (documentation / API consistency)

Partners get `:partner/external-id` for the consumer's primary key, but
entities use `:entity/source-id` (and have a separate `:entity/code`).
Anyone working through the schema namespace-by-namespace and applying the
same pattern hits a schema error. Reasonable cross-jurisdiction conventions
exist for both (`:source-id` matches the HR/disposal companions) but the
inconsistency is invisible until you write the wrong attr.

Fix options:
- Add an `:entity/external-id` alias (back-compat)
- Document the convention prominently in `doc/conventions.md`
- Rename `:partner/external-id` → `:partner/source-id` over a deprecation window


### F2 — `:account/commodity` ref must be looked up; can't tempid in same tx
Severity: P2 (DX)
Setting up an account with a default commodity in one tx fails — must
split into two transacts (commodity first, then accounts with
`[:commodity/symbol "EUR"]` lookup). Workaround obvious once you know;
not obvious from the docstring.

### F3 — `create-test-db` ships no default journals
Severity: P2 (DX, but the error message is excellent)
Every consumer needs at minimum `:general`, `:cash` (typically `:sales`,
`:purchase` too). The error from `resolve-journal` is clear about how to
fix it. Either:
- Add a `core/create-test-db-with-journals` helper
- Have `create-test-db` ship sensible defaults
- Document this in `doc/programming.md` as a getting-started step

### F4 — Money record passed to `kontor.book` verbs fails opaquely
Severity: P1 (DX)
`(money/money 50000M :EUR)` constructs a `Money` record; `kontor.book`
verbs expect `:amount <BigDecimal>` + `:commodity <keyword>`. The
`->bigdec` helper (book.clj:76-82) throws `IllegalArgumentException`
when handed a Money: "No matching ctor found for class
java.math.BigDecimal". The error doesn't hint at the actual cause.
Fix: extend `->bigdec` to unwrap a Money via `kontor.money/amount`,
and have the entry-level `:commodity` default from `(money/commodity x)`
when amount is a Money.

### F5 — `:account/code` is NOT unique; `:account/path` IS
Severity: P1 (DX + docs)
The natural impulse for someone reading the schema is to use `:account/code`
as the primary lookup, because it's "the code". But it's `:db.unique nil`.
The canonical lookup attribute is `:account/path` (hierarchical PTA-style).
The tests show this convention; the schema doc on `:account/code` doesn't
warn against using it as a lookup-ref. Could either:
- Make `:account/code` `:db.unique/identity` (scoped per chart if needed)
- Add a docstring warning + a `doc/conventions.md` section
- Surface in the book docstrings: "use `:account/path` for lookup-refs"


### F6 — `:commodity` keyword (`:EUR`) treated as entid lookup; needs lookup-ref
Severity: P2 (DX, but consistent w/ datahike norms)
Passing `:commodity :EUR` (a bare keyword) fails — must be `[:commodity/symbol "EUR"]`.
Test fixtures define `(def usd [:commodity/symbol "USD"])` as a top-level var
and pass `:commodity usd`. The book docstring doesn't say "must be a
lookup-ref or eid". Consider:
- Auto-coerce `:commodity` strings/keywords to lookup-refs in `kontor.book`
  (e.g. `:EUR` → `[:commodity/symbol "EUR"]`)
- Document the convention prominently

### F7 — IC providers' GL-scan uses `:account-code`; chart convention is `:account/path`
Severity: P0 (silent wrong-result for any consumer using the documented chart convention)

The chart convention everywhere (kontor.book lookup-refs, all tests, ADR-068
docs) is `:account/path` — hierarchical PTA-style strings, marked `:db.unique/identity`.

But the C2.4/C2.5-fixed IC providers (DE, US, FR, UK) marginalize on
`:account-code` (via `pull-posting`, which pulls `:account/code`). If a
consumer sets only `:account/path` (the canonical lookup-ref), the GL-scan
returns empty silently — no error, no warning, just zero income detected
and no tax component.

The C2.4 commit message claims the GL-scan was fixed. It WAS fixed for the
axis-name bug, but the underlying convention mismatch remained. The
"tests pass" because all tests bypass via `:inputs :investment-income-bases`.

Fix options:
- Extend `pull-posting` to also pull `:account/path`, add `:account-path`
  to `dimension-extractors`, and switch the 4 IC providers to that axis.
- OR document the consumer requirement: every IC-relevant income account
  must set BOTH `:account/path` AND `:account/code` (using the same value).
- OR have the IC provider throw `ex-info` when GL-scan finds zero accounts
  matching ANY of its expected prefixes — better silent-wrong → loud-fail.

The third option is the minimum bar; the first is the real fix.


### F8 — Split install paths between statute + provider; silent under-tax on the wrong one
Severity: P0 (silent wrong-result of 5.5 % Soli)

The DE investment-income module has TWO install fns:
- `kontor.l10n-de.investment-income-statute/install!` — only KiSt provision
- `kontor.l10n-de.investment-income-provider/install-statute!` — KiSt + Soli-on-§20-income

A consumer who calls the natural-looking `de-inv-statute/install!` MISSES
the Soli provision entirely. The IC provider runs cleanly, no error, just
under-taxes every German dividend by 5.5%. Verified live in the REPL.

Root cause: `soli-on-§20-income-provision` lives in the *provider* namespace
(line 161 of investment_income_provider.clj) because "the standalone-
runnable convention" requires the compute-fn registration and its provision
to live together. The IC statute namespace's `install!` doesn't know about it.

Fix options:
- Move the Soli-on-§20-income provision into the IC statute file (the
  compute-fn registration can stay in the provider; provisions reference
  compute-fns by keyword anyway).
- Remove `de-inv-statute/install!` from the public surface; only expose
  `de-inv/install-statute!`.
- Add a runtime check: when the IC provider runs, query the registered
  compute-fns and verify each has a corresponding provision in the DB —
  raise/warn if any are missing.

This is likely NOT just a DE issue — every IC module probably has the
same split-install pattern. Audit needed.

### F9 — DE setup is 4 steps with non-obvious prerequisites
Severity: P1 (DX)

Just to get DE personal investment-income tax working, a consumer must:
1. `(de-cit-statute/install! conn)` — for `DE.Soli.rate` parameter
2. `(de-cgt-statute/install! conn)` — for `DE.EStG.§20.flat-rate` + the
   shared Soli-on-§20 provision and compute-fn
3. `(de-inv/install-statute! conn)` — the IC statute + the income-side
   Soli provision (NOT `de-inv-statute/install!`)
4. Create commodity, accounts, journals separately

The prerequisites are documented in the `install-statute!` docstring but
ordering matters and the failure modes are silent (F8) or cryptic (NPE
on `(* gross rate)` when `:DE.Soli.rate` is missing).

Fix: ship `(de/install-all! conn)` — a one-shot installer per jurisdiction
that pulls in everything in the right order. Same for the other 10
jurisdictions.

## High-level usability gaps

### Story 1 (freelancer + GmbH dividend) end-to-end

What worked:
- The two-sided model is correct (corp declares → distributes; shareholder
  receives → IC provider taxes). Mathematically sound at €12,923.75 total.
- `kontor.book` verbs are pleasingly readable once you know the conventions.
- The bitemporal + sealing discipline kept transactions auditable.
- All the actual tax math is correct.

What was hard:
- 4-step install dance with two paths into the IC statute (F8/F9).
- Chart of accounts convention split between `:account/path` (lookup-ref
  primary key) and `:account/code` (GL-scan marginalize key); the IC
  provider's GL-scan returns silently empty under the documented chart
  convention (F7).
- No default journals in `create-test-db` (F3).
- Commodity reference via lookup-ref is the only way; bare keyword fails (F6).
- Money records can't be passed to `kontor.book` verbs (F4).

What's missing for "usable":
- A 10-line getting-started snippet that just works (currently any honest
  one is ~30 lines of plumbing).
- A `kontor.preset.<jurisdiction>` namespace that ships:
  - `(install-all! conn)` — schema + statutes + provisions in order
  - `(default-chart! conn opts)` — a per-jurisdiction default chart with
    both `:account/path` AND `:account/code` set so GL-scan works
  - `(default-journals! conn)` — GJ, CR, CD, INV, PUR
- Consumer-facing docs at `doc/getting-started-<de|us|fr>.md` walking the
  full freelancer / GmbH dividend story with copy-pasteable code.
- An end-to-end integration test that exercises GL-scan (not just
  `:investment-income-bases`).

## Concrete next-step priorities for "make this usable"

P0 (silent wrong-result fixes — anyone using the substrate today is at risk):
1. F7: IC GL-scan vs `:account/path` mismatch. Fix `pull-posting` to pull
   `:account/path` too; add `:account-path` axis; switch the 4 IC providers
   that GL-scan. Or document loudly + add ex-info when GL-scan returns 0
   bases but the DB has income postings.
2. F8: Split-install audit across all 11 IC modules. Consolidate provisions
   into the statute namespace (compute-fn registration stays in provider).
3. Add a runtime validator: on `period-tax-facts`, query registered
   compute-fns AND active provisions; ex-info on mismatch.

P1 (DX — onboarding):
4. F9: ship `kontor.preset.<jurisdiction>/install-all!` for each of the
   11 jurisdictions.
5. Add `kontor.core/create-test-db-with-journals` (or include defaults
   in `create-test-db`).
6. F4: `kontor.book` verbs accept Money records (unwrap automatically).
7. F6: `kontor.book` verbs auto-coerce `:commodity :EUR` → lookup-ref.

P2 (polish):
8. F1: name unification (`:partner/external-id` vs `:entity/source-id`).
9. F5: docstring + conventions clarification on `:account/path` vs
   `:account/code`.
10. F2: account+commodity in one tx (probably needs upserts on
    `:commodity/symbol`).

