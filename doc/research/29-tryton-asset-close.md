# 29 — Tryton fixed-asset depreciation + year-end close + statutory statements

> **Research-before for Stage L′ (`kontor-asset`).** Deep read of Tryton's
> `account_asset` and `account` modules at file:line depth. Tryton is GPLv3 —
> **this note records PATTERNS only; no code is lifted** (ADR-001: "Tryton is a
> reference design, not a code source"). Tryton is the richest *local* reference
> for both Abschreibungen (depreciation) and Jahresabschluss (year-end close).
>
> Source root: `/home/christian-weilbach/Development/tryton/`
> Primary files: `modules/account_asset/{asset,account,product,invoice}.py`,
> `modules/account_asset/account_chart_de.xml`,
> `modules/account/{fiscalyear,period,account,move}.py`,
> `modules/account/account_chart_de.xml`.

---

## 0. Executive summary

Tryton's fixed-asset story is **deliberately minimal**: one depreciation method
(`linear`), two frequencies (`monthly`/`yearly`), prorata-by-day at the ends,
and the depreciation *schedule* is fully materialised as `account.asset.line`
rows the moment the asset is `run`. A separate wizard later turns those lines
into posted `account.move`s. There is **no degressive/declining-balance method,
no units-of-production, no book-vs-tax parallel schedule, no group/mass
depreciation, no impairment** — all of that would be a customer's burden.

Tryton's year-end close is **two distinct mechanisms** that should not be
conflated:

1. **`FiscalYear.close`** (`fiscalyear.py:345-386`) — freezes the year and
   snapshots every balance-sheet account into immutable `account.account.deferral`
   rows. P&L accounts are *required to be zero* at this point (they are not
   `deferral=True`), so the close *presumes* P&L has already been zeroed.
2. **`BalanceNonDeferral`** (`fiscalyear.py:465-545`) — the wizard that actually
   *does* the P&L→equity rollup ("Jahresabschluss" / Saldovortrag). It posts one
   adjustment move that credits/debits every non-deferral account to zero and
   books the net to an equity counterpart. This is run *before* `close`.

The financial-statement engine is **`account.account.type`** — a per-company
tree with a `statement` selector (`balance`/`income`/`off-balance`) plus boolean
role flags (`assets`, `receivable`, `payable`, `stock`, `revenue`, `expense`,
`debt`). Balance Sheet vs Income Statement classification is *entirely* a
function of which type an account points at. The type tree ships **per chart
template** — the German chart (`account_chart_de.xml`) defines its own ~40-node
type tree with German labels.

The `account_asset` module's only schema contribution to the type system is one
boolean: `AccountType.fixed_asset` (`account_asset/account.py:130-165`), set
`True` on exactly two German type templates (`property_de`,
`prepaid_expenses_de`) by `account_asset/account_chart_de.xml`.

---

## 1. The Asset model — `account_asset/asset.py`

### 1.1 `Asset` entity shape (`asset.py:54-737`)

`account.asset` is a `Workflow + ModelSQL + ModelView`. Field-by-field:

| Field | Line | Notes |
|---|---|---|
| `number` | 57 | sequence-assigned at `run` (`set_number`, `asset.py:639-657`) |
| `product` | 58-69 | required `Many2One` → `product.product`; domain `type='assets'` **and** `depreciable=True` |
| `supplier_invoice_line` | 70-85 | optional link to the purchase invoice line that created it; `Unique` constraint (`asset.py:204-207`) |
| `customer_invoice_line` | 86-88 | function field — the sale that disposed of it |
| `account_journal` | 89-94 | required; domain `type='asset'` (a journal type the module adds, `account.py:213-219`) |
| `company` | 95-99 | required |
| `currency` | 100-101 | function — derived from company |
| `quantity` / `unit` | 102-113 | quantity of the depreciable good |
| `value` | 114-120 | **acquisition cost** — "value of the asset when purchased", required |
| `depreciated_amount` | 121-130 | amount already depreciated *before* `start_date` (for mid-life imports); `domain [<= value]` |
| `depreciating_value` | 131-134 | **function** = `value − depreciated_amount` (`on_change_with_depreciating_value`, `asset.py:297-302`) |
| `residual_value` | 135-143 | salvage / `Restwert`; `domain [<= depreciating_value]` |
| `purchase_date` | 144-149 | required |
| `start_date` / `end_date` | 150-160 | required; `start_date <= end_date`; depreciation runs strictly between these |
| `depreciation_method` | 161-167 | **`Selection` with exactly one value: `linear`** |
| `frequency` | 168-175 | `monthly` / `yearly` |
| `state` | 176-180 | `draft` / `running` / `closed` |
| `lines` | 181-182 | `One2Many` → `account.asset.line` — the depreciation table |
| `move` | 183-186 | the **closing/disposal** move |
| `update_moves` | 187-194 | `Many2Many` → revaluation moves |
| `revisions` | 196-197 | `One2Many` → `account.asset.revision` |

**Lifecycle / workflow** (`asset.py:213-217`, transitions): `draft → running`,
`running → closed`, `running → draft`. Buttons: `run`, `close`, `draft`,
`create_lines`, `clear_lines`, `update` (`asset.py:218-245`).

- `run` (`asset.py:669-674`): assigns the sequence number and calls
  `create_lines` — i.e. materialises the whole depreciation table at activation.
- `close` (`asset.py:676-694`): clears any *un-posted* lines, builds a
  *disposal* move per asset (`get_closing_move`), posts it.
- `draft` (`asset.py:659-667`): only allowed if there are no lines at all.

Note: there is **no per-period state on the asset** — once `running`, the asset
just has a growing set of lines whose `move` is or isn't posted. "Where are we"
is derived, not stored.

### 1.2 `AssetLine` — the depreciation table row (`asset.py:740-771`)

`account.asset.line` is a plain `ModelSQL`/`ModelView` (NOT a workflow). One row
per depreciation date:

| Field | Line | Meaning |
|---|---|---|
| `asset` | 742-743 | parent, `ondelete='CASCADE'` |
| `date` | 744 | the depreciation date |
| `depreciation` | 745-747 | the **period charge** (can be negative — see revaluation) |
| `acquired_value` | 748-750 | snapshot of `asset.value` at line-creation time |
| `depreciable_basis` | 751-753 | the amount being spread (`depreciating_value − already-depreciated − residual`) |
| `actual_value` | 754-755 | `asset.value − accumulated_depreciation` — the carrying amount (`Buchwert`) |
| `accumulated_depreciation` | 756-758 | running total |
| `move` | 759 | the posted `account.move` for this line (null until posted) |

Lines are `_order` by `date ASC` (`asset.py:767`).

### 1.3 Depreciation computation — the core algorithm

Three methods cooperate:

**`compute_move_dates`** (`asset.py:373-405`) — produces the list of dates at
which a depreciation move fires. It uses `dateutil.rrule`:
- `monthly` → `rrule(MONTHLY, dtstart=start_date, bymonthday=config.asset_bymonthday)`
- `yearly` → `rrule(YEARLY, …, bymonth=config.asset_bymonth, bymonthday=…)`
- the German default config is `bymonthday="-1"` (last day) and `bymonth="12"`
  (December) — `account_asset/account.py:106-117`.
- `end_date` is *always appended* as the final date (`asset.py:404`).
- start point is `max(start_date, last existing line date)` so re-running after
  some lines posted only extends the tail.

**`compute_depreciation`** (`asset.py:407-445`) — linear, with **day-prorated
first and last periods**:
- `first_delta = normalized_delta(start_date−1day, dates[0])` and
  `last_delta = normalized_delta(dates[-2], dates[-1])`.
- `normalized_delta` (`asset.py:34-51`) is a custom timedelta that *forces 365
  days/year* by subtracting leap-day corrections — Tryton deliberately ignores
  Feb-29 so depreciation is calendar-stable.
- The per-period charge is `amount / (len(dates) − 2 + first_ratio + last_ratio)`
  — i.e. the middle periods get a full unit, the two ends get fractional units
  proportional to days elapsed. This is the **prorata-temporis** convention
  (used in DE, FR).
- Result is rounded with `company.currency.round` (HALF-EVEN).

**`depreciate`** (`asset.py:447-486`) — walks the dates, builds an `AssetLine`
per date, decrements a `residual_value` accumulator. Key guards:
- if cumulative depreciation would exceed `depreciable_basis − residual`, the
  last line is *clamped* and the loop breaks (`asset.py:469-473`).
- a `for…else` tail catches floating rounding residue and dumps it on the last
  line (`asset.py:479-482`) — so the table *always* fully depreciates to exactly
  `residual_value`.
- `actual_value` is back-filled on every line after the loop (`asset.py:483-485`).

`get_depreciated_amount` (`asset.py:368-371`) sums `depreciation` over lines
*whose move is posted* — i.e. only realised depreciation counts when recomputing.

### 1.4 Posting depreciation — `get_move` + `create_moves`

**`get_move`** (`asset.py:519-548`) builds one `account.move` per line:
- `debit` line → `product.account_expense_used` (the depreciation **expense**,
  P&L) for `line.depreciation`
- `credit` line → `product.account_depreciation_used` (the **accumulated
  depreciation** contra-asset, BS) for the same amount
- `period = Period.find(company, line.date)`, `origin = line`, journal = the
  asset's asset-journal.

So the standard German entry is `Abschreibungen an Kumulierte Abschreibungen` —
**the asset's gross cost account is never touched by depreciation**; only the
contra-asset accumulates. This is the indirect (`indirekte`) method.

**`create_moves`** (`asset.py:550-573`) — the bulk poster: `create_lines` first
(idempotently extends the table), then for every line with `date <= cutoff` and
`move = None`, build + save + **post** the move, and back-link `line.move`. This
is driven by the `CreateMoves` wizard (`asset.py:791-810`) which the user runs
periodically against all `running` assets.

### 1.5 Disposal / close — `get_closing_move` (`asset.py:575-637`)

When an asset is sold or scrapped, `close` posts a move that:
- credits the **gross asset account** (`product.account_asset_used`, or the
  original supplier-invoice-line account) for the full `value`
- debits **accumulated depreciation** for `get_depreciated_amount() +
  depreciated_amount`
- the difference (`square_amount`) is the gain/loss — booked to
  `account_revenue_used` if a gain, `account_expense_used` if a loss, *unless*
  the caller passes an explicit `account` (the sale invoice does — see §1.7).

This is a clean reversal of the asset off the books with automatic gain/loss
recognition.

### 1.6 Revaluation — `AssetUpdate` / `AssetRevision` (`asset.py:813-1007`)

The `UpdateAsset` wizard lets a `running` asset's `value`, `residual_value`,
`end_date` change mid-life:
- `AssetRevision` (`asset.py:958-1007`) is an immutable audit row recording the
  *new* `value`/`residual_value`/`end_date` plus an `origin` reference
  (`account.invoice.line` — e.g. a capital improvement invoice).
- If `value` changed, the wizard posts an **update move** (`get_move` /
  `get_move_lines`, `asset.py:900-927`) between the depreciation account and a
  counterpart, then **clears and recreates the un-posted lines** so the
  remaining schedule reflects the new basis (`transition_create_lines`,
  `asset.py:945-955`).
- The new move-date must sit between the last posted move and the next
  scheduled depreciation (`UpdateAssetShowDepreciation`, `asset.py:813-829`).

This is genuinely good design: revaluation is *append-only* (a revision row + a
move), and the forward schedule is recomputed rather than retro-edited.

### 1.7 Asset creation from invoice — `account_asset/invoice.py`

`account_asset/invoice.py` extends `account.invoice.line` two ways:
- **Purchase side**: `_account_domain` (`invoice.py:33-38`) — for `in` invoices,
  forces the line's account domain to `type.fixed_asset = True`;
  `on_change_product` (`invoice.py:40-55`) auto-fills the line account with
  `product.account_asset_used` when the product is a depreciable asset. The
  `Asset.supplier_invoice_line` field then back-links, and
  `on_change_supplier_invoice_line` (`asset.py:308-342`) **auto-populates the
  whole asset**: `value` from the line amount (currency-converted),
  `purchase_date`/`start_date` from the invoice date, `end_date` from
  `start_date + product.depreciation_duration months`.
- **Sale side**: an `asset` `Many2One` on the invoice line
  (`invoice.py:11-19`); when that sale's move-lines are generated,
  `get_move_lines` (`invoice.py:77-82`) calls `Asset.close([asset],
  account=self.account, …)` — i.e. **invoicing the sale auto-disposes the
  asset** with the gain/loss routed to the revenue account.

### 1.8 The depreciation-table report — `AssetDepreciationTable` (`asset.py:1010-1170`)

`account.asset.depreciation_table` is a `CompanyReport` rendered from
`asset_table.fodt`. It is the **Anlagenspiegel** (statement of fixed-asset
movements): per-product groups, each asset contributing `start_fixed_value`,
`value_increase`, `value_decrease`, `end_fixed_value` (gross-cost roll-forward),
plus `start_value`, `amortization_increase`, `amortization_decrease`,
`end_value`, `actual_value`, `closing_value` (accumulated-depreciation
roll-forward). The computation is a set of `cached_property`s over
`asset_lines` + `update_lines` filtered to the report window
(`asset.py:1071-1168`). This is exactly the German `Anlagengitter` shape and is
worth studying as a *report definition*, not as code to lift.

### 1.9 Configuration (`account_asset/account.py:10-127`)

`account.configuration` gains `asset_sequence`, `asset_bymonthday`,
`asset_bymonth`, `asset_frequency` — all `MultiValue` (per-company). German
defaults: `bymonthday=-1`, `bymonth=12`, `frequency=monthly`. The
`asset_bymonthday`/`asset_bymonth` knobs are *only* the posting-date convention,
not the depreciation math.

---

## 2. Year-end close — `account/fiscalyear.py` + `account/period.py`

### 2.1 `FiscalYear` (`fiscalyear.py:31-421`)

`account.fiscalyear` is a `Workflow`. States: `open` / `closed` / `locked`
(`fiscalyear.py:46-50`). Transitions: `open→closed`, `closed→locked`,
`closed→open` (`fiscalyear.py:89-93`). It owns `One2Many periods`, a
`move_sequence` (strict — gap-free numbering), and a company.

A SQL exclusion constraint (`fiscalyear.py:69-87`) forbids overlapping date
ranges *and* forbids an `open` year earlier than any other open year
(`open_earlier`) — you cannot leave 2023 open while 2024 is also open. This
enforces sequential closing.

**`FiscalYear.close`** (`fiscalyear.py:345-386`):
1. `cls.lock()` + `Period.lock()` — DB row locks so no new years/periods appear.
2. Refuse if an *earlier* fiscal year is still `open`
   (`msg_close_fiscalyear_earlier`).
3. `Period.close(periods)` — closes every period in the year (see §2.3).
4. For **every account**, compute `get_deferral` and save the resulting
   `account.account.deferral` rows.

**`get_deferral`** (`fiscalyear.py:321-343`) is the crux:
- if the account's type is **not** `deferral=True` (i.e. it's a P&L account) and
  its balance is **not zero** → raise `FiscalYearCloseError`
  (`msg_close_fiscalyear_account_balance_not_zero`).
