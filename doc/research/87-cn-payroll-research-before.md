# 87 — CN payroll research-before (Stage R C11 — kontor-payroll-cn)

*Date: 2026-05-18. Synthesis of regulator publications + market-pain
research feeding ADR-085. Companion to notes 82 (DE-DATEV), 83 (US-ADP),
84 (CA-CRA).*

## 1. Scope

The CN payroll adapter implements the four `PayrollProvider` shapes for
the People's Republic of China: gross-to-net is **engine-authoritative**
(用友 Yonyou NC, 金蝶 Kingdee K/3 + Cloud, 北森 Beisen, 薪人薪事
Salaryman, Workday China). kontor consumes the engine's per-period
output and produces balanced ASBE-aligned GL postings plus the canonical
audit-docs for **个税申报** (IIT filing) and **五险一金对账**
(SI+HF reconciliation).

## 2. Statutory primitives (the four bodies)

### 2.1 个人所得税 — Individual Income Tax (IIT)

- Authority: **State Taxation Administration (STA / 国家税务总局)**.
- Filing portal: **自然人电子税务局** (Natural Person Electronic Tax
  Bureau) — both desktop (Windows) and Web variants.
- Computation: since 2019-01-01 the **comprehensive-income cumulative
  method** (累计预扣预缴) applies to 综合所得 (wages, labor service,
  authorship, royalties). Monthly withholding is based on
  year-to-date cumulative income minus cumulative deductions:
  `withhold_M = max(0, taxable_YTD * rate - quick_deduction - withheld_YTD_prev)`.
- Annual reconciliation (**综合所得汇算清缴**) runs between March 1
  and June 30 of the following year — the EMPLOYEE files, not the
  employer. The employer's role ends at year-end statement issuance.
- IIT brackets / quick deductions are public regulation (个人所得税法
  实施条例 2019 — Article 14) but versioned (last touched 2024-04). 
  kontor does NOT bundle.

### 2.2 五险一金 — Five Insurances + One Fund

The bundled per-period social-insurance + housing-fund deductions. Per
city of employment (per the **employee's** city, which is typically
where the work is actually performed). 200+ cities, each with its own
**rate table + base-cap / base-floor** that re-bases annually (June or
July depending on city), so the rate table is consumer-supplied.

| Insurance / Fund | 中文 | Employer side | Employee side | Notes |
|---|---|---|---|---|
| Pension | 养老保险 | 16% (national) | 8% | Bracket: 60%-300% of city avg wage |
| Medical | 医疗保险 | 6–11% city | 2% | Plus 个人账户; **bundled** with 生育 since 2019 in most cities |
| Unemployment | 失业保险 | 0.5–1% | 0.5% | |
| Work injury | 工伤保险 | 0.2–1.6% (8 risk classes) | — | Employer-only |
| Maternity | 生育保险 | 0.5–0.8% | — | Employer-only; merged with 医保 in most cities since 2019 (国发〔2019〕10号) |
| Housing fund | 住房公积金 | 5–12% (per city + per employer policy) | 5–12% (mirror) | Both sides match the % |

**Per-city rate variance** (illustrative — June 2025 figures):

| City | Pension ER | Pension EE | Medical ER | Medical EE | HF ER+EE |
|---|---|---|---|---|---|
| 北京 (Beijing) | 16% | 8% | 9.8% | 2% | 12% + 12% |
| 上海 (Shanghai) | 16% | 8% | 9.5% | 2% | 7% + 7% (default) |
| 深圳 (Shenzhen) | 14–15% | 8% | 5.2% (基本) | 2% | 5–12% bilateral |
| 广州 (Guangzhou) | 14% | 8% | 5.5% | 2% | 5–12% |
| 杭州 (Hangzhou) | 14% | 8% | 9.9% | 2% | 12% + 12% |
| 成都 (Chengdu) | 16% | 8% | 8% | 2% | 7% + 7% |

These figures change. kontor stores `:cn-payroll/social-insurance-city`
on `:employment/jurisdiction-specific-codes` and lets the engine apply
the right table.

### 2.3 企业年金 — Enterprise Annuity (out of scope v1)

Voluntary employer + employee retirement plan (财政部 + 人社部 — 企业
年金办法 2018). CAS treatment: defined-contribution with employer + 
employee mirror percentages, vesting schedules, asset-management fees. 
**Deferred to v2** — note that the protocol surface is open-set so a 
consumer adding `:component-kind :ee-enterprise-annuity` + 
`:er-enterprise-annuity` requires no kontor change.

