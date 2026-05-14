# 28 — Odoo fixed-asset depreciation + year-end close + statutory statements

> **Research-before for Stage L′ (`kontor-asset`).** Studies how Odoo models fixed-asset
> depreciation ("Abschreibungen") and year-end close + financial statements
> ("Jahresabschluss"). Odoo is LGPLv3 — read for *patterns*, never translate (ADR-001).
> Date: 2026-05-14. Odoo checkout is **v19.0** (`odoo/release.py:version_info = (19,0,0,FINAL,…)`).
>
> **Scope note.** `account_asset` is an Odoo *Enterprise* module and is **not** in this
> Community checkout (`ls addons | grep asset` → nothing). The asset half of this note is
> therefore from Odoo's *public documentation* plus the OCA `account_asset_management`
> module (AGPL-3 — readable as a *reference*, not liftable). The year-end-close + report
> half **is** in Community `account` and is cited at file:line.

---

## 1. Asset model (depreciation)

### 1.1 The two community-accessible reference points

Odoo ships fixed-asset accounting only in Enterprise. Two license-clean references exist:

- **Odoo's public docs** — `odoo.com/documentation/19.0/applications/finance/accounting/vendor_bills/assets.html`. Describes the Enterprise UI/behaviour.
- **OCA `account_asset_management`** — `github.com/OCA/account-financial-tools/tree/18.0/account_asset_management`, AGPL-3, the long-lived community alternative ("an enhanced version of Odoo's standard asset accounting … a large number of functional enhancements"). Its `models/account_asset.py` is the most detailed open data model available.

The two converge on the same shape; OCA's is richer and better documented, so the field-level detail below is OCA's, cross-checked against Odoo docs.

### 1.2 Entity shape — `account.asset`

Core identity + valuation:
- `name`, `code` — identity.
- `purchase_value` — "the initial value of the asset" (gross value / acquisition cost).
- `salvage_value` — "estimated value … upon its sale at the end of its useful life" (residual / *Restwert*). In Odoo docs this is the **"Not Depreciable Value."**
- `depreciation_base` — **computed** = `purchase_value − salvage_value`. This is the amount that actually gets spread.
- `value_residual` — computed, stored: remaining book value.
- `value_depreciated` — computed, stored: accumulated depreciation to date.

Depreciation configuration:
- `method` — `linear | linear-limit | degressive | degr-linear | degr-limit`.
  - **linear** = straight-line: `depreciation_base / number_of_depreciations`, equal instalments.
  - **degressive** = declining-balance: `residual_value × method_progress_factor` each period, decreasing instalments.
  - **degr-linear** ("Declining Then Straight Line" in Odoo docs) = declining, but each period takes `max(declining, straight-line-on-remaining)` so it switches to straight-line once that yields more. This is the **German degressive-then-linear** AfA pattern and the IFRS "reflect consumption" requirement.
  - `*-limit` variants stop at the salvage floor.
- `method_time` — `year | number`: drive the schedule by a useful-life in *years* or by an explicit *count* of lines.
- `method_number` — number of years / depreciations.
- `method_period` — posting cadence: `month | quarter | year`.
- `method_progress_factor` — the degressive factor (e.g. `0.30`).
- `method_end` — optional explicit end date.

Calculation refinements (these are exactly the "prorata-temporis" knobs the docs talk about):
- `prorata` — first entry runs from the *depreciation start date* rather than from the first day of the fiscal year (Odoo docs call the anchor the **"Prorata Date"** vs **"First Depreciation Date"**).
- `days_calc` — compute each instalment by actual day-count rather than equal periods.
- `use_leap_years` — count leap-year days.
- `carry_forward_missed_depreciations` — roll un-posted past instalments into the current open period (matters when an asset is entered late).