- if it **is** a deferral account (balance-sheet) → snapshot `debit`, `credit`,
  `line_count`, `amount_second_currency` into a new `Deferral` row.

So `close` **presumes P&L is already zero** — it does not zero P&L itself. That
job belongs to `BalanceNonDeferral` (§2.2), which must be run first.

**`reopen`** (`fiscalyear.py:388-410`): refuses if a *later* year is non-open,
then deletes the deferral rows. **`lock_`** (`fiscalyear.py:412-421`): cascades
`Period.lock_` to all periods; `locked` is terminal (no transition out).

`RenewFiscalYear` (`fiscalyear.py:606-716`) clones the previous year's config +
periods + a fresh sequence — convenience, not semantics.

### 2.2 `BalanceNonDeferral` — the actual P&L→equity rollup (`fiscalyear.py:465-545`)

This wizard is Tryton's "Jahresabschluss" / Saldovortrag posting. The user picks
a `situation` journal, an `adjustment` period, and a debit/credit equity
account. `create_move` (`fiscalyear.py:507-539`):
- searches every account with `deferral=False, type != None, closed != True` —
  i.e. **all P&L accounts**.
- builds one move line per non-zero account that *reverses* its balance
  (`get_move_line`, `fiscalyear.py:474-489`).
- sums the reversals (`amount = Σ debit − credit` = net result) and posts a
  single counterpart line to the chosen equity account
  (`get_counterpart_line`, `fiscalyear.py:491-505`).