### 2.4 年终奖 — Annual Bonus Special Tax Treatment

Per 财税〔2018〕164号 and the **2027-extension** (extended again by
财政部 + 国家税务总局 第30号 公告 2023): until 2027-12-31, employees
may **elect** between two IIT treatments for the year-end bonus:

| Method | Formula | When better |
|---|---|---|
| (A) 单独计税 / Separate | `tax = bonus * rate(bonus/12) - quick_deduction(bonus/12)` | Bonus large vs base; few deductions |
| (B) 并入综合所得 / Combined | Bonus folded into YTD income; cumulative method computes | Bonus small + many 专项附加扣除 deductions |

kontor does NOT compute IIT (engine does). What kontor DOES is:

1. Carry the bonus as a **separate `:annual-bonus`** component-kind so
   the audit-doc can break it out.
2. Provide the **accrual primitive** for monthly accrual toward an
   expected year-end bonus (`accrue-annual-bonus-tx-data`).
3. Provide the **emit primitive** for the IIT filing audit-doc with
   the bonus-method field (`:single` or `:combined`) recorded as part
   of the payload (consumer's tax-prep engine reads).

## 3. CN-specific wage-type vocabulary (the `:component-kind` set)

Open-set per ADR-075. The CN canonical kinds:

| `:component-kind` | 中文 | Side | Notes |
|---|---|---|---|
| `:base-wage` | 基本工资 | EE+ | The default earning. |
| `:performance-bonus` | 绩效工资 | EE+ | Monthly performance. |
| `:overtime` | 加班费 | EE+ | 1.5×/2×/3× per 劳动法 §44. |
| `:annual-bonus` | 年终奖 | EE+ | Special tax treatment — note 2.4. |
| `:allowance` | 补贴 | EE+ | Meal / transport / housing allowance. |
| `:taxable-benefit` | 应税补贴 | EE+ | Folded into IIT base. |
| `:iit-withheld` | 个人所得税 | EE− | Cumulative method (note 2.1). |
| `:ee-pension` | 养老保险-个人 | EE− | 8% standard. |
| `:ee-medical` | 医疗保险-个人 | EE− | 2% standard. |
| `:ee-unemployment` | 失业保险-个人 | EE− | 0.5% standard. |
| `:ee-housing-fund` | 住房公积金-个人 | EE− | 5–12% bilateral. |
| `:er-pension` | 养老保险-单位 | ER+ | Employer expense + payable. |
| `:er-medical` | 医疗保险-单位 | ER+ | Includes 生育 in 2019+ merger cities. |
| `:er-unemployment` | 失业保险-单位 | ER+ | |
| `:er-work-injury` | 工伤保险-单位 | ER+ | Employer-only. |
| `:er-maternity` | 生育保险-单位 | ER+ | Only emitted in cities that still split from 医保. |
| `:er-housing-fund` | 住房公积金-单位 | ER+ | |
| `:annual-bonus-accrual` | 年终奖累计 | ER+ | Monthly 1/12 accrual to liability. |

Carry-only (T4-style, for audit-doc only):

| `:component-kind` | 中文 | Posts? | Notes |
|---|---|---|---|
| `:si-base` | 社保基数 | no | The base used by SI calc this period. |
| `:hf-base` | 公积金基数 | no | Often DIFFERENT from SI base. |
| `:cumulative-taxable-ytd` | 累计应纳税所得额 | no | For IIT-method audit-doc. |

## 4. CN CoA wage-account map (CAS / ASBE 2006 + ASSBE)

The canonical liability bucket is **应付职工薪酬 (2211)** with six
MOF-canonical sub-accounts per CAS 9 — Employee Compensation (财会
〔2014〕8号):

| ASBE sub-account | Path | Role |
|---|---|---|
| 2211.01 工资 | Liabilities:EmployeeComp:Wages | Net wages payable |
| 2211.02 职工福利 | Liabilities:EmployeeComp:Welfare | Welfare benefits |
| 2211.03 社会保险费 | Liabilities:EmployeeComp:SI | All five insurances |
| 2211.04 住房公积金 | Liabilities:EmployeeComp:HF | Housing fund |
| 2211.05 工会经费 | Liabilities:EmployeeComp:Union | Trade union fund (out of scope v1) |
| 2211.06 职工教育经费 | Liabilities:EmployeeComp:Education | Worker education fund (out of scope v1) |

Expense side per department:

| Code | Path | Role |
|---|---|---|
| 6601 | Expense:Selling:Wages | Wage expense — Sales dept |
| 6602 | Expense:Admin:Wages | Wage expense — Admin dept |
| 6603 | Expense:Manufacturing:Wages | Wage expense — Manufacturing (4105) |
| 4101.xx | Expense:Production:DirectLabor | Direct labor (manufacturers) |

The l10n-cn module ships 2211 + 5602 (Selling) + 5603 (Admin). The
**payroll-cn extension** ships the 2211 sub-accounts + an Admin-default
wage-expense account when the consumer doesn't have a department split
(典型 SMB case).

Other relevant accounts:

| Code | Path | Role |
|---|---|---|
| 2221.xx | Liabilities:Tax:IIT | IIT withholding payable — sub-account of 应交税费 |

## 5. The engines

Per the market-pain survey:

| Engine | Format | Notes |
|---|---|---|
| 用友 NC / NCC / U8+ | XLS/XLSX/CSV export | Largest enterprise share. Per-customer chart of accounts mapping; column layout configurable. |
| 金蝶 K/3 + Cloud | XLS/XLSX/CSV export | Major SMB/MM share. Similar config-driven layout. |
| 北森 (Beisen) | API + CSV | Mid-market SaaS HRM with payroll module. |
| 薪人薪事 (Salaryman) | API + Excel | Mid-market SaaS HR + payroll. |
| Workday China | XML / OData | Large-enterprise China-local Workday tenant. |

For v1 we ship **YonyouCsvProvider + KingdeeCsvProvider + BeisenCsvProvider**
following the **config-driven column-mapping** pattern of CA's
CeridianDayforce adapter. All three are CSV (or XLSX→CSV exported)
with per-customer column variation. The same parser handles all three
with different `:column-mapping` configs.

## 6. The IIT emit payload

The **自然人电子税务局** desktop import format is XML-based (the
"全员全额扣缴申报" 申报表 schema, current version 2024-04). The Web
portal also accepts the same XML or a structured Excel.

What kontor emits as `:audit-doc`:

- `:audit-doc/category :payroll-filing`
- `:audit-doc/language :zh-cn`
- `:audit-doc/type :emit-payload`
- `:audit-doc/inline-payload` — the structured CSV/XML payload (kontor
  v1 emits CSV-flat — the engine produces the conversion-grade XML).
- `:audit-doc/code` — `"CN-IIT-{period}-{entity-code}"`.
- `:audit-doc/storage-uri` — `"file://iit/{period}/{entity-code}.csv"`
  (consumer overrides).

## 7. Scope discipline (codified in ADR-085)

**v1 ships:**

1. Gross-to-net mapping (engine output → PayrollFacts).
2. 五险一金 component-kind catalog (12 standard kinds + 3 carry-only).
3. 应付职工薪酬 sub-account routing via `:account-tag/*`.
4. 年终奖 separate tracking + monthly accrual primitive.
5. IIT monthly filing audit-doc (the **CN-IIT** emit-payload).

**Out of scope for v1:**

- 企业年金 detailed accounting (vesting, asset-management fees, etc.).
- 残保金 (disability employment guarantee fund — annual).
- 工会经费 (trade union fund — 2% of wages).
- 职工教育经费 (worker education fund — 1.5–2.5% of wages).
- Per-city PT-equivalent (some cities have a tiny 个人调节税 layer).
- Full 自然人电子税务局 XML (CSV+structured is adequate; XML is a
  consumer convertor step).

## 8. License posture

Per CLAUDE.md + ADR-001 + ADR-005 + ADR-071 + ADR-075 + ADR-085:

- STA (国家税务总局) + Ministry of Finance (财政部) specs are public
  (政府信息公开 — Government Information Disclosure regulation).
- No proprietary code lifted from Yonyou / Kingdee / Beisen.
- No per-city SI/HF rate tables bundled (200+ cities, change ~annually).
- No IIT bracket tables bundled (regulator-versioned).
- No 自然人电子税务局 credentials bundled.

## 9. References

- 中华人民共和国个人所得税法 (2018 revision; Articles 6, 11)
- 中华人民共和国个人所得税法实施条例 (国务院令 707, 2018-12-18)
- 国家税务总局公告 2018年第61号 (cumulative-method withholding)
- 财税〔2018〕164号 (年终奖 separate-tax election; extended to 2027 by
  财政部国家税务总局公告 2023年第30号)
- 国发〔2019〕10号 (生育保险 + 医疗保险 merger)
- 财会〔2014〕8号 (CAS 9 — Employee Compensation)
- GB 32100-2015 (USCC — already used in l10n-cn/identifiers)
- GB/T 2260 (administrative-division codes — relevant for SI city)