Lifecycle + relations:
- `state` — `draft → open → close → removed` (Odoo docs' UI labels: draft, running, closed/disposed).
- `date_start` — asset start date.
- `date_remove` — disposal date, set on removal.
- `profile_id` — **required** Many2one to `account.asset.profile` (the *asset model / template*, §1.4).
- `depreciation_line_ids` — One2many to `account.asset.line` (the depreciation board, §1.3).
- `account_move_line_ids` — the posted journal entries this asset produced.
- `partner_id`, `group_ids`, `company_id`, `currency_id`.

Note OCA has **no `parent_id`** asset-hierarchy field — child/component assets are not modelled; an asset is flat. (Splitting a vendor bill into multiple assets is done at creation time, not via a tree.)

### 1.3 The depreciation board — `account.asset.line`

Each row is one planned (or posted) depreciation instalment:
- `asset_id`, `line_date`, `amount`, `name`, `line_days`.
- `type` — `create | depreciate | remove`. The board literally contains the *acquisition* row and the *disposal* row alongside the depreciation rows — the board is the full lifecycle ledger, not just the recurring part.
- `previous_id` — links a line to its predecessor (so each row's residual-value math is a function of the prior row, important for degressive).
- `init_entry` — flags a prior-year / opening depreciation entered manually rather than computed.
- `move_id` / `move_check` — the posted `account.move`, and whether it is posted.

`compute_depreciation_board()` is the engine method: detects already-posted entries, builds the fiscal-year breakdown (`_compute_depreciation_table`), computes each line (`_compute_depreciation_line`, `_compute_year_amount` — explicitly documented as the override seam for the degressive-linear logic), and writes the `account.asset.line` rows. Helpers: `_get_depreciation_stop_date`, `_get_fy_duration_factor` (prorata across a short/long fiscal year), `_compute_line_dates`, `_group_lines`.

So the board is **draft data first** — the lines are computed and visible *before* anything is journalized; posting an `account.asset.line` is a separate step that creates the `account.move`.

### 1.4 The asset profile / template — `account.asset.profile`

`account.asset.profile` (Enterprise calls it the **"asset model"**) is the template: it carries `method`, `method_time`, `method_number`, `method_period`, `method_progress_factor`, the **journal** and the depreciation/expense **accounts** to use, the prorata flags. An `account.asset`'s config is defaulted from its `profile_id`. This is what lets "create an asset from a vendor bill" be one click — the bill line's account is mapped to a profile, and the asset inherits the whole depreciation policy. (OCA issue #910 notes profile changes do *not* retro-apply to existing assets — the profile is a creation-time default, not a live link.)

### 1.5 Lifecycle

1. **Acquisition.** Asset created — manually, or auto-generated from a posted journal entry hitting an asset account (OCA), or from a vendor bill line (Enterprise). `_create_first_asset_line()` writes the `type=create` board row. `validate()` moves `draft → open` and triggers board computation.
2. **Depreciation.** Board lines post one `account.move` each: Dr depreciation expense / Cr accumulated depreciation. Posting is driven manually from the board, by the "Compute Assets" wizard, or by the **"Generate assets" cron** (Odoo's generic `_autopost_draft_entries` cron, `account_move.py:6260`, explicitly says it posts "entries created by the module account_asset").
3. **Modification / revaluation.** Odoo docs: "Modify Depreciation" — an *increase* creates a *new linked asset entry*; a *decrease* posts a journal entry and rewrites the future *un-posted* board lines. Posted history is never edited — only the forward board is recomputed. OCA recomputes the remaining board from the changed residual.
4. **Disposal / sale.** `remove()` (OCA) / "Sell or Dispose" (Enterprise). Odoo computes **gain or loss = sale amount − book value at sale date**, writing the disposal `account.move` (Cr asset gross, Dr accumulated depreciation, Dr/Cr the gain/loss account, Dr cash/receivable). Early disposal is detected so the partial-period depreciation up to the sale date is taken first. `state → removed`, `date_remove` set.

---

## 2. Year-end close + statutory statements (Community `account`, file:line)

This is the part Community actually ships, and it holds a **surprising and important design lesson**.

### 2.1 The closing entry — there is **no posted closing entry**

Odoo does **not journalize a P&L→retained-earnings closing entry at year-end.** There is no `close_fiscal_year` button that posts anything. Instead:

- `account.account.account_type` has the value **`equity_unaffected` — "Current Year Earnings"** (`account_account.py:57`).
- Every account computes `include_initial_balance` (`account_account.py:638-646`): it is **True** for everything *except* income/expense accounts and the `equity_unaffected` account. The field's help text: *"Account types that should be reset to zero at each new fiscal year (like expenses, revenue..) should not have this option set."*
- So the year-end roll-forward is **a reporting convention, not a transaction.** A balance-sheet query for any date sums income/expense from the *start of the current fiscal year only* (because they don't "carry initial balance"), and sums balance-sheet accounts *from the beginning of time*. The accumulated prior-year profit lives implicitly: it is whatever was *manually* moved into a real `equity` account, plus the `equity_unaffected` account.
- `res.company.get_unaffected_earnings_account()` (`company.py:823-853`) lazily creates one `equity_unaffected` account per company ("Profit or Loss Appropriation", code `999999`). It is used as the **automatic balancing line** of the *opening* move (`company.py:855-940`, `_update_opening_move` → `update_vals(balancing_account, …, balancing=True)`), not of a closing move.

The Balance-Sheet *report* then shows two equity lines: "Current Year Earnings" (the live P&L net, computed) and the `equity_unaffected` account balance ("Previous Years Unallocated Earnings"). The actual *allocation* of last year's profit to reserves/dividends is a **manual journal entry the accountant posts when they want to** — Odoo never does it automatically.

The report engine's `date_scope` field encodes exactly this (`account_report.py:601-613`): `from_beginning`, `from_fiscalyear`, `to_beginning_of_fiscalyear`, `to_beginning_of_period`, `strict_range`. A Balance-Sheet line uses `from_beginning`; a P&L line uses `from_fiscalyear`; "Current Year Earnings" is a P&L-scoped aggregation surfaced on the balance sheet.

### 2.2 Period locking — the close *is* a lock date

What Odoo *does* have at year-end is **lock dates** on `res.company` (`company.py:55-103`):

- `fiscalyear_lock_date` — soft lock for *all* users; "the accountant lock date".
- `tax_lock_date` — auto-set when the tax-closing entry is posted (`company.py:83-88`).
- `sale_lock_date`, `purchase_lock_date` — soft locks scoped to sale/purchase journals.
- `hard_lock_date` — *irreversible*; "does not allow any exception" (`company.py:99-103`).
- `fiscalyear_last_day` / `fiscalyear_last_month` (`company.py:76-77`) — the fiscal-year boundary (default 31 / December), validated by `_check_fiscalyear_last_day` (`company.py:340-354`).

Enforcement:
- `_validate_locks` (`company.py:542-595`) runs on `res.company.write`: a hard lock cannot be removed or moved *backward*; hard-locking refuses if **draft entries** still exist in the period (a `RedirectWarning` showing them) or if **unreconciled bank-statement lines** exist. This is Odoo's pre-close validation gate.
- `_get_user_fiscal_lock_date` / `_get_user_lock_date` (`company.py:597-644`) compute the *effective* lock per user, folding in **lock exceptions**.
- `account.move._check_fiscal_lock_dates` (`account_move.py:2803-2820`) runs on post/write: if `move.date` falls on/before a violated lock date, `UserError`. Posting near a soft lock auto-bumps the accounting date to lock+1 (`account_move.py:6513-6534`, `_get_accounting_date`).

**Lock exceptions** — `account.lock_exception` (`models/account_lock_exception.py`, 306 lines) — a per-user, time-boxed, reason-bearing override of a *soft* lock date (`_name='account.lock_exception'`, fields `user_id`, `reason`, `end_datetime`, `lock_date_field`, `lock_date`, state `active`). The hard lock has **no** exception path. Exceptions create an audit trail (`action_show_audit_trail_during_exception`, `_get_audit_trail_during_exception_domain`).

So Odoo's "year-end close" = (a) run pre-close checks, (b) set `fiscalyear_lock_date`, (c) optionally later set `hard_lock_date`. The financial statements are *queries*, not the product of a close.

### 2.3 The report engine — statements as data

Community ships the *model* of the report engine (`models/account_report.py`, 967 lines) but only the **Generic Tax report** as data (`data/account_reports_data.xml`, 63 lines). Balance Sheet / P&L / Cash Flow *definitions* live in the Enterprise `account_reports` module, as does the *evaluation* engine (`_get_lines`, `_compute_expression` are not in Community). But the **data model** is fully visible and is the pattern worth studying.

Three models compose a statement:

- **`account.report`** (`account_report.py:44`) — the statement header. Fields: `line_ids`, `column_ids`, `country_id` (which jurisdiction), `chart_template`, `availability_condition` (`country | coa | always`), `root_report_id` + `variant_report_ids` (a report can be a **variant** of a root — e.g. a country's Balance Sheet is a variant of the generic Balance Sheet root), `section_report_ids` (a **composite** report stitches sub-reports), plus a large bank of `filter_*` booleans (date range, comparison, analytic, journals, hierarchy, draft entries…).
- **`account.report.line`** (`account_report.py:349`) — one row of the statement. `parent_id` / `children_ids` give the **tree** (Assets › Current Assets › Receivables …); `hierarchy_level` is computed from depth; `code` is a unique handle so other lines' formulas can reference it; `expression_ids` holds the actual computation; `groupby` can explode a line into sub-lines grouped by `account.move.line` fields.
- **`account.report.expression`** (`account_report.py:579`) — the computation, one or more per line (labelled, e.g. `balance`). The `engine` selection (`account_report.py:587-598`) is the key vocabulary:
  - `domain` — an Odoo ORM domain over journal items.
  - `account_codes` — sum journal items by **account-code prefix** (e.g. `1` for all asset accounts; supports `+`/`-` arithmetic, code ranges). This is how a Balance-Sheet line says "all accounts starting 10".
  - `tax_tags` — sum by tax tag (creates `account.account.tag` rows on the fly, `account_report.py:701-716`); this is how *tax returns* are defined.
  - `aggregation` — arithmetic over *other lines'* expressions by `code.label` (e.g. `total_assets.balance - total_liab.balance`).
  - `external` — a manually keyed / carried-over value (`account.report.external.value`, `account_report.py:947`).
  - `custom` — a named Python function (the escape hatch).
  - Each expression has a `date_scope` (§2.1), `figure_type`, `blank_if_zero`, and **carryover** fields (`carryover_target` — for tax lines that roll a credit into the next period).

The takeaway: **a statutory financial statement is a tree of rows, each row a small declarative formula over the ledger, with arithmetic that can reference sibling rows by code.** No code, no subclassing — pure data. A statement is editable by an accountant. This is markedly more flexible than kontor's current `kontor.financial-statements/compute-statement` declarative defs, and the `aggregation` "reference another line by code" mechanic and the `date_scope` enum are the two ideas worth importing.

---

## 3. Tax-law reflection

### 3.1 Book vs tax depreciation — parallel boards via parallel *ledgers*, not parallel *boards*

Neither Odoo Enterprise nor OCA `account_asset_management` models a *single asset with two depreciation areas* the way SAP's `ANLB`/depreciation-area model or `account_asset` competitors do. The OCA `account.asset` has exactly **one** `method`/`method_time`/board.

The Odoo-blessed pattern for Handelsbilanz-vs-Steuerbilanz (book vs tax) depreciation is: **two assets, two profiles, two journals** — "an asset can generate two separate, but linked, asset entries from a single vendor bill, with each entry posted to its respective journal" (Odoo forum/docs). Each asset depreciates independently; they post to different journals (and in Enterprise, different *ledgers* / multi-book). So Odoo's answer to "parallel depreciation" is the same answer it gives to "parallel GAAP": **use the parallel-ledger mechanism, duplicate the asset per book.** There is no first-class "depreciation area" concept.

This is a *cautionary* finding for kontor: SAP-style depreciation areas (one asset, N areas, each with its own method + life + accounts) are the genuinely ergonomic model, and Odoo's "duplicate the asset" is a known pain point. kontor already has `:ledger/*` (ADR-021) — the *right* design is one `:asset` entity with N `:depreciation-schedule` children, each tagged to a `:ledger` (or to a "valuation purpose" — book/tax/IFRS), not N asset entities.

### 3.2 Jurisdiction-specific rules — data-driven via methods, not hardcoded tables

Odoo does **not** ship German AfA-Tabellen or US MACRS tables as data. There is no `account.asset` rate table keyed by asset class. Instead:

- The *method* enum (`linear`, `degressive`, `degr-linear`, the `*-limit` variants) is general enough to *express* most jurisdictions' rules. German degressive-then-linear AfA is exactly `degr-linear`. US MACRS half-year/mid-quarter conventions map onto `prorata` + `days_calc` + the declining factor.
- The *useful life* and *factor* are entered per asset (or per profile). The l10n module's contribution is to ship **asset profiles** as default data (e.g. "Office equipment — 13 years linear" matching the AfA-Tabelle line) — a *seed*, not an engine. The user still owns the number.
- MACRS's published per-year percentage *tables* (which are not a clean formula — they bake in the convention + the switch point) are **not** representable by Odoo's method enum and would need the `custom` Python engine or manual board entry. This is a real gap in Odoo's model.

So: jurisdiction rules are **left to the user**, with l10n modules providing *default profiles* as a convenience seed. Nothing is hardcoded.

### 3.3 Financial-report localization — each l10n module ships its own statements

Confirmed from `odoo.com/documentation/19.0/developer/howtos/accounting_localization.html`: a localization module (`l10n_XX`) depends on `account_reports` and ships its **own** `balance_sheet.xml` / `profit_and_loss.xml` as `account.report` records with `country_id` set. The generic Balance Sheet / P&L are *roots*; each country's statutory version is a `variant_report_ids` of that root (or a standalone country report). `availability_condition='country'` makes a report appear only when the company's country matches. When `account` is installed, the l10n module matching the company's country auto-installs (falling back to `l10n_generic_coa`).

This maps cleanly onto kontor's existing **kernel + l10n-companion** split (ADR-002, architecture.md): the *report engine* is kernel, the *statement definitions* (German `Bilanz` per HGB §266, `GuV` per §275; US-GAAP classified balance sheet) are l10n-companion data. kontor is already structured for this.

---

## 4. What to learn from / what is a cautionary tale

**Good to learn from:**

1. **Closing entry as a reporting convention, not a transaction** (§2.1). Odoo's `include_initial_balance` flag + `equity_unaffected` account type + `date_scope` enum means there is *no fragile year-end batch job* and *no risk of a half-posted close*. The P&L→retained-earnings roll is recomputed on every report read. This is **strictly better** for an event-sourced bitemporal kernel — kontor can restate any past year's statements without "un-closing." kontor's current `kontor.closing/close-fiscal-year!` *posts* a closing entry; Odoo's model says it may not need to.
2. **The three-model report engine** (`report` / `line` / `expression`) with the **engine enum** and **`aggregation` cross-line references by code** (§2.3). Declarative, accountant-editable, no subclassing. The `account_codes` prefix engine is a particularly clean fit for a code-structured chart.
3. **`date_scope` as a per-expression enum** (§2.1) — the single idea that makes one engine serve both the Balance Sheet (`from_beginning`) and the P&L (`from_fiscalyear`).
4. **Lock dates as a small typed set** with hard vs soft distinction and a **typed, audited, time-boxed exception entity** (§2.2). kontor's `kontor.period` soft-lock/hard-seal already mirrors this; the `account.lock_exception` *entity* (per-user, reason, end-datetime, audit trail) is worth copying for the exception case.
5. **Pre-close validation gate** (`_validate_locks`: no drafts, no unreconciled statement lines, §2.2) — a concrete checklist kontor's `close-fiscal-year!` should run.
6. **Asset profile / template** (§1.4) — depreciation policy is data, defaulted onto each asset; "create asset from bill" is a one-click consequence.
7. **The board carries the whole lifecycle** (`type = create | depreciate | remove`, §1.3) — acquisition, depreciation, disposal are *one ordered list*, not three subsystems.

**Cautionary tales:**

1. **No depreciation areas — "duplicate the asset" for book-vs-tax** (§3.1). This is Odoo's weakest fixed-asset design choice and a documented user pain. kontor should *not* copy it; one `:asset` with N schedules, each ledger/purpose-tagged, is the right shape.
2. **`custom` Python engine as the escape hatch** (§2.3). Convenient for Odoo, but it puts arbitrary code inside report definitions — un-auditable, un-restate-able, breaks the "statement is data" promise. kontor should keep the escape hatch *out* of the declarative layer.
3. **Profile changes don't retro-apply** (OCA #910) — the template is a creation-time copy, not a live link. Fine, but must be *documented* as an invariant or it surprises users.
4. **No MACRS-table representation** (§3.2) — Odoo's method enum can't express published per-year percentage tables; they fall back to `custom` or manual board entry. kontor's method abstraction should allow a *table-driven* method from day one (a vector of per-period factors), not only formula-driven methods.
5. **No asset hierarchy / componentization** (§1.2) — OCA's asset is flat; component depreciation (IFRS — depreciate a building's roof separately from its structure) isn't modelled. If kontor wants IAS 16 component accounting, that's a deliberate addition, not inherited.
6. **The report *evaluation engine* is Enterprise-only** — only the *data model* is in Community. kontor must build its own evaluator (it already has `kontor.report` — extend it).

---

## 5. Concrete mapping hints for kontor

### 5.1 Asset — needs a **new `:asset` entity**; `:schedule` is not enough

kontor's `:schedule` (ADR-032) deliberately does *not* compute per-period amounts. A fixed asset needs: a gross value, a salvage value, a method + life, a *computed board*, a residual-value rollforward, a disposal flow with gain/loss. That is a real entity. Recommended:

- **New `:asset/*` namespace** (requires an ADR per the namespacing rule): `:asset/gross-value` (Money), `:asset/salvage-value`, `:asset/depreciable-base` (computed), `:asset/acquisition-date`, `:asset/in-service-date`, `:asset/method`, `:asset/useful-life`, `:asset/method-period`, `:asset/declining-factor`, `:asset/prorata?`, `:asset/state` (draft/active/disposed via the **status machine**, ADR-034 — not a bespoke enum), `:asset/origin-transaction` (the vendor-bill posting it was created from), `:asset/profile` (ref to a template).
- **`:asset-schedule/*`** — a *per-(asset, ledger/purpose)* depreciation board. This is where the SAP "depreciation area" idea lives and where kontor *beats* Odoo: one asset, a `:book` schedule and a `:tax` schedule, each `:posting/ledger`-tagged. Each schedule owns `:asset-schedule/method`, `:asset-schedule/useful-life`, `:asset-schedule/lines`.
- **`:asset-line/*`** — board rows: `:asset-line/date`, `:asset-line/amount`, `:asset-line/type` (`:acquisition | :depreciation | :disposal`), `:asset-line/posted-transaction`, `:asset-line/residual-after`. Computed *as draft data first*, then each posts a `:transaction` via `kontor.posting/build-transaction`.
- **`:asset-profile/*`** — the template (method defaults, journal, depreciation/expense/accumulated accounts). l10n-de companion ships AfA-aligned profiles as seed data; l10n-us ships MACRS profiles.
- The **method engine** is a small pure-function library (`linear`, `declining-balance`, `declining-then-straight-line`, **`table-driven`** for MACRS) — the one place to design *beyond* Odoo (its enum can't do MACRS tables).
- Posting a board line reuses everything kontor already has: `build-transaction` (Dr `expense_depreciation` / Cr accumulated-depreciation), sealing (ADR-007), bitemporal `:tx/valid-from`. Disposal is a reversing+gain/loss transaction, exactly the ADR-007 pattern.
- The `:schedule` entity (ADR-032) is *still useful* — for the *posting cadence* of an already-computed board, or for revrec/subscription. But the *amount computation* now lives in `kontor-asset`. Consider: `:asset-schedule` could *emit* `:schedule-occurrence` rows, reusing ADR-032's cadence machinery.

### 5.2 Year-end close — mostly **reuse**, with one design call

- **Design call (surface with AskUserQuestion):** Odoo proves the closing entry can be a *reporting convention* (`include_initial_balance` + `equity_unaffected` + `date_scope`), not a posted transaction. kontor's `kontor.closing/close-fiscal-year!` currently *posts* a rollup. For an event-sourced bitemporal kernel, the Odoo model is arguably better (restate any year with no un-close). Options: (a) keep the posted closing entry (familiar, some jurisdictions/auditors expect a visible *Abschlussbuchung*); (b) adopt Odoo's computed model; (c) **both** — a `:account-type` direction flag (`:resets-each-fiscal-year?`) drives the *report* roll, and `close-fiscal-year!` optionally *also* posts a formal closing entry for jurisdictions that require one (DE SKR `Schlussbilanzkonto`/`GuV`-Konto). (c) is the kontor-shaped answer.
- **Reuse:** `kontor.period` soft-lock/hard-seal already mirrors Odoo's `fiscalyear_lock_date` / `hard_lock_date`. Add the **pre-close validation gate** (`_validate_locks` pattern: no draft transactions, no unreconciled bank lines, trial-balance-zero) to `close-fiscal-year!` / `close-period!` — research note 07 already flagged this; Odoo confirms the exact checklist.
- **New small entity:** a `:lock-exception/*` mirroring `account.lock_exception` — per-user, reason-bearing, time-boxed override of a *soft* period lock, with `:status-history` audit. Hard seal has no exception (matches `hard_lock_date`). Composes with ADR-038 approval-policy.
- The **`equity_unaffected` account type** maps to a kontor `:account/type` value (kontor's account-type table, ADR-041 `:account-type-direction`) — "Current Year Earnings" / *Jahresüberschuss* — flagged `:resets-each-fiscal-year? false` and excluded from the P&L.

### 5.3 Financial statements — **extend `kontor.report` / `kontor.financial-statements`**

- kontor already has `kontor.report` (declarative engine) + `kontor.financial-statements/compute-statement`. The Odoo `report`/`line`/`expression` shape is the upgrade target:
  - Adopt the **engine enum** idea: kontor expressions should support at least `account-codes` (prefix sum — clean for code-structured charts), `domain`/`query` (datalog over postings), `aggregation` (**reference sibling lines by code** — kontor's current defs likely don't do this and should), `external` (manually keyed values).
  - Adopt **`date-scope`** per expression (`:from-beginning | :from-fiscal-year | :to-beginning-of-period | :strict-range`) — the one mechanism that lets a single engine produce both BS and P&L. This composes *perfectly* with kontor's bitemporal `:as-of-valid` / `:as-of-tx`.
  - Adopt **root + variant**: a generic Balance Sheet root in the kernel, country-statutory variants (`:report/country`) in l10n companions — exactly kontor's kernel+l10n split.
  - **Do not** adopt the `custom` Python engine — keep statements pure data.
- **Cash-flow statement & statement-of-changes-in-equity:** Odoo Enterprise defines these as the *same* `account.report` data shape (cash-flow is just lines with `account_codes`/`domain`/`aggregation` engines + `date_scope`). So kontor needs **no new machinery** for them — once the report engine has the engine enum + date-scope + cross-line aggregation, the cash-flow statement is *just another statement definition* (an l10n or kernel data file), not a new generator. This directly answers the roadmap's "no cash-flow generator" gap.

---

## Sources

- Odoo Community v19.0 source, this checkout:
  - `addons/account/models/account_report.py:44-967` — `account.report` / `.line` / `.expression` / `.column` / `.external.value` models; engine enum `:587-598`; `date_scope` `:601-613`.
  - `addons/account/models/account_account.py:44-75` (`account_type` incl. `equity_unaffected`), `:638-646` (`include_initial_balance`).
  - `addons/account/models/company.py:55-103` (lock-date fields + fiscal-year boundary), `:340-354` (`_check_fiscalyear_last_day`), `:542-644` (`_validate_locks`, `_get_user_fiscal_lock_date`), `:823-940` (`get_unaffected_earnings_account`, `_update_opening_move`).
  - `addons/account/models/account_move.py:2803-2820` (`_check_fiscal_lock_dates`), `:6260-6273` (`_autopost_draft_entries` — posts `account_asset` moves), `:6513-6534` (`_get_accounting_date`).
  - `addons/account/models/account_lock_exception.py:1-306` (`account.lock_exception`).
  - `addons/account/data/account_reports_data.xml:1-63` (only the generic tax report ships in Community).
  - `odoo/release.py` — `version_info = (19,0,0,FINAL,…)`.
- Odoo public docs:
  - Non-current assets and fixed assets — https://www.odoo.com/documentation/19.0/applications/finance/accounting/vendor_bills/assets.html
  - Accounting localization howto — https://www.odoo.com/documentation/19.0/developer/howtos/accounting_localization.html
  - Forum: multiple depreciation tables / areas — https://www.odoo.com/forum/help-1/does-odoo-support-multiple-depreciation-tables-methods-areas-for-a-single-fixed-asset-289196
- OCA `account_asset_management` (AGPL-3, reference only):
  - https://github.com/OCA/account-financial-tools/tree/18.0/account_asset_management
  - `models/account_asset.py` — `account.asset` / `account.asset.line` fields + `compute_depreciation_board` and related methods.
  - Issue #910 — profile changes don't retro-apply to existing assets.