- the move's `origin` is the fiscal year, posted in the `adjustment` period at
  the period start date.

This is **exactly kontor's `kontor.closing/close-fiscal-year!`** mechanism — one
balanced closing move, P&L accounts → retained-earnings/equity. Tryton calls the
counterpart account-agnostic ("Credit Account"/"Debit Account"); kontor's
`l10n-de.closing` pins it to SKR04 2900 (Gewinnvortrag).

### 2.3 `Period` close mechanics (`account/period.py`)

`account.period` is a `Workflow`, states `open`/`closed`/`locked`, transitions
`open→closed`, `closed→locked`, `closed→open` (`period.py:91-95`). `type` is
`standard` or `adjustment` (`period.py:56-60`) — adjustment periods are the DE
"period 13" mechanism for year-end true-ups (`BalanceNonDeferral` requires an
`adjustment` period, `fiscalyear.py:438-442`).

**`Period.close`** (`period.py:381-424`):
1. `JournalPeriod.lock()` + `Move.lock()`.
2. For every account whose `end_date` falls in the period — if it has a
   non-zero **cumulated** balance, raise `ClosePeriodError`
   (`msg_close_period_inactive_accounts`). (This is a *de-activation* check, not
   a general "must be zero" rule — it only bites accounts that are being closed
   as of that period.)
3. Refuse if **any move in the period is not `posted`**
   (`msg_close_period_non_posted_moves`) — no drafts allowed past close.
4. Close the journal-periods.

`account_asset` extends this (`account_asset/account.py:179-210`):
`Period.check_asset_line_running` — refuses to close a period if any `running`
asset has a depreciation line *in that period* with no posted move. I.e.
**you cannot close a period with un-posted depreciation** — the close forces you
to run the depreciation poster first.

Date integrity: `check_move_dates` (`period.py:221-248`) — no move may have a
date outside its period; `check_fiscalyear_dates` (`period.py:206-220`) — period
dates must sit inside the fiscal year.

### 2.4 How posted moves interact with closed periods — `account/move.py`

- `account.move` states: `draft` / `posted` (`move.py` selection). A posted move
  is immutable: `check_modification` (`move.py:290-301`) raises `AccessError` on
  any write/delete of a `posted` move (outside a small `_check_modify_exclude`
  set). This is **Tryton's sealing rule** — directly analogous to kontor's
  `:posting/posted-at` + sealing middleware (ADR-007).
- You cannot fix a posted move in place; you `cancel` it, which *copies* it as a
  reversal (`_cancel_default` / `cancel`, `move.py:397-443`). If the original's
  period is `closed`, the cancel/copy is *redirected to the current open period*
  (`move.py:315-342`, `407-411`) with a user warning — Tryton never re-opens a
  period to post a correction; corrections land in the open period.
- `post` (`move.py:447-520`) refuses empty moves and unbalanced moves
  (`Abs(Σ debit−credit) >= currency.rounding`), then stamps `state='posted'` and
  a strict sequence number. Balance validation is `Σ debit = Σ credit` per move
  per company-currency (`validate_move`, `move.py:351-395`) — kontor's
  sum-to-zero, just signed as debit/credit columns.

---

## 3. Financial-statement engine — `account.account.type`

### 3.1 The type tree as the BS/IS classifier (`account/account.py:37-455`)

`TypeMixin` (`account.py:37-130`) defines the shape of both
`account.account.type` and `account.account.type.template`:

- **`statement`** (`account.py:43-51`): `None` / `balance` / `income` /
  `off-balance`. **This single field decides Balance Sheet vs Income
  Statement.** A type with `statement='balance'` is a BS line; `'income'` is a
  P&L line.
- **`assets`** (`account.py:52-56`): boolean, only meaningful when
  `statement='balance'` — distinguishes the asset side from the
  liability/equity side of the balance sheet.
- Role booleans, each domain-gated to a compatible `statement`:
  `receivable` / `payable` / `debt` (balance + asset/liability side),
  `stock` (balance, not off-balance), `revenue` / `expense` (income only).
- The type is a **tree** (`tree(separator='\\')`, `sequence_ordered()`) —
  `parent`/`childs`, and the parent's `statement` is inherited
  (`on_change_parent`, `account.py:277-280`; the parent domain forbids mixing
  `off-balance` with on-balance, `account.py:222-233`).

`Type.get_amount` (`account.py:289-341`) is the report aggregator: it walks the
type subtree, sums member-account balances, and **flips the sign for
`statement='balance' AND assets`** (`account.py:339-340`) so assets present
positive. `account.account` points at a `type` (and optionally a `debit_type` /
`credit_type` for accounts that flip sides, `account.py:856-882`).

There is **no separate "report layout" entity** for BS/IS in core Tryton — the
type tree *is* the layout. `BalanceSheetContext` / `IncomeStatementContext`
(`account.py:2580-2669`) only supply the date/period window and a "posted only"
toggle; the actual statement is rendered by walking the type tree.

### 3.2 How the statement structure is localised

The type tree ships **inside each chart-of-accounts template**. The German chart
`account/account_chart_de.xml` defines its own ~40-node
`account.account.type.template` tree with German labels — e.g.:

- `account_type_template_root_de` "Universal-Kontentypenplan" (`statement=None`)
- `account_type_template_assets_de` "Aktiva" (`statement=balance`)
  - `account_type_template_non_current_assets_de` "Anlagevermögen" (`assets=True`)
    - `account_type_template_property_de` "Sachanlagen und Ausrüstung" (`assets=True`)
    - `account_type_template_intangible_assets_de` "Immaterielle Vermögenswerte"
  - `account_type_template_current_assets_de` "Umlaufvermögen"
- `account_type_template_current_liabilities_de` "Passiva"
- `account_type_template_depreciation_de` "Abschreibungen / Amortisation"
  (`statement=income`, parent = "Aufwendungen") — `account_chart_de.xml:238-243`

So **the financial-statement structure is per-chart**: a different country's
chart template ships a different type tree. `TypeTemplate.create_type`
(`account.py:173-208`) instantiates the tree per company at chart-install time.
The German chart's ~135 `account.account.template` rows each `ref` a type — e.g.
the SKR-style accounts `1.6.0`/`1.6.1`/`1.6.2` "Kumulierte Abschreibungen …" all
point at `account_type_template_depreciation_de` (`account_chart_de.xml:365-372,
650-665`).

### 3.3 `AccountDeferral` — the carry-forward record (`account/account.py:1647-1789`)

`account.account.deferral` is the immutable per-(account, fiscalyear) snapshot
written by `FiscalYear.close`: `debit`, `credit`, `line_count`,
`amount_second_currency`, plus a computed cumulative `balance`. `write` is
*always* refused (`account.py:1788-1789`) — deferrals are append-only, deleted
only by `reopen`. `Account.get_credit_debit` (`account.py:1131-…`) folds these
deferral rows into a closed year's balances so reports on a closed year still
work. The `deferral` flag on a *type* (function field, `account.py:521-526`) is
`True` for `statement in {balance, off-balance}` — i.e. only balance-sheet
accounts carry forward; P&L does not (it's been zeroed).

---

## 4. The German chart — what `account_asset/account_chart_de.xml` ships

The asset module's German fragment is **astonishingly thin** — 13 lines total:

```xml
<record id="account.account_type_template_prepaid_expenses_de"
        model="account.account.type.template">
  <field name="fixed_asset" eval="True"/>
</record>
<record id="account.account_type_template_property_de"
        model="account.account.type.template">
  <field name="fixed_asset" eval="True"/>
</record>
```

That is the *entire* German asset localisation: it flags exactly **two**
existing type templates (`property_de` = "Sachanlagen und Ausrüstung",
`prepaid_expenses_de` = a prepaid-expense type) as `fixed_asset = True`. The
`fixed_asset` boolean (`account_asset/account.py:130-165`) is what the product's
`account_asset` / `account_depreciation` domains filter on
(`account_asset/product.py:14-32, 64-75`) — so it just *gates which accounts can
be picked* as the gross-asset and accumulated-depreciation accounts. The
intangible-assets type is **not** flagged — Tryton's asset module is implicitly
scoped to tangible `Sachanlagen`.

The real German asset *accounts* live in the base `account/account_chart_de.xml`
(not the asset module): a ~135-account SKR-flavoured chart with
`account_type_template_property_de`-typed accounts for fixed assets,
`account_type_template_depreciation_de`-typed accounts both for the BS
contra-asset ("Kumulierte Abschreibungen", codes `1.6.x`) **and** the P&L expense
("Miete, Abschreibung, Amortisation und Wertminderung", code `5.1.4`).

**What this tells us for kontor:** Tryton bakes *almost nothing* country-specific
into the asset engine. There is **no German AfA-table, no useful-life defaults,
no §7 EStG degressive option, no GWG (low-value-asset) immediate-expensing
threshold**. The German user *still* sets `value`, `start_date`, `end_date`
(useful life), `residual_value`, and `frequency` **by hand** on every asset (or
via `product.depreciation_duration`, a single integer of months on the product
template, `account_asset/product.py:83-89`). The localisation layer's *only*
job is (a) the type tree and accounts, (b) the `fixed_asset` flag, (c) the
posting-date config defaults.

---

## 5. Tax-law reflection — book vs tax depreciation

### 5.1 Tryton does **not** separate book vs tax depreciation

This is the single biggest gap for a German (or any) user. There is **one
`depreciation_method`, one schedule, one set of moves per asset**. An asset
cannot carry a commercial-balance (Handelsbilanz) schedule *and* a tax-balance
(Steuerbilanz) schedule simultaneously. The `account_asset` module ships no
parallel-ledger integration at all.

A German user wanting Handelsbilanz ≠ Steuerbilanz under stock Tryton has only
bad options:
- model the asset **twice** (two `account.asset` records, two products, two
  account sets) — ugly, double-counts the gross asset;
- post the *difference* as manual adjustment moves into a separate journal —
  un-automated, error-prone;
- run the asset for the *commercial* schedule and track the tax delta entirely
  outside the system.

Tryton's general ledger *does* have parallel-book capability via
`account.account` `debit_type`/`credit_type` and multi-company, but the **asset
module is not wired into it**. This is precisely where kontor's `:ledger/*`
substrate (ADR-021 — per-(entity, ledger, commodity) sum-to-zero, the SAP
`RLDNR`-on-line pattern) is *structurally ahead of Tryton*: kontor can run two
depreciation schedules into `primary` and `tax` ledgers from one `:asset`
entity.

### 5.2 Jurisdiction-specific rules are **not** reflected

As §4 establishes — no AfA-Tabellen, no useful-life library, no GWG threshold,
no Sammelposten/Poolabschreibung, no degressive method, no half-year/mid-quarter
US conventions. Tryton's stance is "the accountant knows the useful life; we do
the arithmetic." The *only* localisation hook is the `fixed_asset` type flag and
the product's `depreciation_duration`.

### 5.3 Financial-statement localisation **is** properly layered

In contrast to depreciation rules, the *statement structure* is cleanly
localised: the `account.account.type` tree ships per chart template (§3.2), and
`account_chart_de.xml` ships a full German type tree with German labels and the
correct `statement`/`assets`/role-flag assignments. This is the part of Tryton's
design kontor should mirror most closely.

---

## 6. Tryton's design — strengths and weaknesses for this domain

### 6.1 Genuinely better than Odoo / worth borrowing

1. **Statement classification is pure data.** `statement` + role booleans on a
   typed tree, no hard-coded report logic. kontor's `:account/type` keyword is
   thinner; Tryton's `statement`/`assets`/`receivable`/`payable`/`revenue`/
   `expense`/`stock`/`debt`/`fixed_asset` lattice is a better target.
2. **The two-mechanism close is correct.** Separating "zero the P&L"
   (`BalanceNonDeferral`, a *posted move*) from "freeze + snapshot the BS"
   (`FiscalYear.close` → immutable `Deferral` rows) is exactly right. kontor
   already does the first half (`kontor.closing`); the *deferral snapshot* is
   the missing half.
3. **`AccountDeferral` is append-only and `write` is hard-refused.** Closed-year
   balances are immutable records, not recomputation. Audit-clean.
4. **Asset revaluation is append-only** — `AssetRevision` + an update move +
   forward-recompute, never a retro-edit of posted lines. Matches kontor's
   ADR-007 philosophy.
5. **Close is *gated by* readiness.** `Period.close` refuses drafts and (via the
   asset module) un-posted depreciation; `FiscalYear.close` refuses non-zero
   P&L and out-of-order years. The close is a *checked* transition, not a flag
   flip — this matches kontor research note 07's "pre-close validation hook".
6. **The depreciation table is materialised, inspectable, and re-derivable.**
   `create_lines`/`clear_lines` let you preview before `run`; re-running only
   extends the un-posted tail. Posted lines are sacrosanct.
7. **Invoice→asset and sale→disposal are automatic** and bidirectional
   (`account_asset/invoice.py`) — the asset register is not a parallel
   data-entry burden.
8. **Prorata-temporis is built in** with a deliberate 365-day normalisation
   (`normalized_delta`) so depreciation is calendar-stable.

### 6.2 Where Tryton under-delivers

1. **One depreciation method.** `linear` only. No degressive/declining-balance,
   no sum-of-years-digits, no units-of-production, no MACRS. Real-world
   Abschreibung needs at least linear + geometrisch-degressiv (§7 EStG).
2. **No book-vs-tax parallel schedule** (§5.1) — the headline gap.
3. **No mass/group depreciation** — every asset is depreciated and posted
   individually; the only grouping is the *report*. No asset categories driving
   defaults beyond one integer on the product.
4. **No impairment / Teilwertabschreibung** as a first-class event — only the
   generic `AssetUpdate` revaluation, which is a value change, not a tested
   impairment with its own accounts.
5. **No partial disposal** — `close` disposes the *whole* asset; you cannot
   retire 3 of 10 units.
6. **No componentisation** — one asset = one depreciation profile; can't
   depreciate the engine and the airframe separately.
7. **`FiscalYear.close` presumes P&L is already zero** but does not *run* the
   rollup or even *prompt* for it — a user who forgets `BalanceNonDeferral`
   just gets a confusing `account_balance_not_zero` error. The two mechanisms
   are not sequenced for the user.
8. **The asset has no period-state.** "Which depreciation is posted through
   when" is derived from line/move states every time — fine at small scale,
   a query burden at large scale (Tryton adds a partial SQL index on
   `state in (draft, running)` to compensate, `asset.py:208-212`).
9. **No GWG / low-value-asset shortcut, no Sammelposten pool** — every trivial
   asset goes through the full machinery.
10. **`depreciation_duration` is one global integer per product** — no
    per-jurisdiction useful-life table, no effective-dating of rate changes.

---

## 7. Concrete mapping hints — Tryton patterns → kontor substrate

### 7.1 What needs a new `:asset` entity (Tryton's `account.asset`)

kontor's `:schedule` (ADR-032) models "a recurring posting fires on these
dates" but explicitly **does not compute amounts** and has **no notion of a
depreciable thing**. A fixed-asset register needs a genuine new entity. Proposed
`:asset/*` namespace (new namespace ⇒ new ADR):

| kontor `:asset/*` | Tryton source | Notes |
|---|---|---|
| `:asset/code` | `Asset.number` | sequence-assigned |
| `:asset/product` *(opt)* | `Asset.product` | or a direct account ref — kontor has no `product` entity in-kernel; the *companion* may carry it. The kernel `:asset` should reference **accounts directly** (gross-asset, accumulated-depreciation, expense, gain, loss) rather than depend on a product module. |
| `:asset/acquisition-cost` (Money) | `Asset.value` | |
| `:asset/depreciated-amount-opening` (Money) | `Asset.depreciated_amount` | for mid-life imports |
| `:asset/residual-value` (Money) | `Asset.residual_value` | Restwert/salvage |
| `:asset/purchase-date`, `:asset/start-date`, `:asset/end-date` | same | useful life = end−start |
| `:asset/method` (keyword) | `Asset.depreciation_method` | kontor should ship **at least** `:linear` and `:declining-balance`; make it a **provider seam** (see §7.3) |
| `:asset/frequency` (keyword) | `Asset.frequency` | `:monthly`/`:yearly` — *reuse `:schedule/frequency` vocabulary* |
| `:asset/state` (facet keyword) | `Asset.state` | `:draft`/`:running`/`:closed` — route through `kontor.status-machine` (ADR-034) so transitions get `:status-history` for free; Tryton's hand-rolled `Workflow` is exactly what kontor's status machine generalises |
| `:asset/gross-account`, `:asset/accumulated-depreciation-account`, `:asset/expense-account`, `:asset/gain-account`, `:asset/loss-account` | `product.account_*_used` | direct account refs |
| `:asset/origin-transaction` *(opt)* | `Asset.supplier_invoice_line` | back-link to the acquisition tx |
| `:asset/disposal-transaction` *(opt)* | `Asset.move` | the closing/disposal tx |

And `:asset-line/*` (Tryton's `AssetLine`) — the materialised depreciation
table:

| kontor `:asset-line/*` | Tryton | Notes |
|---|---|---|
| `:asset-line/asset` | `AssetLine.asset` | ref |
| `:asset-line/sequence` | (implicit by date) | give it an explicit ordinal for `[asset, sequence]` composite identity, mirroring `:schedule-occurrence` |
| `:asset-line/date` | `AssetLine.date` | |
| `:asset-line/depreciation` (Money) | `AssetLine.depreciation` | the period charge |
| `:asset-line/depreciable-basis` (Money) | `AssetLine.depreciable_basis` | |
| `:asset-line/accumulated-depreciation` (Money) | `AssetLine.accumulated_depreciation` | |
| `:asset-line/carrying-amount` (Money) | `AssetLine.actual_value` | Buchwert = cost − accumulated |
| `:asset-line/transaction` *(opt)* | `AssetLine.move` | back-ref to the posted depreciation tx; null until posted |

### 7.2 How `:asset` relates to `:schedule` (ADR-032)

Two viable designs; the research recommends **the asset *owns* its table, and
optionally drives a `:schedule` for the posting cadence**:

- The depreciation *table* (`:asset-line` rows) is the asset's own materialised
  artifact — directly mirroring Tryton's `create_lines`/`run`. This is *not* the
  same as `:schedule-occurrence`: the table is computed up-front (the full
  forward schedule), whereas `:schedule-occurrence` only records *fired*
  occurrences. So `:asset-line` is a **new shape**, closer to Tryton's
  `AssetLine` than to `:schedule-occurrence`.
- The *posting cadence* ("post all depreciation moves due ≤ date") can reuse the
  `:schedule` frequency vocabulary and the bulk-poster pattern from
  `Asset.create_moves`. But a `:schedule` is not strictly required — the asset's
  own line table already carries the dates.
- **Recommendation:** add `:asset` + `:asset-line` as first-class entities;
  *reuse* `:schedule`'s frequency arithmetic (`kontor.schedule`'s
  `inst->local-date` / frequency helpers) for `compute-move-dates`; do **not**
  force the asset through `:schedule-occurrence`.

### 7.3 The depreciation-method engine — a provider seam

Tryton hard-codes `linear`. kontor's culture is provider protocols
(`TaxProvider`, `CostingProvider`, `EInvoiceProvider`). A
**`DepreciationProvider`** protocol is the natural fit:

```clojure
(defprotocol DepreciationProvider
  (compute-schedule [this asset]
    "Given an :asset entity map, return an ordered seq of
     {:date :depreciation :accumulated :carrying-amount} — the table.")
  (provider-id [this]))
```

Kernel ships `LinearProvider` (port Tryton's prorata-temporis + 365-day
normalisation + the round-residue-onto-last-line guard from `asset.py:447-486`
— *as a pattern*, re-implemented) and `DecliningBalanceProvider`. l10n modules
add jurisdiction methods (`l10n-de` could add a §7 EStG geometrisch-degressiv
provider, GWG immediate-expense, Sammelposten pool). This makes the headline
Tryton weakness (§6.1) a kontor strength.

### 7.4 Book-vs-tax — ride the `:ledger` substrate (ADR-021)

This is where kontor *structurally beats Tryton*. One `:asset` entity can carry
**two methods**: a `:asset/book-method` and a `:asset/tax-method` (or a small
`:asset-valuation/*` join: per-(asset, ledger) → method + schedule). The
depreciation poster then emits postings tagged `:posting/ledger :primary` for
the commercial schedule and `:posting/ledger :tax` for the fiscal schedule, both
in one transaction, each balancing independently. Handelsbilanz vs Steuerbilanz
falls out of the existing multi-ledger invariant — no new mechanism. **Capture
this as the key ADR design call**: should the v1 `:asset` support parallel
schedules, or ship single-schedule first and add the `:asset-valuation` join in
a follow-up? (AskUserQuestion candidate.)

### 7.5 Year-end close — kontor already has the rollup; add the deferral snapshot

- `kontor.closing/close-fiscal-year!` ≈ Tryton's `BalanceNonDeferral`
  (`fiscalyear.py:465-545`) — both post **one balanced closing move, P&L →
  equity**. kontor's existing mechanism is the correct half. ✓
- **Missing half:** Tryton's `FiscalYear.close` + `AccountDeferral`
  (`fiscalyear.py:345-386`, `account.py:1647-1789`) — the *immutable
  per-(account, fiscal-year) balance snapshot*. kontor's `closing.clj` docstring
  says opening balances are *derived* ("balance.clj computes balances
  cumulatively"), which is correct and arguably cleaner than Tryton's snapshot —
  **but** a deferral-style snapshot has two values kontor lacks: (a) it makes a
  closed year's reported figures *immutable and fast* without re-walking all
  history, and (b) it's the natural anchor for the audit chain ("these were the
  closing balances we filed"). **Recommendation:** consider a
  `:balance-assertion`-flavoured (kontor already has `:balance-assertion/*`!)
  or new `:fiscal-year-deferral/*` snapshot written at year-close. The existing
  `:balance-assertion/*` namespace may already be the right home — investigate
  in the implement step.
- **Fiscal-year entity:** kontor has `kontor.period` (open/close/lock/seal) but
  research note 07 explicitly *deferred* a fiscal-year entity. Tryton's
  `FiscalYear` shows the value: the `open_earlier` / `close earlier year first`
  ordering constraint, the `move_sequence` per year, and `RenewFiscalYear`
  cloning. Stage L′ should reconsider whether a thin `:fiscal-year/*` entity
  (grouping periods, carrying the close-ordering invariant) is now warranted.
- **Close gating:** Tryton's `Period.close` refusing drafts + un-posted
  depreciation (`period.py:411-420`, `account_asset/account.py:181-210`) is
  exactly kontor research note 07's "pre-close validation hook". The asset
  module's `check_asset_line_running` is a concrete pattern: **`kontor-asset`
  should register a pre-close check that refuses to close a period with
  un-posted depreciation lines.** kontor's `:side-effect-intent` / validation
  middleware (ADR-041/011) is the hook.

### 7.6 Financial-statement engine — enrich `:account/type`

Tryton's `account.account.type` tree (§3) is richer than kontor's flat
`:account/type` keyword. `kontor.financial-statements` currently classifies by
**account-code prefix** supplied in a statement definition — which works but
puts BS/IS membership in the *report definition* rather than on the *account*.
Tryton's lesson: put `statement` (`:balance`/`:income`/`:off-balance`) and the
role booleans (`assets`, `receivable`, `payable`, `revenue`, `expense`, `stock`,
`debt`, `fixed_asset`) **on the account (or an account-type entity)**, and let
the statement engine walk that. Two concrete additions for Stage L′:

- a `:account/fixed-asset?` boolean (Tryton's `fixed_asset`) — gates which
  accounts an `:asset` may use as gross-asset / accumulated-depreciation
  accounts (Tryton: `product.py:14-32` domain `type.fixed_asset = True`).
- consider whether `:account/type` should grow from a flat keyword into a small
  typed entity (`:account-type/*`) carrying `statement` + role flags, so the
  l10n charts ship a German type tree the way `account_chart_de.xml` does
  (`account_type_template_*_de`). This is a bigger change — flag it as a design
  call, not a foregone conclusion. The current code-prefix engine can coexist.

### 7.7 Disposal + revaluation patterns to port

- **Disposal** (`get_closing_move`, `asset.py:575-637`): a `kontor-asset`
  `dispose-asset!` helper builds one transaction — credit gross-asset, debit
  accumulated-depreciation, plug gain/loss to `:asset/gain-account` /
  `:asset/loss-account` (or a caller-supplied account when the disposal is via a
  sale invoice). Sale-driven disposal (`invoice.py:77-82`) is a companion
  concern.
- **Revaluation** (`AssetUpdate` + `AssetRevision`, `asset.py:813-1007`): port
  the *append-only* shape — a `:asset-revision/*` row (immutable, with an
  `:origin` ref) + an update transaction + **clear-and-recompute the un-posted
  tail**. Do not retro-edit posted `:asset-line` rows — this is already kontor's
  ADR-007 instinct.
- **Posted-line immutability:** Tryton's `AssetLine.move` once posted is
  sealed by the move's own `check_modification`. In kontor this is *free* —
  the depreciation transaction's postings get `:posting/posted-at` and the
  sealing middleware (ADR-007) protects them. `:asset-line` rows whose
  `:asset-line/transaction` is set should likewise be treated as frozen by the
  `clear-lines` helper (only delete un-posted lines, exactly
  `Asset.clear_lines`, `asset.py:502-512`).

### 7.8 The Anlagenspiegel report

Tryton's `AssetDepreciationTable` (`asset.py:1010-1170`) is a *report
definition* — gross-cost roll-forward + accumulated-depreciation roll-forward
per asset-group. This maps onto kontor's existing **declarative report engine**
(`kontor.report`) — `kontor-asset` should ship an Anlagenspiegel report
*definition*, not bespoke code. The German `Anlagengitter` column set
(Anschaffungskosten / Zugänge / Abgänge / Umbuchungen / kumulierte
Abschreibungen / Buchwert) is the target layout; l10n-de supplies the exact
labels.

---

## 8. Open design calls for the implement step (AskUserQuestion candidates)

1. **Parallel schedules in v1?** Ship `:asset` single-schedule and add an
   `:asset-valuation` per-ledger join later, or design parallel
   (book + tax) from day one given kontor's `:ledger` substrate already supports
   it? (§7.4)
2. **`:fiscal-year/*` entity?** Note 07 deferred it; Tryton's `FiscalYear`
   close-ordering invariant + per-year sequence make a fresh case. (§7.5)
3. **Deferral snapshot — reuse `:balance-assertion/*` or new
   `:fiscal-year-deferral/*`?** kontor's derived-balance model may not *need*
   the snapshot, but immutability + audit-anchor + report-speed argue for it.
   (§7.5)
4. **`:account/type` — keep flat keyword + code-prefix statements, or grow a
   typed `:account-type/*` tree with `statement` + role flags per Tryton?** (§7.6)
5. **Depreciation methods in kernel vs l10n.** Kernel ships
   `:linear` + `:declining-balance` via `DepreciationProvider`; l10n-de adds §7
   EStG degressiv + GWG + Sammelposten. Confirm the kernel/l10n split. (§7.3)

---

## 9. File:line index (every Tryton claim above)

**`account_asset/asset.py`** — `normalized_delta` 34-51; `Asset` model 54-737;
fields `value` 114-120, `depreciated_amount` 121-130, `depreciating_value`
131-134/297-302, `residual_value` 135-143, `depreciation_method` 161-167,
`frequency` 168-175, `state` 176-180, `lines` 181-182, `move` 183-186,
`update_moves` 187-194, `revisions` 196-197; transitions/buttons 213-245;
`get_depreciated_amount` 368-371; `compute_move_dates` 373-405;
`compute_depreciation` 407-445; `depreciate` 447-486; `create_lines` 488-500;
`clear_lines` 502-512; `get_move` 519-548; `create_moves` 550-573;
`get_closing_move` 575-637; `set_number` 639-657; `draft` 659-667; `run`
669-674; `close` 676-694; `AssetLine` 740-771; `AssetUpdateMove` 774-778;
`CreateMoves` wizard 791-810; `UpdateAsset` wizard 832-955; `AssetRevision`
958-1007; `AssetDepreciationTable` 1010-1170; `PrintDepreciationTable` 1190-1221.

**`account_asset/account.py`** — config knobs `asset_bymonthday`/`asset_bymonth`/
`asset_frequency` 10-32; `Configuration` 44-83; `ConfigurationAssetDate`
defaults (`-1`, `12`) 106-117; `ConfigurationAssetFrequency` default `monthly`
120-127; `AccountTypeMixin.fixed_asset` 130-165; `Move._get_origin` 168-176;
`Period.check_asset_line_running` + `close` override 179-210; `Journal` asset
type 213-219.

**`account_asset/product.py`** — `Category.account_depreciation`/`account_asset`
12-32; `account_used` properties 41-49; `Template.depreciable` /
`depreciation_duration` 80-89; `account_*_used` 91-99.

**`account_asset/invoice.py`** — `InvoiceLine.asset` 11-19; `_account_domain`
fixed-asset filter 33-38; `on_change_product` auto-account 40-55;
`get_move_lines` → `Asset.close` 77-82.

**`account_asset/account_chart_de.xml`** — entire file 1-13 (flags
`prepaid_expenses_de` + `property_de` types `fixed_asset=True`).

**`account/fiscalyear.py`** — `FiscalYear` 31-421; states 46-50; SQL constraints
(`dates_overlap`, `open_earlier`) 69-87; transitions 89-93; `find` 269-319;
`get_deferral` 321-343; `close` 345-386; `reopen` 388-410; `lock_` 412-421;
`BalanceNonDeferral` wizard 465-545 (`get_move_line` 474-489,
`get_counterpart_line` 491-505, `create_move` 507-539); `RenewFiscalYear`
606-716.

**`account/period.py`** — `Period` 27-441; states 39-43; `type`
standard/adjustment 56-60; transitions 91-95; `check_fiscalyear_dates` 206-220;
`check_move_dates` 221-248; `find` 250-301; `close` 381-424; `reopen` 426-431;
`lock_` 433-437.

**`account/account.py`** — `TypeMixin` (`statement`, `assets`, role booleans)
37-130; `TypeTemplate` + `create_type` 133-208; `Type` + `get_amount`
(sign-flip for asset types) 211-341; `Account.debit_type`/`credit_type`
856-882; `AccountDeferral` (immutable, `write` refused) 1647-1789;
`TrialBalance` 2547-2577; `BalanceSheetContext` 2580-2597;
`IncomeStatementContext` 2627-2669.

**`account/move.py`** — `check_modification` (posted = immutable) 290-301;
`copy` redirecting closed-period copies 303-349; `validate_move` (balance
check) 351-395; `_cancel_default` / `cancel` 397-443; `post` (empty/unbalanced
guards, sequence stamp) 447-520.

**`account/account_chart_de.xml`** — type tree `account_type_template_*_de`
(root 6-9, assets 11-15, non_current_assets 69-74, property 76-81,
intangible_assets 90-95, current_liabilities 125-129, depreciation 238-243);
~135 `account.account.template` rows; depreciation-typed accounts `1.6.0`/
`1.6.1`/`1.6.2` at 365-372/650-665, P&L depreciation `5.1.4` at 968-975.

**`account_asset/doc/design.rst`** — module design narrative 1-156.
